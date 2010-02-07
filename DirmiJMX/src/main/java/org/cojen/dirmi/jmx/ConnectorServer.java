/*
 *  Copyright 2008-2009 Brian S O'Neill
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

import java.net.InetSocketAddress;

import java.util.Collections;
import java.util.Map;

import javax.management.MBeanServer;

import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXServiceURL;

import org.cojen.dirmi.Environment;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ConnectorServer extends JMXConnectorServer {
    private final JMXServiceURL serviceURL;
    private final Map<String,?> environment;
    private final MBeanServer mbeanServer;

    private Environment mDirmiEnv;

    public ConnectorServer(JMXServiceURL serviceURL,
                           Map<String,?> environment,
                           MBeanServer mbeanServer)
    {
        this.serviceURL = serviceURL;
        this.environment = environment;
        this.mbeanServer = mbeanServer;
    }

    public synchronized void start() throws IOException {
        if (mDirmiEnv != null) {
            return;
        }

        Environment dirmiEnv = new Environment();

        RemoteMBeanServerConnection server = Wrapper.from
            (RemoteMBeanServerConnection.class, MBeanServer.class)
            .wrap(mbeanServer);

        dirmiEnv.newSessionAcceptor
            (new InetSocketAddress(serviceURL.getHost(), serviceURL.getPort()))
            .acceptAll(server);

        mDirmiEnv = dirmiEnv;
    }

    public synchronized void stop() throws IOException {
        Environment dirmiEnv = mDirmiEnv;
        if (dirmiEnv != null) {
            mDirmiEnv = null;
            dirmiEnv.close();
        }
    }

    public synchronized boolean isActive() {
        return mDirmiEnv != null;
    }

    public JMXServiceURL getAddress() {
        return serviceURL;
    }

    public Map<String,?> getAttributes() {
        return Collections.emptyMap();
    }
}
