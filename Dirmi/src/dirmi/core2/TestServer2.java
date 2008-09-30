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

package dirmi.core2;

import java.io.IOException;
import java.net.InetSocketAddress;

import dirmi.Session;
import dirmi.SessionServer2;

import dirmi.nio2.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestServer2 implements TestRemote {
    public static void main(String[] args) throws Exception {
        ThreadPool pool = new ThreadPool(100, true, "dirmi");
        final SocketStreamProcessor2 processor = new SocketStreamProcessor2(pool);
        final StreamAcceptor acceptor = processor.newAcceptor
            (new InetSocketAddress(Integer.parseInt(args[0])));

        StreamBrokerAcceptor brokerAcceptor = new StreamBrokerAcceptor(acceptor);

        SessionServer2 server = new StandardSessionServer2
            (brokerAcceptor, new TestServer2(), pool);

        while (true) {
            Session session = server.accept();
            System.out.println("Accepted: " + session);
        }
    }

    public void doIt(String message) {
        System.out.println("hello: " + message);
    }
}
