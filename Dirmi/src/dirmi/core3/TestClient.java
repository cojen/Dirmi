/*
 *  Copyright 2008 Brian S O'Neill
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

package dirmi.core3;

import java.net.InetSocketAddress;

import dirmi.Session;

import dirmi.io2.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestClient {
    public static void main(String[] args) throws Exception {
        InetSocketAddress endpoint = new InetSocketAddress(args[0], Integer.parseInt(args[1]));

        ThreadPool pool = new ThreadPool(100, true, "dirmi");
        // Note: The message processor should be globally shared and have an
        // unbounded pool.
        SocketMessageProcessor processor = new SocketMessageProcessor(pool);
        MessageChannel channel = processor.newConnector(endpoint).connect();
        StreamConnector connector = new SocketStreamConnector(endpoint);
        
        StreamBroker broker = new StreamConnectorBroker(channel, connector);

        Session session = new StandardSession(broker, null, pool);
        System.out.println("Connected: " + session);

        TestRemote server = (TestRemote) session.getRemoteServer();
        System.out.println("Remote server: " + server);

        while (true) {
            System.out.println("yo!");
            server.doIt(new org.joda.time.DateTime().toString());
            Thread.sleep(1000);
        }
    }
}
