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

package org.cojen.dirmi.util;

import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestThrottledExecutor {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestThrottledExecutor.class.getName());
    }

    @Test
    public void testSerial() {
        ThrottledExecutor te = new ThrottledExecutor(1, 0);
        for (int i=0; i<100; i++) {
            Task task = new Task(0);
            assertFalse(task.mDone);
            te.execute(task);
            assertTrue(task.mDone);
        }
    }

    @Test
    public void testRejection() throws Exception {
        final ThrottledExecutor te = new ThrottledExecutor(1, 0);

        final AtomicBoolean started = new AtomicBoolean();

        new Thread() {
            public void run() {
                started.set(true);
                te.execute(new Task(10000));
            }
        }.start();

        while (!started.get()) {
            Thread.sleep(1);
        }

        try {
            te.execute(new Task(0));
            fail();
        } catch (RejectedExecutionException e) {
        }
    }

    @Test
    public void testConcurrent() throws Exception {
        testConcurrent(100);
    }

    @Test
    public void testConcurrentRejection() throws Exception {
        testConcurrent(2);
    }

    private void testConcurrent(int maxQueued) throws Exception {
        final ThrottledExecutor te = new ThrottledExecutor(2, maxQueued);

        final AtomicInteger rejections = new AtomicInteger();

        Task[] tasks = new Task[5];
        Thread[] threads = new Thread[tasks.length];

        for (int i=0; i<threads.length; i++) {
            final Task task = new Task(2500);
            tasks[i] = task;

            threads[i] = new Thread() {
                public void run() {
                    try {
                        te.execute(task);
                    } catch (RejectedExecutionException e) {
                        rejections.getAndIncrement();
                    }
                }
            };
        }

        for (int i=0; i<threads.length; i++) {
            threads[i].start();
        }

        Thread.sleep(1000);
        int active = threads.length;
        for (int i=0; i<threads.length; i++) {
            if (!threads[i].isAlive()) {
                active--;
            }
        }

        assertEquals(2, active);

        for (int i=0; i<threads.length; i++) {
            threads[i].join();
        }

        int done = 0;
        for (int i=0; i<tasks.length; i++) {
            if (tasks[i].mDone) {
                done++;
            }
        }

        if (maxQueued >= tasks.length) {
            assertEquals(done, tasks.length);
            assertEquals(0, rejections.get());
        } else {
            assertTrue(done < tasks.length);
            assertEquals(tasks.length - done, rejections.get());
        }
    }

    private static class Task implements Runnable {
        private final int mDelayMillis;
        volatile boolean mDone;

        Task(int delayMillis) {
            mDelayMillis = delayMillis;
        }

        public void run() {
            if (mDelayMillis != 0) {
                try {
                    Thread.sleep(mDelayMillis);
                } catch (InterruptedException e) {
                }
            }
            mDone = true;
        }
    }
}
