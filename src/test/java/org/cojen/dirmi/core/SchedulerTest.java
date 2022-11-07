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

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SchedulerTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SchedulerTest.class.getName());
    }

    @Before
    public void setup() {
        mEngine = new Engine();
    }

    @After
    public void teardown() {
        mEngine.close();
    }

    private Engine mEngine;

    @Test
    public void basic() throws Exception {
        try {
            doBasic();
        } catch (AssertionError e) {
            // Try again.
            doBasic();
        }
    }

    private void doBasic() throws Exception {
        var task = new Task();
        long start = System.nanoTime();
        assertTrue(mEngine.scheduleMillis(task, 100));
        task.runCheck();
        long delayMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("" + delayMillis, delayMillis >= 100 && delayMillis <= 1000);

        task = new Task();
        assertTrue(mEngine.scheduleMillis(task, 100));
        mEngine.close();
        try {
            task.runCheck();
        } catch (AssertionError e) {
            // Expected.
        }

        assertFalse(mEngine.scheduleNanos(new Task(), 1));
    }

    @Test
    public void fuzz() throws Exception {
        try {
            doFuzz();
        } catch (AssertionError e) {
            // Try again.
            doFuzz();
        }
    }

    private void doFuzz() throws Exception {
        var tasks = new Task[1000];
        for (int i=0; i<tasks.length; i++) {
            tasks[i] = new Task();
        }

        var rnd = new Random(309458);

        for (int i=0; i<tasks.length; i++) {
            long delay = rnd.nextInt(1000);
            tasks[i].expectedTime = System.nanoTime() + delay * 1_000_000L;
            mEngine.scheduleMillis(tasks[i], delay);
            TestUtils.sleep(rnd.nextInt(10));
        }

        for (Task task : tasks) {
            task.runCheck();
            long deviationMillis = (task.actualTime - task.expectedTime) / 1_000_000L;
            assertTrue("" + deviationMillis, deviationMillis >= 0 && deviationMillis <= 1000); 
        }
    }

    static class Task extends Scheduled {
        long expectedTime;
        volatile long actualTime;
        volatile boolean ran;

        @Override
        public void run() {
            actualTime = System.nanoTime();
            ran = true;
        }

        void runCheck() {
            for (int i=0; i<2000; i++) {
                if (ran) {
                    return;
                }
                TestUtils.sleep(1);
            }
            fail();
        }
    }
}
