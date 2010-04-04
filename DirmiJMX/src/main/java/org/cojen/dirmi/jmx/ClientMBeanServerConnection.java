/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.io.IOException;

import javax.management.InstanceNotFoundException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class ClientMBeanServerConnection implements MBeanServerConnection {
    private final MBeanServerConnection mCon;

    protected ClientMBeanServerConnection(MBeanServerConnection con) {
        mCon = con;
    }

    public void addNotificationListener(ObjectName name,
                                        NotificationListener listener,
                                        NotificationFilter filter,
                                        Object handback)
        throws InstanceNotFoundException, IOException
    {
        mCon.addNotificationListener
            (name, new NotificationListenerServer(listener, handback), filter, null);
    }

    public void removeNotificationListener(ObjectName name,
                                           NotificationListener listener)
        throws InstanceNotFoundException, ListenerNotFoundException, IOException
    {
        // FIXME
        System.out.println("removeNotificationListener: " + name + ", " + listener);
    }

    public void removeNotificationListener(ObjectName name,
                                           NotificationListener listener,
                                           NotificationFilter filter,
                                           Object handback)
        throws InstanceNotFoundException, ListenerNotFoundException, IOException
    {
        // FIXME
        System.out.println("removeNotificationListener: " + name + ", " + listener + ", " +
                           filter + ", " + handback);
    }
}
