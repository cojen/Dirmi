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

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class IdleTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(IdleTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        // Verify that idle connections are closed and that new connections are created again
        // when needed.

        var env = Environment.create();
        env.export("main", new IfaceServer());
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        env.idleConnectionMillis(100);

        var connector = new Connector() {
            final AtomicInteger mTotal = new AtomicInteger();

            @Override
            public void connect(Session session) throws IOException {
                var s = new Socket();
                s.connect(session.remoteAddress());
                session.connected(s);
                mTotal.incrementAndGet();
            }
        };

        env.connector(connector);

        var session = env.connect(Iface.class, "main", "localhost", ss.getLocalPort());
        var iface = session.root();

        class Runner extends Thread {
            private volatile int mState;
            volatile Throwable mException;

            @Override
            public void run() {
                try {
                    int i = 0;
                    while (true) {
                        int state = mState;
                        if (state < 0) {
                            break;
                        }
                        if (state == 1) {
                            synchronized (this) {
                                notify();
                                wait();
                            }
                            continue;
                        }
                        assertEquals(i, iface.echo(i));
                        i++;
                    }
                } catch (Throwable e) {
                    mException = e;
                }
            }

            synchronized void pause() throws InterruptedException {
                mState = 1;
                wait();
            }

            synchronized void unpause() {
                mState = 0;
                notify();
            }

            synchronized void finish() {
                mState = -1;
                notify();
            }
        };

        var runners = new Runner[4];
        for (int i=0; i<runners.length; i++) {
            (runners[i] = new Runner()).start();
        }

        Thread.sleep(1000);

        for (Runner r : runners) {
            r.pause();
        }

        Thread.sleep(2000);

        for (Runner r : runners) {
            r.unpause();
        }

        int total = connector.mTotal.get();
        assertTrue(2 <= total && total <= 5);

        Thread.sleep(1000);

        for (Runner r : runners) {
            r.finish();
        }

        for (Runner r : runners) {
            r.join();
            assertNull(r.mException);
        }

        int total2 = connector.mTotal.get();
        assertTrue(total < total2 && total2 <= 9);

        env.close();
    }

    public static interface Iface extends Remote {
        int echo(int p) throws RemoteException;
    }

    static class IfaceServer implements Iface {
        @Override
        public int echo(int p) {
            return p;
        }
    }
}
