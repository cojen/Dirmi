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

import java.io.EOFException;
import java.io.IOException;

import java.net.ServerSocket;

import java.util.function.BiConsumer;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemotePipeTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RemotePipeTest.class.getName());
    }

    private Environment mEnv;
    private Session<R1> mSession;

    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mEnv.export("main", new R1Server());
        var ss = new ServerSocket(0);
        mEnv.acceptAll(ss);

        mSession = mEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
    }

    @After
    public void teardown() throws Exception {
        mEnv.close();
    }

    @Test
    public void basic() throws Exception {
        R1 root = mSession.root();

        Pipe p1 = root.echo(10, null, "hello");
        p1.flush();
        assertEquals(10, p1.readInt());
        assertEquals("hello", p1.readObject());
        String pipeName = (String) p1.readObject();
        p1.recycle();

        // The pipe was recycled, and so it should be chosen again.
        Pipe p2 = root.echo(123, null, "world");
        assertEquals(p1, p2);
        p2.flush();
        assertEquals(123, p2.readInt());
        assertEquals("world", p2.readObject());
        assertEquals(pipeName, p2.readObject());
        p2.recycle();
    }

    @Test
    public void failedRecycle1() throws Exception {
        R1 root = mSession.root();

        Pipe p1 = root.failedRecycle(10, null);
        p1.flush();
        assertEquals(10, p1.readInt());
        String pipeName = (String) p1.readObject();
        p1.recycle();

        // The pipe was recycled, and so it should be chosen again. It won't work correctly
        // because the remote side recycled the pipe when it still had unflushed data.
        Pipe p2 = root.echo(123, null, "hello");
        assertEquals(p1, p2);
        p2.flush();
        try {
            assertEquals(123, p2.readInt());
            fail();
        } catch (IOException e) {
        }
    }

    @Test
    public void failedRecycle2() throws Exception {
        R1 root = mSession.root();

        Pipe p1 = root.failedRecycle(10, null);
        p1.flush();
        assertEquals(10, p1.readInt());
        String pipeName = (String) p1.readObject();
        p1.writeInt(123);
        p1.recycle();

        // The pipe was recycled incorrectly on both sides, and so it was closed.
        Pipe p2 = root.echo(123, null, "hello");
        assertNotEquals(p1, p2);
        p2.flush();
        assertEquals(123, p2.readInt());
        assertEquals("hello", p2.readObject());
        assertNotEquals(pipeName, p2.readObject());
        p2.recycle();
    }

    @Test
    public void failedRecycle3() throws Exception {
        R1 root = mSession.root();

        Pipe p1 = root.echo(10, null, "hello");
        p1.flush();
        assertEquals(10, p1.readInt());
        assertEquals("hello", p1.readObject());
        String pipeName = (String) p1.readObject();
        p1.writeInt(123);
        p1.recycle();

        // The pipe was recycled incorrectly on the client side, and so it was closed.
        Pipe p2 = root.echo(123, null, "hello");
        assertNotEquals(p1, p2);
        p2.flush();
        assertEquals(123, p2.readInt());
        assertEquals("hello", p2.readObject());
        assertNotEquals(pipeName, p2.readObject());
        p2.recycle();
    }

    @Test
    public void uncaughtException() throws Exception {
        R1 root = mSession.root();

        var handler = new BiConsumer<Session, Throwable>() {
            Session session;
            Throwable exception;

            @Override
            public synchronized void accept(Session s, Throwable e) {
                session = s;
                exception = e;
                notify();
            }

            synchronized void await() throws InterruptedException {
                while (exception == null) {
                    wait();
                }
            }
        };

        mEnv.uncaughtExceptionHandler(handler);

        Pipe p1 = root.exception(null);
        p1.flush();
        try {
            p1.readInt();
            fail();
        } catch (EOFException e) {
        }

        handler.await();

        assertEquals("foo", handler.exception.getMessage());
        assertNotNull(handler.session);
        assertNotSame(mSession, handler.session);
    }

    public static interface R1 extends Remote {
        Pipe echo(int a, Pipe pipe, String b) throws IOException;

        Pipe failedRecycle(int a, Pipe pipe) throws IOException;

        Pipe exception(Pipe pipe) throws IOException;
    }

    private static class R1Server implements R1 {
        @Override
        public Pipe echo(int a, Pipe pipe, String b) throws IOException {
            pipe.writeInt(a);
            pipe.writeObject(b);
            pipe.writeObject(pipe.toString());
            pipe.flush();
            pipe.recycle();
            return null;
        }

        @Override
        public Pipe failedRecycle(int a, Pipe pipe) throws IOException {
            pipe.writeInt(a);
            pipe.writeObject(pipe.toString());
            pipe.flush();
            pipe.writeInt(a);
            pipe.recycle();
            return null;
        }

        @Override
        public Pipe exception(Pipe pipe) {
            throw new IllegalStateException("foo");
        }
    }
}
