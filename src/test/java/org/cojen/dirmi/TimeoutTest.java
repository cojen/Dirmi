/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.io.PipedInputStream;
import org.cojen.dirmi.io.PipedOutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TimeoutTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(TimeoutTest.class.getName());
    }

    private Environment mEnv;

    private LockedOutputStream mClientOut, mServerOut;

    private Session<Echo> mClientSession;
    private Session<Echo> mServerSession;

    private void setup(boolean lockEarly) throws Exception {
        mEnv = Environment.create();
        mEnv.export("main", new EchoServer());

        var connector = new Connector() {
            private Object mResult;

            @Override
            public void connect(Session session) throws IOException {
                // Connect with a local pipe and simulate slow writes with a lock.

                var clientIn = new PipedInputStream();
                OutputStream serverOut = new PipedOutputStream(clientIn);

                var serverIn = new PipedInputStream();
                OutputStream clientOut = new PipedOutputStream(serverIn);

                synchronized (this) {
                    if (mClientOut == null) {
                        mClientOut = new LockedOutputStream(clientOut);
                        mServerOut = new LockedOutputStream(serverOut);
                        clientOut = mClientOut;
                        serverOut = mServerOut;

                        if (lockEarly) {
                            mServerOut.lock();
                        }
                    }
                }

                final OutputStream fserverOut = serverOut;

                mEnv.execute(() -> {
                    synchronized (this) {
                        try {
                            mResult = mEnv.accepted(null, null, serverIn, fserverOut);
                        } catch (Exception e) {
                            mResult = e;
                        }
                        notify();
                    }
                });

                session.connected(null, null, clientIn, clientOut);
            }

            @SuppressWarnings("unchecked")
            synchronized Session<Echo> await() throws Exception {
                while (mResult == null) {
                    wait();
                }
                if (mResult instanceof Session) {
                    return (Session<Echo>) mResult;
                }
                throw (Exception) mResult;
            }
        };

        mEnv.connector(connector);

        mClientSession = mEnv.connect(Echo.class, "main", null);
        mServerSession = connector.await();
    }

    @After
    public void teardown() throws Exception {
        if (mEnv != null) {
            mEnv.close();
        }
    }

    @Test
    public void serverStall() throws Exception {
        setup(false);

        Echo echo = mClientSession.root();

        // Test without locking, to allow some pings to be sent and received initially. This
        // isn't necessary for the test, but it ensures that the ping handling code is run.
        for (int i=0; i<5; i++) {
            String msg = "hello-" + i;
            assertEquals(msg, echo.echo(msg));
            Thread.sleep(1000);
        }

        mServerOut.lock();

        try {
            for (int i=0; i<20; i++) {
                String msg = "hello-" + i;
                assertEquals(msg, echo.echo(msg));
                Thread.sleep(1000);
            }
        } catch (ClosedException | DisconnectedException e) {
            assertTrue(e.getMessage().contains("ping"));
            return;
        } finally {
            mServerOut.unlock();
        }

        fail("session wasn't closed");
    }

    @Test
    public void setupStall() throws Exception {
        // The session cannot be established, and CloseTimeout closes the connection.
        try {
            setup(true);
            fail();
        } catch (ClosedException e) {
        }
    }

    public static interface Echo extends Remote {
        String echo(String msg) throws RemoteException;
    }

    private static class EchoServer implements Echo {
        @Override
        public String echo(String msg) {
            return msg;
        }
    }
}
