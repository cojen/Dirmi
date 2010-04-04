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

import java.io.IOException;

import java.util.concurrent.Executor;

import org.junit.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@Ignore
public abstract class AbstractTestSuite {
    protected static Environment env;

    @BeforeClass
    public static void createEnv() {
        env = new Environment(1000, null, new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                synchronized (System.out) {
                    System.out.println("Exception in thread \"" + t.getName() + "\" " + e);
                    if (e instanceof java.io.InvalidClassException) {
                        // Reduce output when testing broken serializable objects.
                        return;
                    }
                    e.printStackTrace(System.out);
                }
            }
        });
    }

    @AfterClass
    public static void closeEnv() throws Exception {
        if (env != null) {
            env.close();
            env = null;
        }
    }

    public static void sleep(long millis) {
        if (millis == 0) {
            return;
        }
        long start = System.nanoTime();
        do {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
            }
            millis -= (System.nanoTime() - start) / 1000000;
        } while (millis > 0);
    }

    protected SessionStrategy sessionStrategy;

    @Before
    public void setUp() throws Exception {
        sessionStrategy = createSessionStrategy(env);
    }

    @After
    public void tearDown() throws Exception {
        if (sessionStrategy != null) {
            sessionStrategy.tearDown();
        }
    }

    protected abstract SessionStrategy createSessionStrategy(Environment env)
        throws Exception;
}
