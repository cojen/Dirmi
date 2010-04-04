/*
 *  Copyright 2009-2010 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.io;

import java.util.List;
import java.util.Random;
import java.util.Vector;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.RejectedException;

import org.cojen.dirmi.util.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestListenerQueue {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestListenerQueue.class.getName());
    }

    private ThreadPool mThreadPool;
    private IOExecutor mExecutor;

    @Before
    public void setup() {
        mThreadPool = new ThreadPool(10, true);
        mExecutor = new IOExecutor(mThreadPool);
    }

    @After
    public void teardown() {
        mThreadPool.shutdown();
    }

    @Test
    public void enqueueFirst() throws Exception {
        ListenerQueue<Listener> queue = new ListenerQueue<Listener>(mExecutor, Listener.class);
        ListenerImpl listener = new ListenerImpl();
        queue.enqueue(listener);
        queue.dequeue().hello();
        assertEquals(1, listener.waitForHello(1));
        assertEquals(0, listener.waitForStop(0));
    }

    @Test
    public void dequeueFirst() throws Exception {
        ListenerQueue<Listener> queue = new ListenerQueue<Listener>(mExecutor, Listener.class);
        queue.dequeue().hello();
        ListenerImpl listener = new ListenerImpl();
        queue.enqueue(listener);
        assertEquals(1, listener.waitForHello(1));
        assertEquals(0, listener.waitForStop(0));
    }

    @Test
    public void chaos() throws Exception {
        final ListenerQueue<Listener> queue =
            new ListenerQueue<Listener>(mExecutor, Listener.class);

        final int count = 100000;

        final List<ListenerImpl> listeners = new Vector<ListenerImpl>();

        final AtomicInteger extraStop = new AtomicInteger();

        Thread t1 = new Thread() {
            public void run() {
                Random rnd = new Random(12412343);

                for (int i=0; i<count; i++) {
                    ListenerImpl listener = new ListenerImpl();
                    listeners.add(listener);

                    try {
                        queue.enqueue(listener);
                    } catch (RejectedException e) {
                        extraStop.getAndIncrement();
                        queue.dequeue().stop();
                    }

                    if (rnd.nextInt(100) == 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        };

        Thread t2 = new Thread() {
            public void run() {
                Random rnd = new Random();

                for (int i=0; i<count; i++) {
                    Listener listener = queue.dequeue();

                    if ((i & 1) == 0) {
                        listener.hello();
                    } else {
                        listener.stop();
                    }

                    if (rnd.nextInt(100) == 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        };

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        for (int i=0; i<1000; i++) {
            if (listeners.size() < count) {
                Thread.sleep(100);
            }
        }

        assertEquals(count, listeners.size());

        Thread.sleep(1000);

        int helloTotal = 0;
        int stopTotal = 0;

        for (int i=0; i<100; i++) {
            helloTotal = 0;
            stopTotal = 0;
            for (ListenerImpl listener : listeners) {
                helloTotal += listener.getHello();
                stopTotal += listener.getStop();
            }
            if ((helloTotal + stopTotal) == count) {
                break;
            }
            Thread.sleep(100);
        }

        assertEquals(count, helloTotal + stopTotal);
    }

    @Test
    public void enqueueFirstAndClose() throws Exception {
        ListenerQueue<Listener> queue = new ListenerQueue<Listener>(mExecutor, Listener.class);

        ListenerImpl listener1 = new ListenerImpl();
        ListenerImpl listener2 = new ListenerImpl();

        queue.enqueue(listener1);
        queue.enqueue(listener2);

        queue.dequeueForClose().stop();

        assertEquals(0, listener1.waitForHello(0));
        assertEquals(1, listener1.waitForStop(1));

        assertEquals(0, listener2.waitForHello(0));
        assertEquals(1, listener2.waitForStop(1));
    }

    @Test
    public void dequeueFirstAndClose() throws Exception {
        ListenerQueue<Listener> queue = new ListenerQueue<Listener>(mExecutor, Listener.class);

        queue.dequeueForClose().stop();

        ListenerImpl listener1 = new ListenerImpl();
        ListenerImpl listener2 = new ListenerImpl();

        queue.enqueue(listener1);
        queue.enqueue(listener2);

        assertEquals(0, listener1.waitForHello(0));
        assertEquals(1, listener1.waitForStop(1));

        assertEquals(0, listener2.waitForHello(0));
        assertEquals(1, listener2.waitForStop(1));
    }

    @Test
    public void dequeueCloseEnqueue() throws Exception {
        ListenerQueue<Listener> queue = new ListenerQueue<Listener>(mExecutor, Listener.class);

        queue.dequeueForClose().stop();

        ListenerImpl listener1 = new ListenerImpl();
        ListenerImpl listener2 = new ListenerImpl();

        queue.enqueue(listener1);
        queue.enqueue(listener2);

        assertEquals(0, listener1.waitForHello(0));
        assertEquals(1, listener1.waitForStop(1));

        assertEquals(0, listener2.waitForHello(0));
        assertEquals(1, listener2.waitForStop(1));

        queue.enqueue(listener1);
        queue.enqueue(listener2);

        assertEquals(0, listener1.waitForHello(0));
        assertEquals(2, listener1.waitForStop(2));

        assertEquals(0, listener2.waitForHello(0));
        assertEquals(2, listener2.waitForStop(2));
    }

    public static interface Listener {
        void hello();

        void stop();
    }

    private static class ListenerImpl implements Listener {
        private int mHello;
        private int mStop;

        public synchronized void hello() {
            mHello++;
            notifyAll();
        }

        public synchronized void stop() {
            mStop++;
            notifyAll();
        }

        synchronized int waitForHello(int expect) throws InterruptedException {
            long end = System.currentTimeMillis() + 1000;
            while (mHello != expect) {
                wait(1000);
                if (System.currentTimeMillis() > end) {
                    break;
                }
            }
            return mHello;
        }

        synchronized int waitForStop(int expect) throws InterruptedException {
            long end = System.currentTimeMillis() + 1000;
            while (mStop != expect) {
                wait(1000);
                if (System.currentTimeMillis() > end) {
                    break;
                }
            }
            return mStop;
        }

        synchronized int getHello() {
            return mHello;
        }

        synchronized int getStop() {
            return mStop;
        }

        public synchronized String toString() {
            return "hello=" + mHello + ", stop=" + mStop;
        }
    }
}
