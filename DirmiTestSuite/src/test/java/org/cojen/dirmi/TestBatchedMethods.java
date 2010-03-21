/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi;

import java.lang.reflect.UndeclaredThrowableException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestBatchedMethods extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestBatchedMethods.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteBatchedServer());
    }

    @Test
    public void oneTask() throws Exception {
        RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;
        assertFalse(server instanceof RemoteBatchedServer);

        Listener listener = new Listener();
        server.register(listener);

        server.startTask("one");
        assertNull(listener.dequeue(1000));

        // Force flush.
        server.syncNop();

        assertEquals("root", listener.dequeue(1000));
        assertEquals("one", listener.dequeue(1000));

        server.startTask("two");
        assertNull(listener.dequeue(1000));

        // Force flush with async immediate call mode.
        server.asyncNop();

        assertEquals("root", listener.dequeue(1000));
        assertEquals("two", listener.dequeue(1000));

        server.startTask("three");
        assertNull(listener.dequeue(1000));

        // This won't force a flush.
        server.eventualNop();

        assertNull(listener.dequeue(1000));

        // This will force a flush.
        sessionStrategy.localSession.flush();

        assertEquals("root", listener.dequeue(1000));
        assertEquals("three", listener.dequeue(1000));
    }

    @Test
    public void taskFlood() throws Exception {
        RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        Listener listener = new Listener();
        server.register(listener);

        int count = 10000;

        for (int i=0; i<count; i++) {
            server.startTask(Integer.toString(i));
        }

        // Some will have flushed, but some are still buffered.

        int received = 0;

        while (true) {
            String name = listener.dequeue(0);
            if (name == null) {
                break;
            }
            assertEquals("root", name);
            String op = listener.dequeue(1000);
            assertEquals(Integer.toString(received), op);
            received++;
        }

        assertTrue(received < count);

        // Force flush.
        server.syncNop();

        while (true) {
            String name = listener.dequeue(1000);
            if (name == null) {
                break;
            }
            assertEquals("root", name);
            String op = listener.dequeue(1000);
            assertEquals(Integer.toString(received), op);
            received++;
        }

        assertEquals(count, received);
    }

    @Test
    public void testChain() throws Exception {
        RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        Listener listener = new Listener();
        server.register(listener);

        server.startTask("one");
        assertNull(listener.dequeue(1000));

        RemoteBatched server2 = server.chain("server2");

        assertNull(listener.dequeue(1000));

        server2.startTask("two");
        assertNull(listener.dequeue(1000));

        // Asking for the name will force a flush.
        assertEquals("server2", server2.getName());

        assertEquals("root", listener.dequeue(1000));
        assertEquals("one", listener.dequeue(1000));
        assertEquals("server2", listener.dequeue(1000));
        assertEquals("two", listener.dequeue(1000));
    }

    @Test
    public void testIsolation() throws Exception {
        final RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        Listener listener = new Listener();
        server.register(listener);

        server.startTask("one");
        assertNull(listener.dequeue(1000));

        new Thread() {
            public void run() {
                try {
                    server.startTask("two");
                    server.syncNop();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }.start();

        // Confirms that task one was not flushed.
        assertEquals("root", listener.dequeue(1000));
        assertEquals("two", listener.dequeue(1000));
        assertNull(listener.dequeue(1000));

        server.syncNop();
        assertEquals("root", listener.dequeue(1000));
        assertEquals("one", listener.dequeue(1000));
    }

    @Test
    public void testUnbatched() throws Exception {
        final RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        Listener listener = new Listener();
        server.register(listener);

        server.startTask("one");
        assertNull(listener.dequeue(1000));

        server.unbatchedTask("two");

        // Confirms that task one was not flushed.
        assertEquals("root", listener.dequeue(1000));
        assertEquals("two", listener.dequeue(1000));
        assertNull(listener.dequeue(1000));

        server.syncNop();
        assertEquals("root", listener.dequeue(1000));
        assertEquals("one", listener.dequeue(1000));
    }

    @Test
    public void testCompletion() throws Exception {
        final RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        Completion<String>[] completions = new Completion[1000];

        for (int i=0; i<completions.length; i++) {
            completions[i] = server.echo("hello_" + i);
        }

        server.syncNop();

        for (int i=0; i<completions.length; i++) {
            assertEquals("hello_" + i, completions[i].get());
        }
    }

    @Test
    public void testManyExceptions() throws Exception {
        final RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        server.manyExceptions();
        try {
            server.syncNop();
            fail();
        } catch (UndeclaredThrowableException e) {
            assert(e.getCause() instanceof InterruptedException);
        }

        server.manyExceptions();
        try {
            server.syncNop2();
            fail();
        } catch (UndeclaredThrowableException e) {
            assert(e.getCause() instanceof InterruptedException);
        }

        server.manyExceptions();
        try {
            server.syncNop3();
            fail();
        } catch (InterruptedException e) {
        }
    }

    @Test
    public void testStreamReset() throws Exception {
        RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        final Object obj = new LinkedBlockingQueue();

        for (int i=0; i<3; i++) {
            Completion<Boolean> c = server.testStreamReset(obj);
            server.syncNop();
            assertFalse(c.get());
        }
    }

    @Test
    public void testSharedRef() throws Exception {
        RemoteBatched server = (RemoteBatched) sessionStrategy.remoteServer;

        final Object obj = new LinkedBlockingQueue();

        for (int i=0; i<3; i++) {
            Completion<Boolean> c = server.testSharedRef(obj, obj);
            server.syncNop();
            assertTrue(c.get());
        }
    }

    private static class Listener implements RemoteBatched.TaskListener {
        private final BlockingQueue<String> mQueue;

        Listener() {
            mQueue = new LinkedBlockingQueue<String>();
        }

        public synchronized void started(String name, String op) {
            mQueue.add(name);
            mQueue.add(op);
        }

        String dequeue(long timeoutMillis) throws InterruptedException {
            return mQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }
}
