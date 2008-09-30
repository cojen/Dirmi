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

import java.io.IOException;
import java.net.InetSocketAddress;

import dirmi.Session;
import dirmi.SessionAcceptor;
import dirmi.SessionListener;

import dirmi.io2.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestServer implements TestRemote {
    public static void main(String[] args) throws Exception {
        ThreadPool pool = new ThreadPool(100, false, "dirmi");

        StreamAcceptor acceptor = new SocketStreamAcceptor
            (new InetSocketAddress(Integer.parseInt(args[0])), pool);

        StreamBrokerAcceptor brokerAcceptor = new StreamBrokerAcceptor(acceptor, pool);

        final SessionAcceptor sessionAcceptor = new StandardSessionAcceptor(brokerAcceptor, pool);
        final TestServer exportedServer = new TestServer();

        sessionAcceptor.accept(exportedServer, new SessionListener() {
            public void established(Session session) {
                System.out.println("Accepted: " + session);
                sessionAcceptor.accept(exportedServer, this);
            }

            public void failed(IOException e) {
                e.printStackTrace(System.out);
            }
        });
    }

    public void doIt(String message) {
        System.out.println("hello: " + message);
    }
}
