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
package org.xwiki.contrib.changerequest.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.changerequest.ChangeRequest;
import org.xwiki.contrib.changerequest.ChangeRequestException;
import org.xwiki.contrib.changerequest.ChangeRequestRightsManager;
import org.xwiki.contrib.rights.RightsReader;
import org.xwiki.contrib.rights.RightsWriter;
import org.xwiki.contrib.rights.SecurityRuleAbacus;
import org.xwiki.contrib.rights.SecurityRuleDiff;
import org.xwiki.contrib.rights.WritableSecurityRule;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.security.authorization.AuthorizationException;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.ReadableSecurityRule;
import org.xwiki.security.authorization.Right;
import org.xwiki.security.authorization.RightSet;
import org.xwiki.security.authorization.RuleState;

import com.xpn.xwiki.XWikiException;

/**
 * Component in charge of performing right synchronization operations.
 *
 * @version $Id$
 * @since 0.7
 */
@Component
@Singleton
public class DefaultChangeRequestRightsManager implements ChangeRequestRightsManager
{
    @Inject
    private DocumentReferenceResolver<ChangeRequest> changeRequestDocumentReferenceResolver;

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private RightsWriter rightsWriter;

    @Inject
    private SecurityRuleAbacus ruleAbacus;

    @Inject
    private RightsReader rightsReader;

    @Override
    public void copyAllButViewRights(ChangeRequest originalChangeRequest, ChangeRequest targetChangeRequest)
        throws ChangeRequestException
    {
        DocumentReference originalDocReference =
            this.changeRequestDocumentReferenceResolver.resolve(originalChangeRequest);
        DocumentReference targetDocReference =
            this.changeRequestDocumentReferenceResolver.resolve(targetChangeRequest);

        try {
            List<ReadableSecurityRule> actualRules =
                this.rightsReader.getRules(originalDocReference.getLastSpaceReference(), false);
            List<ReadableSecurityRule> writableSecurityRules = new ArrayList<>();

            for (ReadableSecurityRule actualRule : actualRules) {
                if (actualRule.match(Right.VIEW)) {
                    WritableSecurityRule rule = this.rightsWriter.createRule(actualRule);
                    RightSet rights = rule.getRights();
                    rights.remove(Right.VIEW);
                    if (!rights.isEmpty()) {
                        rule.setRights(rights);
                        writableSecurityRules.add(rule);
                    }
                } else {
                    writableSecurityRules.add(actualRule);
                }
            }
            this.rightsWriter.saveRules(writableSecurityRules, targetDocReference.getLastSpaceReference());
        } catch (AuthorizationException | XWikiException e) {
            throw new ChangeRequestException(
                String.format("Error while trying to retrieve or save rights between [%s] and [%s]",
                    originalDocReference, targetDocReference), e);
        }
    }

    @Override
    public boolean isViewAccessConsistent(ChangeRequest changeRequest, DocumentReference newChange)
        throws ChangeRequestException
    {
        Set<DocumentReference> documentReferences = new HashSet<>(changeRequest.getModifiedDocuments());
        documentReferences.add(newChange);

        Set<DocumentReference> subjects = new HashSet<>();
        for (DocumentReference documentReference : documentReferences) {
            try {
                List<ReadableSecurityRule> actualRules = this.rightsReader.getActualRules(documentReference);
                List<ReadableSecurityRule> normalizedRules = this.ruleAbacus.normalizeRulesBySubject(actualRules);
                for (ReadableSecurityRule normalizedRule : normalizedRules) {
                    if (normalizedRule.match(Right.VIEW)) {
                        subjects.addAll(normalizedRule.getGroups());
                        subjects.addAll(normalizedRule.getUsers());
                    }
                }
            } catch (AuthorizationException e) {
                throw new ChangeRequestException(
                    String.format("Error while trying to access rights for [%s]", documentReference), e);
            }
        }

        return this.isViewAccessConsistent(documentReferences, subjects);
    }

    @Override
    public boolean isViewAccessStillConsistent(ChangeRequest changeRequest,
        Set<DocumentReference> subjectReferences) throws ChangeRequestException
    {
        return this.isViewAccessConsistent(changeRequest.getModifiedDocuments(), subjectReferences);
    }

    private boolean isViewAccessConsistent(Set<DocumentReference> documentReferences,
        Set<DocumentReference> subjectReferences)
    {
        for (DocumentReference subject : subjectReferences) {
            Boolean hasAccess = null;
            for (DocumentReference modifiedDocument : documentReferences) {
                boolean currentHasAccess =
                    this.authorizationManager.hasAccess(Right.VIEW, subject, modifiedDocument);

                if (hasAccess == null) {
                    hasAccess = currentHasAccess;
                } else if (hasAccess != currentHasAccess) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void copyViewRights(ChangeRequest changeRequest, EntityReference newChange)
        throws ChangeRequestException
    {
        DocumentReference changeRequestDocReference =
            this.changeRequestDocumentReferenceResolver.resolve(changeRequest);
        SpaceReference changeRequestSpaceReference = changeRequestDocReference.getLastSpaceReference();

        try {
            List<ReadableSecurityRule> actualRules =
                this.rightsReader.getRules(changeRequestSpaceReference, false);
            List<ReadableSecurityRule> rules = new ArrayList<>(this.rightsWriter.createRules(actualRules));
            List<ReadableSecurityRule> documentRules =
                new ArrayList<>(this.rightsReader.getActualRules(newChange));
            List<ReadableSecurityRule> wikiRules =
                this.rightsReader.getActualRules(newChange.extractReference(EntityType.WIKI));

            // we filter out the wiki reference rules
            documentRules.removeAll(wikiRules);
            for (ReadableSecurityRule actualRule : documentRules) {
                if (actualRule.match(Right.VIEW)) {
                    WritableSecurityRule rule = this.rightsWriter.createRule(actualRule);
                    rule.setRights(Collections.singletonList(Right.VIEW));
                    rules.add(rule);
                }
            }

            this.rightsWriter.saveRules(rules, changeRequestSpaceReference);
        } catch (AuthorizationException | XWikiException e) {
            throw new ChangeRequestException(
                String.format("Error while copying rights from [%s] for change request [%s]", changeRequest, newChange),
                e);
        }
    }

    /**
     * Helper class for computing efficiently the change to perform in {@link #applyChanges(ChangeRequest, List)}.
     */
    private static final class SecurityRuleAction
    {
        private final boolean add;
        private final RuleState state;
        private final boolean isGroup;

        /**
         * Default constructor.
         *
         * @param add {@code true} if it concerns a rule added, else it's a rule deleted.
         * @param state the rule state
         * @param isGroup {@code true} if the associated key in the map where those actions are used is a reference to
         *                a group, else it's a reference to a user.
         */
        SecurityRuleAction(boolean add, RuleState state, boolean isGroup)
        {
            this.add = add;
            this.state = state;
            this.isGroup = isGroup;
        }
    }

    /**
     * Create the {@link SecurityRuleAction} corresponding to the given rule and the given add flag, and put it in the
     * given map.
     *
     * @param rule the rule for which to create the {@link SecurityRuleAction}.
     * @param add to set the flag in the created action.
     * @param map where to put the created action.
     */
    private void creationActionRule(ReadableSecurityRule rule, boolean add,
        Map<DocumentReference, List<SecurityRuleAction>> map)
    {
        SecurityRuleAction action;
        DocumentReference key;
        if (this.isAboutUser(rule)) {
            key = rule.getUsers().get(0);
            action = new SecurityRuleAction(add, rule.getState(), false);
        } else {
            key = rule.getGroups().get(0);
            action = new SecurityRuleAction(add, rule.getState(), true);
        }
        if (!map.containsKey(key)) {
            map.put(key, new ArrayList<>());
        }
        map.get(key).add(action);
    }

    /**
     * Check if the given rule is about a user or a group.
     *
     * @param rule the rule to check.
     * @return {@code true} if the rule is about a user, {@code false} if it's about a group.
     */
    private boolean isAboutUser(ReadableSecurityRule rule)
    {
        return rule.getUsers() != null && !rule.getUsers().isEmpty();
    }

    /**
     * Clone the given rule and apply the changes required by the action before adding it back in the list of rules to
     * write.
     *
     * @param securityRuleAction the change to be performed on the rule.
     * @param normalizedRule the original rule to be updated.
     * @param updatedRules the list of rules to write after the changes.
     */
    private void updateRule(SecurityRuleAction securityRuleAction, ReadableSecurityRule normalizedRule,
        List<ReadableSecurityRule> updatedRules)
    {
        // We ensure first that we won't have twice the rule to write
        updatedRules.remove(normalizedRule);
        RightSet rightSet = normalizedRule.getRights();
        WritableSecurityRule writerRule = this.rightsWriter.createRule(normalizedRule);

        // if the rule has been added then we need to add a view Right to the rule we found,
        // else we need to remove it.
        if (securityRuleAction.add) {
            rightSet.add(Right.VIEW);
        } else {
            rightSet.remove(Right.VIEW);
        }

        // if the rule was only about view right and it has been removed, we already discarded it first,
        // so we don't need to do anything
        if (!rightSet.isEmpty()) {
            writerRule.setRights(rightSet);
            updatedRules.add(writerRule);
        }
    }

    /**
     * Create a new security rule based on an action, when there was no matching rules.
     *
     * @param action the action for which to create a new security rule.
     * @param reference the reference of the user or group to use.
     * @param updatedRules the list of rules to write.
     */
    private void createRule(SecurityRuleAction action, DocumentReference reference,
        List<ReadableSecurityRule> updatedRules)
    {
        // if the action was about deleting a rule, we just ignore it: we did not find a rule concerning it previously
        // there's nothing more to do now.
        if (action.add) {
            WritableSecurityRule rule = this.rightsWriter.createRule();
            if (action.isGroup) {
                rule.setGroups(Collections.singletonList(reference));
            } else {
                rule.setUsers(Collections.singletonList(reference));
            }
            rule.setRights(Collections.singletonList(Right.VIEW));
            rule.setState(action.state);
            updatedRules.add(rule);
        }
    }

    /**
     * Performs the given actions on the change request rights to update them.
     *
     * @param changeRequest the change request on which to perform changes.
     * @param actionsToPerform the rights update to perform that have been previously computed.
     * @param ruleDiffList the original diff list used for logging purpose.
     * @throws ChangeRequestException in case of problem for writing rights.
     */
    private void applyActions(ChangeRequest changeRequest,
        Map<DocumentReference, List<SecurityRuleAction>> actionsToPerform, List<SecurityRuleDiff> ruleDiffList)
        throws ChangeRequestException
    {
        DocumentReference changeRequestDocReference =
            this.changeRequestDocumentReferenceResolver.resolve(changeRequest);
        SpaceReference changeRequestSpaceReference = changeRequestDocReference.getLastSpaceReference();

        try {
            // first we get the rules that concerns the change request
            List<ReadableSecurityRule> actualRules =
                this.rightsReader.getRules(changeRequestSpaceReference, false);

            // then we normalize them to allow using properly the map of actions
            List<ReadableSecurityRule> normalizedRules = this.ruleAbacus.normalizeRulesBySubject(actualRules);

            // we create a copy of the list to allow modifying it when iterating on the original
            List<ReadableSecurityRule> updatedRules = new ArrayList<>(normalizedRules);

            for (ReadableSecurityRule normalizedRule : normalizedRules) {
                // the rule applies on the given target
                DocumentReference target = (this.isAboutUser(normalizedRule))
                    ? normalizedRule.getUsers().get(0) : normalizedRule.getGroups().get(0);

                // we check if there's one or several actions to perform
                if (actionsToPerform.containsKey(target)) {

                    // we create a copy of the list to allow removing the actions while iterating
                    List<SecurityRuleAction> securityRuleActions = actionsToPerform.get(target);

                    for (SecurityRuleAction securityRuleAction : new ArrayList<>(securityRuleActions)) {

                        // we only update the rule if the state is the same than in the action
                        // we're doing this because we're not supposed to change the state of the rule:
                        // we only create or remove rules. A change of a state will create two actions, one for removing
                        // and one for creating.
                        // This strategy is tightly linked to the way normalizing rules work right now, see the javadoc
                        // of SecurityRuleAbacus.
                        if (normalizedRule.getState() == securityRuleAction.state) {
                            this.updateRule(securityRuleAction, normalizedRule, updatedRules);

                            // we remove the action once applied
                            securityRuleActions.remove(securityRuleAction);
                        }
                    }

                    // if there's no more action, we remove the entry
                    if (securityRuleActions.isEmpty()) {
                        actionsToPerform.remove(target);
                    }
                }
            }

            // We might still have actions to apply if there was no previous rules concerning the targets:
            // here we apply only the action for adding rules.
            for (Map.Entry<DocumentReference, List<SecurityRuleAction>> entry : actionsToPerform.entrySet()) {
                DocumentReference reference = entry.getKey();

                for (SecurityRuleAction action : entry.getValue()) {
                    this.createRule(action, reference, updatedRules);
                }
            }

            // finally we write all rules
            this.rightsWriter.saveRules(updatedRules, changeRequestSpaceReference);
        } catch (AuthorizationException | XWikiException e) {
            throw new ChangeRequestException(String.format("Error while applying rights changes for change request "
                + "[%s] with diff [%s]", changeRequest, ruleDiffList),
                e);
        }
    }

    private Map<DocumentReference, List<SecurityRuleAction>> computeActionsMap(List<SecurityRuleDiff> ruleDiffList)
    {
        Map<DocumentReference, List<SecurityRuleAction>> actionsToPerform = new HashMap<>();

        for (SecurityRuleDiff securityRuleDiff : ruleDiffList) {
            ReadableSecurityRule currentRule = securityRuleDiff.getCurrentRule();
            ReadableSecurityRule previousRule = securityRuleDiff.getPreviousRule();
            switch (securityRuleDiff.getChangeType()) {
                case RULE_ADDED:
                    if (currentRule.match(Right.VIEW)) {
                        this.creationActionRule(currentRule, true, actionsToPerform);
                    }
                    break;

                case RULE_DELETED:
                    if (previousRule.match(Right.VIEW)) {
                        this.creationActionRule(previousRule, false, actionsToPerform);
                    }
                    break;

                default:
                case RULE_UPDATED:
                    if (previousRule.match(Right.VIEW)) {
                        this.creationActionRule(previousRule, false, actionsToPerform);
                    }
                    if (currentRule.match(Right.VIEW)) {
                        this.creationActionRule(currentRule, true, actionsToPerform);
                    }
                    break;
            }
        }
        return actionsToPerform;
    }

    @Override
    public void applyChanges(ChangeRequest changeRequest, List<SecurityRuleDiff> ruleDiffList)
        throws ChangeRequestException
    {
        // This methods works in two main steps:
        //   1. we compute a list of actions to perform on rights, indexed by the reference of the group or user
        //      targeted by the right rule. This data structure is chosen because of the way rules are normalized
        //      with the SecurityRuleAbacus.
        //   2. we apply the map of actions on the change request rights.

        Map<DocumentReference, List<SecurityRuleAction>> actionsToPerform =
            this.computeActionsMap(ruleDiffList);

        if (!actionsToPerform.isEmpty()) {
            this.applyActions(changeRequest, actionsToPerform, ruleDiffList);
        }
    }
}
