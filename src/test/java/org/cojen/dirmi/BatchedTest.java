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

import java.lang.reflect.UndeclaredThrowableException;

import java.net.ServerSocket;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class BatchedTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(BatchedTest.class.getName());
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
        R1 r1 = mSession.root();
        r1.a("hello");
        r1.b("world");
        assertNull(mServer.mBuilder);
        String result = r1.c('!');
        assertEquals("helloworld!", result);
    }

    @Test
    public void basicPipe() throws Exception {
        R1 r1 = mSession.root();
        r1.a("hello");
        r1.b("world");
        assertNull(mServer.mBuilder);
        Pipe pipe = r1.d(null);
        pipe.writeObject('?');
        pipe.flush();
        String result = (String) pipe.readObject();
        pipe.recycle();
        assertEquals("helloworld?", result);
    }

    @Test
    public void unbatched() throws Exception {
        R1 r1 = mSession.root();
        r1.a("hello");
        r1.b("world");
        assertNull(r1.check());
        String result = r1.c('!');
        assertEquals("helloworld!", result);
    }

    @Test
    public void exception() throws Exception {
        R1 r1 = mSession.root();
        r1.ax("hello");
        r1.b("world");
        assertNull(mServer.mBuilder);

        try {
            r1.c('!');
            fail();
        } catch (IllegalStateException e) {
            assertEquals("ax", e.getMessage());
        }

        assertNull(mServer.mBuilder);

        r1.a("hello");
        r1.bx("world");

        try {
            r1.c('!');
            fail();
        } catch (IllegalStateException e) {
            assertEquals("bx", e.getMessage());
        }

        // Session should still work.
        r1.b("world");
        String result = r1.c('!');
        assertEquals("helloworld!", result);
    }

    @Test
    public void exceptionPipe() throws Exception {
        R1 r1 = mSession.root();
        r1.ax("hello");
        r1.b("world");
        assertNull(mServer.mBuilder);

        try {
            Pipe pipe = r1.d(null);
            pipe.writeObject('?');
            pipe.flush();
            String result = (String) pipe.readObject();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("ax", e.getMessage());
        }

        assertNull(mServer.mBuilder);

        r1.a("hello");
        r1.bx("world");

        try {
            r1.c('!');
            fail();
        } catch (IllegalStateException e) {
            assertEquals("bx", e.getMessage());
        }
    }

    @Test
    public void undeclaredException() throws Exception {
        R1 r1 = mSession.root();
        r1.e();
        try {
            r1.f();
            fail();
        } catch (UndeclaredThrowableException e) {
            assertEquals("foo", e.getMessage());
            Throwable cause = e.getCause();
            assertEquals(Exception.class, cause.getClass());
            assertEquals("foo", cause.getMessage());
        }
    }

    @Test
    public void brokenRemoteObjects() throws Exception {
        // Test handling of remote objects returned after the exception point. They'll be
        // disposed and should have the original exception as the cause.

        R1 r1 = mSession.root();
        R1 r1a = r1.g();
        r1.e();
        R1 r1b = r1.g();
        R1 r1c = r1.g();

        Throwable cause = null;

        try {
            r1.f();
            fail();
        } catch (UndeclaredThrowableException e) {
            assertEquals("foo", e.getMessage());
            cause = e.getCause();
            assertEquals(Exception.class, cause.getClass());
            assertEquals("foo", cause.getMessage());
        }

        r1a.f();

        try {
            r1b.f();
            fail();
        } catch (ClosedException e) {
            assertTrue(e.getMessage().contains("disposed"));
            assertSame(cause, e.getCause());
        }

        try {
            r1c.f();
            fail();
        } catch (ClosedException e) {
            assertTrue(e.getMessage().contains("disposed"));
            assertSame(cause, e.getCause());
        }
    }

    public static interface R1 extends Remote {
        @Batched
        public void a(Object msg) throws RemoteException;

        @Batched
        public void ax(Object msg) throws RemoteException;

        @Batched
        public void b(Object msg) throws RemoteException;

        @Batched
        public void bx(Object msg) throws RemoteException;

        public String c(Object msg) throws RemoteException;

        public Pipe d(Pipe pipe) throws IOException;

        @Batched
        public void e() throws Exception;

        public void f() throws RemoteException;

        @Batched
        public R1 g() throws RemoteException;

        @Unbatched
        public String check() throws RemoteException;
    }

    private static class R1Server implements R1 {
        private StringBuilder mBuilder;

        @Override
        public void a(Object msg) {
            append(msg);
        }

        @Override
        public void ax(Object msg) {
            throw new IllegalStateException("ax");
        }

        @Override
        public void b(Object msg) {
            append(msg);
        }

        @Override
        public void bx(Object msg) {
            throw new IllegalStateException("bx");
        }

        @Override
        public String c(Object msg) {
            append(msg);
            String result = mBuilder.toString();
            mBuilder = null;
            return result;
        }

        @Override
        public Pipe d(Pipe pipe) throws IOException {
            append(pipe.readObject());
            String result = mBuilder.toString();
            mBuilder = null;
            pipe.writeObject(result);
            pipe.flush();
            pipe.recycle();
            return null;
        }

        @Override
        public void e() throws Exception {
            throw new Exception("foo");
        }

        @Override
        public void f() {
        }

        @Override
        public R1 g() {
            return new R1Server();
        }

        @Override
        public String check() {
            return mBuilder == null ? null : mBuilder.toString();
        }

        private void append(Object msg) {
            if (mBuilder == null) {
                mBuilder = new StringBuilder();
            }
            mBuilder.append(msg);
        }
    }
}
