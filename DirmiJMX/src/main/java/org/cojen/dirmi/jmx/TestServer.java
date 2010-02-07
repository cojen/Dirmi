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

import java.util.Map;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;

import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Must set system property via command line:
 * -Djmx.remote.protocol.provider.pkgs=org.cojen.dirmi.jmx
 *
 * <p>Given a protocol of "dirmi", the org.cojen.dirmi.jmx.dirmi.ServerProvider
 * class will be loaded to support the protocol.
 *
 * @author Brian S O'Neill
 */
public class TestServer {
    public static void main(String[] args) throws Exception {
        JMXServiceURL url = new JMXServiceURL("service:jmx:dirmi://localhost:1234/foo");
        Map<String,?> env = null;

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        JMXConnectorServer server = JMXConnectorServerFactory
            .newJMXConnectorServer(url, env, mbeanServer);
        server.start();
    }
}
