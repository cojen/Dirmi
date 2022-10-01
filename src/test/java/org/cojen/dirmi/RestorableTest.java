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

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.core.CoreUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RestorableTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RestorableTest.class.getName());
    }

    private Environment mEnv;
    private ServerSocket mServerSocket;
    private Acceptor mAcceptor;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mEnv.export("main", new R1Server());
        mServerSocket = new ServerSocket(0);
        mAcceptor = new Acceptor(mEnv, mServerSocket);
        mEnv.execute(mAcceptor);

        mSession = mEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        if (mAcceptor != null) {
            mAcceptor.close();
        }
        mEnv.close();
    }

    @Test
    public void basic() throws Exception {
        R1 root = mSession.root();
        R2 r2 = root.a(123);

        try {
            root.b(999).a();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("non-restorable parent"));
        }

        R1 r1x = r2.a();

        mAcceptor.suspend();
        mAcceptor.closeLastAccepted();

        int reconnectAt = 5;
        int disconnected = 0;

        for (int i=0; i<8; i++) {
            try {
                root.a(123);
                if (i <= reconnectAt) {
                    fail();
                }
            } catch (DisconnectedException e) {
                disconnected++;
            } catch (RemoteException e) {
            }

            try {
                assertEquals("123:789", r2.b(789));
                if (i <= reconnectAt) {
                    fail();
                }
            } catch (DisconnectedException e) {
                disconnected++;
            } catch (RemoteException e) {
            }

            try {
                assertEquals(111, r1x.b(111).c());
                if (i <= reconnectAt) {
                    fail();
                }
            } catch (DisconnectedException e) {
                disconnected++;
            } catch (RemoteException e) {
            }

            Thread.sleep(1000);

            if (i == reconnectAt) {
                mAcceptor.resume();
            }
        }

        assertTrue(disconnected > 0);
    }

    private static class Acceptor implements Runnable {
        final Environment mEnv;
        final ServerSocket mServerSocket;
        volatile boolean mClosed;
        boolean mAccepting;
        boolean mSuspended;
        Session mSession;

        Acceptor(Environment env, ServerSocket ss) throws IOException {
            mEnv = env;
            mServerSocket = ss;
            ss.setSoTimeout(10);
        }

        @Override
        public void run() {
            try {
                while (!mClosed) {
                    synchronized (this) {
                        while (mSuspended) {
                            wait();
                        }
                        mAccepting = true;
                    }

                    Socket s;
                    try {
                        s = mServerSocket.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    } finally {
                        synchronized (this) {
                            mAccepting = false;
                            notify();
                        }
                    }

                    Session session = mEnv.accepted(s);

                    synchronized (this) {
                        mSession = session;
                    }
                }
            } catch (RemoteException e) {
                // Ignore.
            } catch (IOException | InterruptedException e) {
                if (!mClosed) {
                    e.printStackTrace();
                }
            }
        }

        void closeLastAccepted() {
            Session session;
            synchronized (this) {
                session = mSession;
                mSession = null;
            }
            if (session != null) {
                session.close();
            }
        }

        synchronized void suspend() throws InterruptedException {
            mSuspended = true;
            while (mAccepting) {
                wait();
            }
        }

        synchronized void resume() {
            mSuspended = false;
            notify();
        }

        void close() {
            mClosed = true;
            CoreUtils.closeQuietly(mServerSocket);
            resume();
        }
    }

    public static interface R1 extends Remote {
        @Restorable
        R2 a(int param) throws RemoteException;

        R2 b(int param) throws RemoteException;
    }

    public static interface R2 extends Remote {
        @Restorable
        R1 a() throws RemoteException;

        String b(int param) throws RemoteException;

        int c() throws RemoteException;
    }

    private static class R1Server implements R1 {
        @Override
        public R2 a(int param) {
            return new R2Server(param);
        }

        @Override
        public R2 b(int param) {
            return new R2Server(param);
        }
    }

    private static class R2Server implements R2 {
        private final int mParam;

        R2Server(int param) {
            mParam = param;
        }

        @Override
        public R1 a() {
            return new R1Server();
        }

        @Override
        public String b(int param) {
            return mParam + ":" + param;
        }

        @Override
        public int c() {
            return mParam;
        }
    }
}
