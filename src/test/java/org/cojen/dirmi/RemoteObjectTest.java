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
public class RemoteObjectTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RemoteObjectTest.class.getName());
    }

    private Environment mServerEnv, mClientEnv;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mServerEnv = Environment.create();
        mServerEnv.export("main", new R1Server());
        var ss = new ServerSocket(0);
        mServerEnv.acceptAll(ss);

        mClientEnv = Environment.create();
        mSession = mClientEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        if (mServerEnv != null) {
            mServerEnv.close();
        }
        if (mClientEnv != null) {
            mClientEnv.close();
        }
    }

    @Test
    public void basic() throws Exception {
        R1 root = mSession.root();

        R2 r2 = root.c1(123);
        assertEquals("hello 123", r2.c2());

        r2 = root.c1(456);
        assertEquals("hello 456", r2.c2());

        assertEquals(mSession, Session.access(root));
        assertEquals(mSession, Session.access(r2));

        r2.dispose();
        try {
            r2.c2();
            fail();
        } catch (ClosedException e) {
            assertTrue(e.getMessage().contains("disposed"));
        }

        try {
            Session.access(r2);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("disposed"));
        }

        try {
            Session.access(this);
            fail();
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void callback() throws Exception {
        R1 root = mSession.root();

        var callback = new R3() {
            private String mMessage;

            @Override
            public synchronized void c3(String message) {
                mMessage = message;
                notify();
            }

            public synchronized String await(long remaining) throws Exception {
                long end = System.currentTimeMillis() + remaining;
                while (true) {
                    if (mMessage != null) {
                        return mMessage;
                    }
                    if (remaining <= 0) {
                        throw new Exception("timeout");
                    }
                    wait(remaining);
                    remaining = end - System.currentTimeMillis();
                }
            }
        };

        root.c2(callback);

        assertEquals("hello", callback.await(10_000));
    }

    @Test
    public void passback() throws Exception {
        R1 root = mSession.root();

        R2 r2 = root.c1(123);
        assertEquals("hello 123", r2.c2());

        Object[] result = root.c3(r2);
        assertEquals(3, result.length);
        assertTrue(result[0] instanceof org.cojen.dirmi.core.Stub);
        assertEquals(R2Server.class.getName(), result[1]);
        assertEquals("hello 123", result[2]);

        r2.dispose();
        try {
            r2.c2();
            fail();
        } catch (ClosedException e) {
            assertTrue(e.getMessage().contains("disposed"));
        }
    }

    @Test
    public void currentSession() throws Exception {
        try {
            Session.current();
            fail();
        } catch (IllegalStateException e) {
        }

        R1 root = mSession.root();
        assertTrue(root.c4().contains("ServerSession"));
    }

    public static interface R1 extends Remote {
        R2 c1(int param) throws RemoteException;

        void c2(R3 callback) throws RemoteException;

        Object[] c3(R2 r2) throws RemoteException;

        String c4() throws RemoteException;
    }

    private static class R1Server implements R1 {
        @Override
        public R2 c1(int param) {
            return new R2Server(param);
        }

        @Override
        public void c2(R3 callback) throws RemoteException {
            callback.c3("hello");

            try {
                callback.c3("world");
                fail();
            } catch (ClosedException e) {
                assertTrue(e.getMessage().contains("disposed"));
            }
        }

        @Override
        public Object[] c3(R2 r2) throws RemoteException {
            return new Object[] {r2, r2.getClass().getName(), r2.c2()};
        }

        @Override
        public String c4() {
            return Session.current().toString();
        }
    }

    public static interface R2 extends Remote {
        String c2() throws RemoteException;

        @Disposer
        @RemoteFailure(declared=false)
        void dispose();
    }

    private static class R2Server implements R2 {
        private final int mParam;

        R2Server(int param) {
            mParam = param;
        }

        @Override
        public String c2() {
            return "hello " + mParam;
        }

        @Disposer
        public void dispose() {
        }
    }

    public static interface R3 extends Remote {
        @Disposer
        void c3(String message) throws RemoteException;
    }
}
