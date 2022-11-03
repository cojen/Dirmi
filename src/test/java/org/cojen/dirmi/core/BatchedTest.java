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

package org.cojen.dirmi.core;

import java.io.IOException;

import java.lang.reflect.UndeclaredThrowableException;

import java.net.ServerSocket;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.NoReply;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;
import org.cojen.dirmi.Unbatched;

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

        var cs = (CoreSession) mSession;
        assertEquals(2, cs.mStubs.size()); // only the root object and r1a

        r1.dispose();
        r1a.dispose();

        assertEquals(0, cs.mStubs.size());
    }

    @Test
    public void brokenRemoteObjects2() throws Exception {
        R1 r1 = mSession.root();
        R1 r1a = r1.h();

        Throwable cause = null;

        try {
            r1.f();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("h", e.getMessage());
            cause = e;
        }

        try {
            r1a.f();
            fail();
        } catch (ClosedException e) {
            assertTrue(e.getMessage().contains("disposed"));
            assertSame(cause, e.getCause());
        }

        var cs = (CoreSession) mSession;
        assertEquals(1, cs.mStubs.size()); // only the root object

        r1.dispose();

        assertEquals(0, cs.mStubs.size());
    }

    @Test
    public void brokenRemoteObjects3() throws Exception {
        R1 r1 = mSession.root();

        // First use of the type, and so this call is immediately flushed. The alias id is
        // generated before the call, and it must be removed because of the exception.
        try {
            R2 r2 = r1.i();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("i", e.getMessage());
        }

        var cs = (CoreSession) mSession;
        assertEquals(1, cs.mStubs.size()); // only the root object

        r1.dispose();

        assertEquals(0, cs.mStubs.size());
    }

    @Test
    public void noReply() throws Exception {
        // Verify that a no-reply method writes a batch response.

        R1 root = mSession.root();

        root.a("hello");
        assertNull(mServer.check());

        // Even though this is a no-reply method, a batch is in progress and so the server must
        // finish it and write a response for the client to read.
        root.j();

        assertEquals("hello", mServer.check());

        // Generates a pending exception.
        root.e();

        try {
            // Even though this is a no-reply method, a batch is in progress and so the server
            // must finish it and write the exception for the client to catch.
            root.j();
        } catch (Exception e) {
            assertEquals("foo", e.getMessage());
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

        @Batched
        public R1 h() throws RemoteException;

        @Batched
        public R2 i() throws RemoteException;

        @NoReply
        public void j() throws RemoteException, Exception;

        @Unbatched
        public String check() throws RemoteException;

        @Disposer
        public void dispose() throws RemoteException;
    }

    public static interface R2 extends Remote {
        public void a() throws RemoteException;
    }

    private static class R1Server implements R1 {
        private volatile StringBuilder mBuilder;

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
        public R1 h() {
            throw new IllegalStateException("h");
        }

        @Override
        public R2 i() {
            throw new IllegalStateException("i");
        }

        @Override
        public void j() {
        }

        @Override
        public String check() {
            StringBuilder b = mBuilder;
            return b == null ? null : b.toString();
        }

        @Override
        public void dispose() {
        }

        private void append(Object msg) {
            StringBuilder b = mBuilder;
            if (b == null) {
                mBuilder = b = new StringBuilder();
            }
            b.append(msg);
        }
    }
}
