/*
 *  Copyright 2009 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.jmx;

import javax.management.Notification;
import javax.management.NotificationListener;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class NotificationListenerServer implements RemoteNotificationListener {
    private final NotificationListener mListener;
    private final Object mHandback;

    NotificationListenerServer(NotificationListener listener, Object handback) {
        mListener = listener;
        mHandback = handback;
    }

    public void handleNotification(Notification notification, Object handback) {
        mListener.handleNotification(notification, mHandback);
    }
}
