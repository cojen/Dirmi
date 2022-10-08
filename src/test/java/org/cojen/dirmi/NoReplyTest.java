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

import java.util.function.BiConsumer;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class NoReplyTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(NoReplyTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        var env = Environment.create();

        var handler = new BiConsumer<Session<?>, Throwable>() {
            private Throwable mException;

            @Override
            public synchronized void accept(Session<?> session, Throwable ex) {
                mException = ex;
                notify();
            }

            synchronized Throwable await() throws InterruptedException {
                while (mException == null) {
                    wait();
                }
                return mException;
            }
        };

        env.uncaughtExceptionHandler(handler);

        var server = new R1Server();
        env.export("main", server);
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        r1.accept(100);
        assertEquals(100, server.await());

        r1.fail();
        Throwable ex = handler.await();
        assertTrue(ex instanceof IllegalStateException);
        assertEquals("fail", ex.getMessage());

        env.close();
    }

    @Test
    public void stuck() throws Exception {
        // Verifies that a NoReply method runs in the same thread that is processing the socket.

        var env = Environment.create();
        var server = new R1Server();
        env.export("main", server);
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        r1.stuck();

        // Should use same socket, and so this should block.
        var t = new Thread(() -> {
            try {
                r1.acceptAck(123);
            } catch (RemoteException e) {
                // Ignore.
            }
        });
        t.start();

        Thread.sleep(1000);
        assertTrue(server.mValue == 0);

        server.stop();

        while (true) {
            int value = server.mValue;
            if (value == 0) {
                Thread.yield();
            } else {
                assertEquals(123, value);
                break;
            }
        }

        env.close();
    }

    public static interface R1 extends Remote {
        @NoReply
        void accept(int v) throws RemoteException;

        void acceptAck(int v) throws RemoteException;

        @NoReply
        void fail() throws RemoteException;

        @NoReply
        void stuck() throws RemoteException;
    }

    private static class R1Server implements R1 {
        volatile int mValue;
        private boolean mStop;

        @Override
        public synchronized void accept(int v) {
            mValue = v;
            notify();
        }

        @Override
        public void acceptAck(int v) {
            accept(v);
        }

        @Override
        public void fail() {
            throw new IllegalStateException("fail");
        }

        @Override
        public synchronized void stuck() {
            try {
                while (!mStop) {
                    wait();
                }
            } catch (InterruptedException e) {
            }
        }

        synchronized int await() throws InterruptedException {
            while (mValue == 0) {
                wait();
            }
            return mValue;
        }

        synchronized void stop() {
            mStop = true;
            notify();
        }
    }
}
