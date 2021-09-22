/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.changerequest.internal.handlers;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.changerequest.ApproversManager;
import org.xwiki.contrib.changerequest.ChangeRequest;
import org.xwiki.contrib.changerequest.ChangeRequestException;
import org.xwiki.contrib.changerequest.ChangeRequestManager;
import org.xwiki.contrib.changerequest.ChangeRequestReference;
import org.xwiki.contrib.changerequest.ChangeRequestReview;
import org.xwiki.contrib.changerequest.FileChange;
import org.xwiki.contrib.changerequest.events.ChangeRequestFileChangeAddedEvent;
import org.xwiki.contrib.changerequest.internal.FileChangeVersionManager;
import org.xwiki.contrib.changerequest.storage.ReviewStorageManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.store.merge.MergeDocumentResult;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.web.EditForm;

/**
 * Handler for adding changes to an existing change request.
 *
 * @version $Id$
 * @since 0.3
 */
@Component
@Named("addchanges")
@Singleton
public class AddChangesChangeRequestHandler extends AbstractChangeRequestActionHandler
{
    static final String PREVIOUS_VERSION_PARAMETER = "previousVersion";

    @Inject
    private ChangeRequestManager changeRequestManager;

    @Inject
    private UserReferenceResolver<CurrentUserReference> userReferenceResolver;

    @Inject
    private FileChangeVersionManager fileChangeVersionManager;

    @Inject
    private ApproversManager<DocumentReference> documentReferenceApproversManager;

    @Inject
    private ApproversManager<ChangeRequest> changeRequestApproversManager;

    @Inject
    private ReviewStorageManager reviewStorageManager;

    @Override
    public void handle(ChangeRequestReference changeRequestReference) throws ChangeRequestException, IOException
    {
        HttpServletRequest request = this.prepareRequest();
        EditForm editForm = this.prepareForm(request);
        XWikiDocument modifiedDocument = this.prepareDocument(request, editForm);
        DocumentReference documentReference = modifiedDocument.getDocumentReferenceWithLocale();
        ChangeRequest changeRequest = this.loadChangeRequest(changeRequestReference);
        boolean isDeletion = "1".equals(request.getParameter("deletion"));

        if (changeRequest != null) {
            UserReference currentUser = this.userReferenceResolver.resolve(CurrentUserReference.INSTANCE);

            FileChange fileChange =
                this.createFileChange(isDeletion, changeRequest, modifiedDocument, documentReference, request,
                    currentUser);
            if (fileChange != null) {
                changeRequest.addFileChange(fileChange);
                this.storageManager.save(changeRequest);
                this.addApprovers(documentReference, changeRequest);
                this.invalidateApprovals(changeRequest);
                this.observationManager
                    .notify(new ChangeRequestFileChangeAddedEvent(), documentReference, changeRequest.getId());
                this.redirectToChangeRequest(changeRequest);
            }
        }
    }

    private FileChange createFileChange(boolean isDeletion, ChangeRequest changeRequest, XWikiDocument modifiedDocument,
        DocumentReference documentReference, HttpServletRequest request, UserReference currentUser)
        throws ChangeRequestException, IOException
    {
        FileChange fileChange;

        if (isDeletion) {
            fileChange = new FileChange(changeRequest, FileChange.FileChangeType.DELETION);
            String previousVersion = modifiedDocument.getVersion();
            String fileChangeVersion =
                this.fileChangeVersionManager.getNextFileChangeVersion(previousVersion, false);
            fileChange
                .setPreviousVersion(previousVersion)
                .setPreviousPublishedVersion(previousVersion)
                .setVersion(fileChangeVersion);
        } else {
            fileChange = new FileChange(changeRequest);

            Optional<FileChange> optionalFileChange = changeRequest.getLatestFileChangeFor(documentReference);
            String previousVersion = request.getParameter(PREVIOUS_VERSION_PARAMETER);

            if (optionalFileChange.isPresent()) {
                if (!this.addChangeToExistingFileChange(request, changeRequest, fileChange,
                    optionalFileChange.get(), modifiedDocument))
                {
                    return null;
                }
            } else {
                String fileChangeVersion =
                    this.fileChangeVersionManager.getNextFileChangeVersion(previousVersion, false);
                fileChange
                    .setPreviousVersion(previousVersion)
                    .setPreviousPublishedVersion(previousVersion)
                    .setVersion(fileChangeVersion)
                    .setModifiedDocument(modifiedDocument);
            }
        }
        fileChange
            .setAuthor(currentUser)
            .setTargetEntity(documentReference);

        return fileChange;
    }

    private boolean addChangeToExistingFileChange(HttpServletRequest request, ChangeRequest changeRequest,
        FileChange currentFileChange, FileChange latestFileChange, XWikiDocument modifiedDocument)
        throws ChangeRequestException, IOException
    {
        boolean result = false;
        String previousVersion = request.getParameter(PREVIOUS_VERSION_PARAMETER);
        if (StringUtils.equals(request.getParameter("fromchangerequest"), "1")) {
            previousVersion = this.fileChangeVersionManager.getFileChangeVersion(previousVersion);
        }
        String previousPublishedVersion = latestFileChange.getPreviousPublishedVersion();
        Optional<MergeDocumentResult> optionalMergeDocumentResult =
            this.changeRequestManager.mergeDocumentChanges(modifiedDocument, previousVersion, changeRequest);
        if (optionalMergeDocumentResult.isPresent()) {
            MergeDocumentResult mergeDocumentResult = optionalMergeDocumentResult.get();
            String fileChangeVersion = this.fileChangeVersionManager.getNextFileChangeVersion(previousVersion, true);
            if (!mergeDocumentResult.hasConflicts()) {
                currentFileChange
                    .setPreviousPublishedVersion(previousPublishedVersion)
                    .setPreviousVersion(previousVersion)
                    .setVersion(fileChangeVersion)
                    .setModifiedDocument(mergeDocumentResult.getMergeResult());
                result = true;
            } else {
                this.contextProvider.get().getResponse().sendError(409, "Conflict found in the changes.");
            }
        } else {
            this.contextProvider.get().getResponse().sendError(404,
                String.format("Could not find file changes for the given reference: [%s]",
                    modifiedDocument.getDocumentReferenceWithLocale()));
        }
        return result;
    }

    private void addApprovers(DocumentReference originalReference, ChangeRequest changeRequest)
        throws ChangeRequestException
    {
        Set<UserReference> usersCRApprovers =
            new HashSet<>(this.changeRequestApproversManager.getAllApprovers(changeRequest, false));

        Set<DocumentReference> groupsCRApprovers =
            new HashSet<>(this.changeRequestApproversManager.getGroupsApprovers(changeRequest));

        usersCRApprovers.addAll(this.documentReferenceApproversManager.getAllApprovers(originalReference, false));
        groupsCRApprovers.addAll(this.documentReferenceApproversManager.getGroupsApprovers(originalReference));

        if (!groupsCRApprovers.isEmpty()) {
            this.changeRequestApproversManager.setGroupsApprovers(groupsCRApprovers, changeRequest);
        }
        if (!usersCRApprovers.isEmpty()) {
            this.changeRequestApproversManager.setUsersApprovers(usersCRApprovers, changeRequest);
        }
    }

    private void invalidateApprovals(ChangeRequest changeRequest) throws ChangeRequestException
    {
        List<ChangeRequestReview> reviews = changeRequest.getReviews();
        for (ChangeRequestReview review : reviews) {
            if (review.isApproved()) {
                review.setValid(false);
                review.setSaved(false);
            }
            this.reviewStorageManager.save(review);
        }
    }
}
