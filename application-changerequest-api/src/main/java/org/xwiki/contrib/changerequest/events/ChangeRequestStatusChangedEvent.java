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
package org.xwiki.contrib.changerequest.events;

import java.io.Serializable;

import org.xwiki.contrib.changerequest.ChangeRequestStatus;
import org.xwiki.observation.event.Event;
import org.xwiki.stability.Unstable;

/**
 * Event triggered when the status of a change request changed.
 *
 * The event also send the following parameters:
 * <ul>
 *     <li>source: the change request identifier</li>
 *     <li>data: an array of {@link ChangeRequestStatus} containing the old and the new status</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.6
 */
@Unstable
public class ChangeRequestStatusChangedEvent implements Event, Serializable
{
    /**
     * Default constructor.
     */
    public ChangeRequestStatusChangedEvent()
    {
    }

    @Override
    public boolean matches(Object otherEvent)
    {
        return otherEvent instanceof ChangeRequestStatusChangedEvent;
    }
}
