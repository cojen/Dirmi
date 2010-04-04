/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import java.util.Map;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;

import javax.security.auth.Subject;

import org.cojen.dirmi.Environment;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class Connector implements JMXConnector {
    private final JMXServiceURL serviceURL;
    private final Map<String,?> environment;

    private Environment mDirmiEnv;
    private MBeanServerConnection mCon;

    public Connector(JMXServiceURL serviceURL, Map<String,?> environment) {
        this.serviceURL = serviceURL;
        this.environment = environment;
    }

    public void connect() throws IOException {
        connect(null);
    }

    public synchronized void connect(Map<String,?> env) throws IOException {
        if (mDirmiEnv != null) {
            return;
        }

        Environment dirmiEnv = new Environment();

        MBeanServerConnection con = (MBeanServerConnection)
            dirmiEnv.newSessionConnector(serviceURL.getHost(), serviceURL.getPort())
            .connect()
            .receive();

        con = Wrapper.from
            (ClientMBeanServerConnection.class, MBeanServerConnection.class)
            .wrap(con);
            
        mDirmiEnv = dirmiEnv;
        mCon = con;
    }

    public MBeanServerConnection getMBeanServerConnection() throws IOException {
        return mCon;
    }

    public MBeanServerConnection getMBeanServerConnection(Subject delegationSubject)
        throws IOException
    {
        return getMBeanServerConnection();
    }

    public synchronized void close() throws IOException {
        mCon = null;
        Environment dirmiEnv = mDirmiEnv;
        if (dirmiEnv != null) {
            mDirmiEnv = null;
            dirmiEnv.close();
        }
    }

    public void addConnectionNotificationListener(NotificationListener listener,
                                                  NotificationFilter filter,
                                                  Object handback)
    {
        System.out.println("addConnectionNotificationListener: " + listener +
                           ", " + filter + ", " + handback);
        // FIXME
    }

    public void removeConnectionNotificationListener(NotificationListener listener)
        throws ListenerNotFoundException
    {
        System.out.println("addConnectionNotificationListener: " + listener);
        // FIXME
    }

    public void removeConnectionNotificationListener(NotificationListener listener,
                                                     NotificationFilter filter,
                                                     Object handback)
        throws ListenerNotFoundException
    {
        // FIXME
    }

    public String getConnectionId() throws IOException {
        // FIXME
        return null;
    }
}
