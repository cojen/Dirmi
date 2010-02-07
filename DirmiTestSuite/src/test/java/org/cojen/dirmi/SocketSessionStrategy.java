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

package org.cojen.dirmi;

import java.io.IOException;

import java.net.InetSocketAddress;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketSessionStrategy extends SessionStrategy {
    private volatile SessionAcceptor acceptor;

    protected SocketSessionStrategy(final Environment env,
                                    final Object localServer, final Object remoteServer)
        throws Exception
    {
        super(env);

        acceptor = env.newSessionAcceptor(new InetSocketAddress("127.0.0.1", 0));

        final boolean[] estNotify = {false};

        acceptor.accept(new SessionListener() {
            public void established(Session session) {
                try {
                    remoteSession = session;
                    session.send(remoteServer);
                    SocketSessionStrategy.this.localServer = session.receive();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    synchronized (estNotify) {
                        estNotify[0] = true;
                        estNotify.notifyAll();
                    }
                }
            }

            public void establishFailed(IOException e) {
                e.printStackTrace();
            }

            public void acceptFailed(IOException e) {
                e.printStackTrace();
            }
        });

        localSession = env.newSessionConnector
            ((InetSocketAddress) acceptor.getLocalAddress()).connect();

        localSession.send(localServer);
        this.remoteServer = localSession.receive();

        synchronized (estNotify) {
            while (!estNotify[0]) {
                estNotify.wait();
            }
        }
    }

    public void tearDown() throws Exception {
        super.tearDown();

        SessionAcceptor acceptor = this.acceptor;
        if (acceptor != null) {
            acceptor.close();
        }
    }
}
