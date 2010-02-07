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

import java.io.Externalizable;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import java.rmi.RemoteException;

import java.util.HashSet;
import java.util.Set;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.io.CountingBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestCompletions extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestCompletions.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteCompletionsServer());
    }

    @Test
    public void failCommandFuture() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;
        assertFalse(server instanceof RemoteCompletionsServer);

        Future<String> future = server.failCommand(2000, "hello");
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException e) {
        }

        try {
            try {
                future.get(5000, TimeUnit.MILLISECONDS);
                fail();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof NullPointerException);
                assertEquals("hello", e.getCause().getMessage());
            }
        } catch (TimeoutException e) {
            fail();
        }

        try {
            server.failCommand(1000, "world").get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
            assertEquals("world", e.getCause().getMessage());
        }
    }

    @Test
    public void failCommandCompletion() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        BlockingQueue<Completion<String>> queue = new LinkedBlockingQueue<Completion<String>>();

        Completion<String> completion = server.failCommand2(1000, "hello");
        assertFalse(completion.isDone());
        assertFalse(completion.isCancelled());

        completion.register(queue);

        completion = queue.poll(2000, TimeUnit.MILLISECONDS);

        if (completion == null) {
            fail();
        }

        try {
            completion.get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
            assertEquals("hello", e.getCause().getMessage());
        }
    }

    @Test
    public void failCommandCompletionLateRegister() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        BlockingQueue<Completion<String>> queue = new LinkedBlockingQueue<Completion<String>>();
        Completion<String> completion = server.failCommand2(100, "hello");
        sleep(1000);
        completion.register(queue);
        completion = queue.poll();
        if (completion == null) {
            fail();
        }
        try {
            completion.get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
            assertEquals("hello", e.getCause().getMessage());
        }
    }

    @Test
    public void runCommandFuture() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        Future<String> future = server.runCommand(1000, "hello");
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException e) {
        }

        try {
            assertEquals("hello", future.get(5000, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            fail();
        }

        assertEquals("world", server.runCommand(0, "world").get());
        assertEquals("world!", server.runCommand(100, "world!").get());
    }

    @Test
    public void runCommandCompletion() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        Completion<String> completion = server.runCommand2(1000, "hello");
        assertFalse(completion.isDone());
        assertFalse(completion.isCancelled());

        try {
            completion.get(100, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException e) {
        }

        try {
            assertEquals("hello", completion.get(5000, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            fail();
        }

        assertEquals("world", server.runCommand2(0, "world").get());
        assertEquals("world!", server.runCommand2(100, "world!").get());

        BlockingQueue<Completion<String>> queue = new LinkedBlockingQueue<Completion<String>>();

        final int count = 100;
        for (int i=0; i<count; i++) {
            completion = server.runCommand2(1000, Integer.toString(i));
            completion.register(queue);
            sleep(10);
        }

        Set<String> received = new HashSet<String>();

        while (true) {
            completion = queue.poll(500, TimeUnit.MILLISECONDS);
            if (completion == null) {
                break;
            }
            assertTrue(received.add(completion.get()));
        }

        assertEquals(count, received.size());
    }

    @Test
    public void runCommandCompletionLateRegister() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        BlockingQueue<Completion<String>> queue = new LinkedBlockingQueue<Completion<String>>();
        Completion<String> completion = server.runCommand2(100, "hello");
        sleep(1000);
        completion.register(queue);
        completion = queue.poll();
        if (completion == null) {
            fail();
        }
        assertEquals("hello", completion.get());
    }

    @Test
    public void runCommandEventual() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        Completion<String> completion;
        BlockingQueue<Completion<String>> queue = new LinkedBlockingQueue<Completion<String>>();

        final int count = 100;
        for (int i=0; i<count; i++) {
            completion = server.runCommand3(1000, Integer.toString(i));
            completion.register(queue);
            sleep(10);
        }

        Set<String> received = new HashSet<String>();

        while (true) {
            completion = queue.poll(500, TimeUnit.MILLISECONDS);
            if (completion == null) {
                break;
            }
            assertTrue(received.add(completion.get()));
        }

        assertTrue(received.size() < count);

        sessionStrategy.localSession.flush();
        sleep(1000);

        while (true) {
            completion = queue.poll(1000, TimeUnit.MILLISECONDS);
            if (completion == null) {
                break;
            }
            assertTrue(received.add(completion.get()));
        }

        assertEquals(count, received.size());
    }

    @Test
    public void notSerializable() throws Exception {
        RemoteCompletions server = (RemoteCompletions) sessionStrategy.remoteServer;

        class Broken implements Externalizable {
            Broken(String arg) {
            }

            public void readExternal(ObjectInput in) {
            }

            public void writeExternal(ObjectOutput out) {
            }
        };

        try {
            Completion<?> completion = server.echo(new Broken(""));
            fail();
        } catch (RemoteException e) {
        }

        // Check that pooled connections are fine.
        for (int i=0; i<10; i++) {
            Completion<String> completion = server.echo("hello");
            assertEquals("hello", completion.get(100, TimeUnit.SECONDS));
        }
    }
}
