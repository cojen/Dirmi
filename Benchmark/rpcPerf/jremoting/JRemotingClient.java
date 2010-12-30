/*
 *  Copyright 2010 Brian S O'Neill
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

package rpcPerf.jremoting;

import org.codehaus.jremoting.client.SocketDetails;
import org.codehaus.jremoting.client.monitors.ConsoleClientMonitor;
import org.codehaus.jremoting.client.resolver.ServiceResolver;
import org.codehaus.jremoting.client.streams.ByteStream;
import org.codehaus.jremoting.client.transports.SocketTransport;

import rpcPerf.PerfTest;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class JRemotingClient implements rpcPerf.Client {
    public static void main(String[] args) throws Exception {
        PerfTest test = new PerfTest(new JRemotingClient(args[0], Integer.parseInt(args[1])));
        test.test(Integer.parseInt(args[2]));
    }

    private final String mHost;
    private final int mPort;

    public JRemotingClient(String host, int port) {
        mHost = host;
        mPort = port;
    }

    public void test(int iterations) throws Exception {
        ServiceResolver resolver =
            new ServiceResolver
            (new SocketTransport(new ConsoleClientMonitor(),
                                 new ByteStream(),
                                 new SocketDetails(mHost, mPort)));
        try {
            RemoteInterface remote = resolver.resolveService("test");
            for (int i=0; i<iterations; i++) {
                remote.doIt();
            }
        } finally {
            resolver.close();
        }
    }
}

