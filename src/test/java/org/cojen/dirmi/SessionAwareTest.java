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

import java.net.ServerSocket;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SessionAwareTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SessionAwareTest.class.getName());
    }

    private Environment mEnv;
    private R1Server mServer;
    private ServerSocket mServerSocket;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mServer = new R1Server();
        mEnv.export("main", mServer);
        mServerSocket = new ServerSocket(0);
        mEnv.acceptAll(mServerSocket);

        mSession = mEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        mEnv.close();
    }

    @Test
    public void basic() throws Exception {
        for (int i=0; i<100; i++) {
            if (mServer.mSession != null) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(mServer.mSession);
        assertNotSame(mSession, mServer.mSession);

        R2 r2 = mSession.root().r2();
        assertEquals(mServer.mSession.toString(), r2.sessionString());
        assertNotNull(R2Server.cSession);
        r2.end();
        R2Server.awaitDetachment();

        r2 = mSession.root().lazy2();
        assertNull(R2Server.cSession);
        mSession.root().done();
        assertEquals(mServer.mSession.toString(), r2.sessionString());
        assertNotNull(R2Server.cSession);
        r2.end();
        R2Server.awaitDetachment();

        // Although a batched call is desired, it's being used for the first time and so it
        // won't actually be lazily flushed.
        R3 r3 = mSession.root().lazy3();
        assertEquals(mServer.mSession.toString(), r3.sessionString());
        assertNotNull(R3Server.cSession);
        r3.end();
        R3Server.awaitDetachment();

        mEnv.close();
        assertNull(mServer.mSession);
    }

    public static interface R1 extends Remote {
        R2 r2() throws RemoteException;

        @Batched
        R2 lazy2() throws RemoteException;

        @Batched
        R3 lazy3() throws RemoteException;

        void done() throws RemoteException;
    }

    public static interface R2 extends Remote {
        String sessionString() throws RemoteException;

        @Disposer
        void end() throws RemoteException;
    }

    public static interface R3 extends R2 {
    }

    private static class R1Server implements R1, SessionAware {
        volatile Session mSession;

        @Override
        public R2 r2() {
            return new R2Server();
        }

        @Override
        public R2 lazy2() {
            return new R2Server();
        }

        @Override
        public R3 lazy3() {
            return new R3Server();
        }

        @Override
        public void done() {
        }

        @Override
        public void attached(Session s) {
            mSession = s;
        }

        @Override
        public void detached(Session s) {
            if (s == mSession) {
                mSession = null;
            }
        }
    }

    private static class R2Server implements R2, SessionAware {
        static volatile Session cSession;
        volatile Session mSession;

        @Override
        public String sessionString() {
            Session s = mSession;
            return s == null ? null : s.toString();
        }

        @Override
        public void end() {
        }

        @Override
        public void attached(Session s) {
            cSession = s;
            mSession = s;
        }

        @Override
        public void detached(Session s) {
            cSession = null;
            mSession = null;
            synchronized (R2Server.class) {
                R2Server.class.notify();
            }
        }

        static synchronized void awaitDetachment() throws InterruptedException {
            while (cSession != null) {
                R2Server.class.wait();
            }
        }
    }

    private static class R3Server extends R2Server implements R3 {
    }
}
