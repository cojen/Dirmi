/*
 *  Copyright 2024 Cojen.org
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

import java.io.Closeable;
import java.io.IOException;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class ExecutorTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ExecutorTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        try {
            Environment.create(null, false);
            fail();
        } catch (NullPointerException e) {
        }

        try {
            Environment.create(new Executor() {
                @Override
                public void execute(Runnable task) {
                }
            }, true);
            fail();
        } catch (IllegalArgumentException e) {
        }

        class Exec implements Executor, Closeable {
            boolean throwEx;
            volatile boolean closed;

            @Override
            public void execute(Runnable task) {
            }

            @Override
            public void close() throws IOException {
                closed = true;
                if (throwEx) {
                    throw new IOException();
                }
            }
        }

        var exec = new Exec();
        Environment.create(exec, true).close();
        assertTrue(exec.closed);

        exec = new Exec();
        exec.throwEx = true;
        Environment.create(exec, true).close();
        assertTrue(exec.closed);

        exec = new Exec();
        Environment.create(exec, false).close();
        assertFalse(exec.closed);

        ExecutorService es = Executors.newCachedThreadPool();
        Environment env = Environment.create(es, true);
        env.close();
        assertTrue(es.isShutdown());

        var task = new Runnable() {
            volatile boolean ran;

            @Override
            public synchronized void run() {
                ran = true;
                notify();
            }

            synchronized void await() throws InterruptedException {
                while (!ran) {
                    wait();
                }
            }
        };

        try {
            env.execute(task);
            fail();
        } catch (RejectedExecutionException e) {
        }

        es = Executors.newCachedThreadPool();
        env = Environment.create(es, false);
        env.close();
        assertFalse(es.isShutdown());

        assertFalse(task.ran);
        env.execute(task);
        task.await();

        es.shutdown();
    }
}
