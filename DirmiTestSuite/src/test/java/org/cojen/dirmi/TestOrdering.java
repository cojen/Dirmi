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

package org.cojen.dirmi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestOrdering extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestOrdering.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteOrderedServer());
    }

    @Test
    public void nop() throws Exception {
        RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;
        assertFalse(ordered instanceof RemoteOrderedServer);
        ordered.nop();
    }

    @Test
    public void singleThreadSync() throws Exception {
        RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;

        for (int s=1; s<=10000; s++) {
            ordered.doIt(s);
        }

        assertTrue(ordered.checkSequence());
    }

    @Test
    public void singleThreadAsync() throws Exception {
        RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;

        for (int s=1; s<=10000; s++) {
            ordered.doItAsync(s);
        }

        assertTrue(ordered.checkSequence());
    }

    @Test
    public void singleThreadCompletion() throws Exception {
        RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;

        Completion<Integer>[] completions = new Completion[10000];

        for (int s=1; s<=completions.length; s++) {
            completions[s - 1] = ordered.doItCompletion(s);
        }

        assertTrue(ordered.checkSequence());

        for (int s=1; s<=completions.length; s++) {
            assertTrue(completions[s - 1].get() == s);
        }
    }

    @Test
    public void multipleThreads() throws Exception {
        final RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;

        final AtomicInteger sequence = new AtomicInteger();
        final AtomicReference<Exception> ex = new AtomicReference<Exception>();

        final int count = 500;

        Thread[] threads = new Thread[10];
        for (int i=0; i<threads.length; i++) {
            threads[i] = new Thread() {
                public void run() {
                    try {
                        for (int j=0; j<count; j++) {
                            synchronized (sequence) {
                                ordered.doItAsync(sequence.incrementAndGet());
                            }
                            synchronized (sequence) {
                                ordered.doIt(sequence.incrementAndGet());
                            }
                        }
                    } catch (Exception e) {
                        ex.set(e);
                    }
                }
            };
        }

        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertNull(ex.get());
        assertEquals(threads.length * count * 2, sequence.get());
        assertTrue(ordered.checkSequence());
    }

    @Test
    public void multipleThreadsPipe() throws Exception {
        final RemoteOrdered ordered = (RemoteOrdered) sessionStrategy.remoteServer;

        final AtomicInteger sequence = new AtomicInteger();
        final AtomicReference<Exception> ex = new AtomicReference<Exception>();

        final Pipe[] pipes = new Pipe[10];
        final Thread[] threads = new Thread[pipes.length];

        for (int s=1; s<=pipes.length; s++) {
            final int fs = s;
            threads[s - 1] = new Thread() {
                public void run() {
                    try {
                        Pipe pipe;
                        synchronized (sequence) {
                            pipe = ordered.doItPipe(null);
                            pipes[fs - 1] = pipe;
                            pipe.writeInt(sequence.incrementAndGet());
                        }
                        pipe.flush();
                    } catch (Exception e) {
                        ex.set(e);
                    }
                }
            };
        }

        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertTrue(ordered.checkSequence());

        synchronized (sequence) {
            for (int s=1; s<=pipes.length; s++) {
                Pipe pipe = pipes[s - 1];
                pipe.close();
            }
        }

        assertNull(ex.get());
    }
}
