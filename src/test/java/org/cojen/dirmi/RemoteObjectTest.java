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
    private ServerSocket mServerSocket;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mServerEnv = Environment.create();
        mServerEnv.export("main", new R1Server());
        mServerSocket = new ServerSocket(0);
        mServerEnv.acceptAll(mServerSocket);

        mClientEnv = Environment.create();
        mSession = mClientEnv.connect(R1.class, "main", "localhost", mServerSocket.getLocalPort());
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
    public void passbackRoot() throws Exception {
        R1 root = mSession.root();
        Object result = root.c5(root);
        assertSame(result, root);
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

    @Test
    public void connectFail() throws Exception {
        try {
            mClientEnv.connect(R1.class, "xxx", "localhost", mServerSocket.getLocalPort());
            fail();
        } catch (RemoteException e) {
            assertTrue(e.getMessage().contains("Unable to find"));
        }
        try {
            mClientEnv.connect(R2.class, "main", "localhost", mServerSocket.getLocalPort());
            fail();
        } catch (RemoteException e) {
            assertTrue(e.getMessage().contains("Mismatched"));
        }
    }

    @Test
    public void batchedRemote() throws Exception {
        R1 root = mSession.root();
        R2 r2 = root.c6(9);
        assertEquals("hello 9", r2.c2());

        // Batched remote object id is an aliases.
        assertTrue(r2.toString().contains("id=-"));
        assertTrue(r2.toString().contains("remoteAddress"));

        R2 r2x = root.c6(19);
        assertNotSame(r2, r2x);
        assertEquals(9, R2Server.cParam);

        // Batched remote object id is an aliases.
        assertTrue(r2x.toString().contains("id=-"));

        // This forces the batch to finish as a side-effect.
        r2.dispose();

        // No address when disposed.
        assertFalse(r2.toString().contains("remoteAddress"));

        assertEquals("hello 19", r2x.c2());
        assertEquals(19, R2Server.cParam);
    }

    @Test
    public void batchedChain() throws Exception {
        R1 root = mSession.root();
        R2 a = root.c6(1);
        R2 b = a.next(2);
        R2 c = b.next(3);
        R2 d = c.next(4);
        R2 e = d.next(5);

        // Root id isn't an alias.
        assertFalse(root.toString().contains("id=-"));

        // Batched remote object ids are all aliases.
        assertTrue(a.toString().contains("id=-"));
        assertTrue(b.toString().contains("id=-"));
        assertTrue(c.toString().contains("id=-"));
        assertTrue(d.toString().contains("id=-"));
        assertTrue(e.toString().contains("id=-"));

        assertEquals(1, R2Server.cParam);

        assertEquals("hello 5", e.c2());
        assertEquals("hello 4", d.c2());
        assertEquals("hello 3", c.c2());
        assertEquals("hello 2", b.c2());
        assertEquals("hello 1", a.c2());

        assertEquals(5, R2Server.cParam);

        e.dispose();

        try {
            e.c2();
            fail();
        } catch (ClosedException ex) {
            assertTrue(ex.getMessage().contains("disposed"));
        }
    }

    @Test
    public void aliasDispose() throws Exception {
        // Test that disposing a batched remote object doesn't dispose the canonical one.

        R1 root = mSession.root();
        R1 self = root.self();
        assertNotSame(root, self);

        String str = root.selfString();
        assertEquals(str, self.selfString());

        self.dispose();

        try {
            self.selfString();
            fail();
        } catch (ClosedException ex) {
            assertTrue(ex.getMessage().contains("disposed"));
        }

        assertEquals(str, root.selfString());
    }

    @Test
    public void bogusExport() throws Exception {
        try {
            mClientEnv.export(mSession.root(), new R1Server());
            fail();
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Unsupported object"));
        }

        try {
            mClientEnv.export(new R1Server(), new R1Server());
            fail();
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Unsupported object"));
        }
    }

    @Test
    public void many() throws Exception {
        R1 root = mSession.root();

        R2[] many = new R2[1000];
        for (int i=0; i<many.length; i++) {
            many[i] = root.c1(i);
        }

        for (int i=0; i<many.length; i++) {
            assertEquals("hello " + i, many[i].c2());
        }
    }

    public static interface R1 extends Remote {
        R2 c1(int param) throws RemoteException;

        void c2(R3 callback) throws RemoteException;

        Object[] c3(R2 r2) throws RemoteException;

        String c4() throws RemoteException;

        Object c5(Object obj) throws RemoteException;

        @Batched
        R2 c6(int param) throws RemoteException;

        @Batched
        R1 self() throws RemoteException;

        String selfString() throws RemoteException;

        @Disposer
        void dispose() throws RemoteException;
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

        @Override
        public Object c5(Object obj) {
            assertSame(this, obj);
            return this;
        }

        @Override
        public R2 c6(int param) {
            return new R2Server(param);
        }

        @Override
        public R1 self() {
            return this;
        }

        @Override
        public String selfString() {
            return toString();
        }

        @Override
        public void dispose() {
        }
    }

    public static interface R2 extends Remote {
        String c2() throws RemoteException;

        @Batched
        R2 next(int param) throws RemoteException;

        @Disposer
        @RemoteFailure(declared=false)
        void dispose();
    }

    private static class R2Server implements R2 {
        static volatile int cParam;

        private final int mParam;

        R2Server(int param) {
            mParam = param;
            cParam = param;
        }

        @Override
        public String c2() {
            return "hello " + mParam;
        }

        @Override
        public R2 next(int param) {
            return new R2Server(param);
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
