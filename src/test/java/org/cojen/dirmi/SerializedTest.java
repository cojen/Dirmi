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

import java.io.InvalidClassException;

import java.net.ServerSocket;

import java.util.Vector;

import java.util.function.BiConsumer;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SerializedTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SerializedTest.class.getName());
    }

    private Environment mEnv;
    private R1Server mServer;
    private ServerSocket mServerSocket;
    private Session<R1> mSession;

    private volatile Throwable mException;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();

        mEnv.uncaughtExceptionHandler((s, ex) -> {
            mException = ex;
        });

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
        R1 root = mSession.root();

        var v = new Vector<>();
        v.add("hello");
        v.add("world");

        root.m1(v, "hello", 123);

        assertEquals(v, mServer.a);
        assertEquals("hello", mServer.b);
        assertEquals(123, mServer.c);
        assertSame(((Vector) mServer.a).get(0), mServer.b);

        assertEquals(123, root.m2("123"));
        assertEquals("123", root.m3(123).get(0));

        mServer.a = null;
        root.m4(v);
        while (mServer.a == null) {
            Thread.sleep(1);
        }
        assertEquals(v, mServer.a);

        mServer.a = null;
        mServer.b = null;
        mServer.c = 0;

        root.m5(v);
        root.m6("123");
        root.m6("456");
        root.m7(999);
        while (mServer.c == 0) {
            Thread.sleep(1);
        }
        assertEquals(v, mServer.a);
        assertEquals("456", mServer.b);
        assertEquals(999, mServer.c);
    }

    @Test
    public void clientRejected() throws Exception {
        try {
            mSession.root().r1();
            fail();
        } catch (RemoteException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof InvalidClassException);
        }
    }

    @Test
    public void serverRejected() throws Exception {
        try {
            mSession.root().r2(new Vector());
            fail();
        } catch (ClosedException e) {
        }

        assertTrue(mException instanceof InvalidClassException);
    }

    @Test
    public void remoteObjects() throws Exception {
        R1 root = mSession.root();
        R1 r1 = root.echo(root);
        assertSame(r1, root);
        assertEquals("hello", root.newR2().value());
    }

    public static interface R1 extends Remote {
        @Serialized(filter="java.base/*")
        void m1(Object a, String b, int c) throws RemoteException;

        @Serialized(filter="java.base/*")
        int m2(String a) throws RemoteException;

        @Serialized(filter="java.base/*")
        Vector<String> m3(int a) throws RemoteException;

        @NoReply
        @Serialized(filter="java.base/*")
        void m4(Object a) throws RemoteException;

        @Batched
        @Serialized(filter="java.base/*")
        void m5(Object a) throws RemoteException;

        @Batched
        @Serialized(filter="java.base/*")
        R2 m6(String b) throws RemoteException;

        void m7(int c) throws RemoteException;

        @Serialized(filter="!*")
        Object r1() throws RemoteException;

        @Serialized(filter="!*")
        void r2(Object obj) throws RemoteException;

        @Serialized(filter="org.cojen.dirmi.*")
        R1 echo(R1 obj) throws RemoteException;

        @Serialized(filter="org.cojen.dirmi.*")
        R2 newR2() throws RemoteException;
    }

    public static interface R2 extends Remote {
        String value() throws RemoteException;
    }

    private static class R1Server implements R1 {
        volatile Object a;
        volatile String b;
        volatile int c;

        @Override
        public void m1(Object a, String b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        @Override
        public int m2(String a) {
            return Integer.parseInt(a);
        }

        @Override
        public Vector<String> m3(int a) {
            var v = new Vector<String>();
            v.add(String.valueOf(a));
            return v;
        }

        @Override
        public void m4(Object a) {
            this.a = a;
        }

        @Override
        public void m5(Object a) {
            this.a = a;
        }

        @Override
        public R2 m6(String b) {
            this.b = b;
            return () -> b;
        }

        @Override
        public void m7(int c) {
            this.c = c;
        }

        @Override
        public Object r1() {
            return new Vector();
        }

        @Override
        public void r2(Object obj) {
            this.a = obj;
        }

        @Override
        public R1 echo(R1 obj) {
            return obj;
        }

        @Override
        public R2 newR2() {
            return () -> "hello";
        }
    }
}
