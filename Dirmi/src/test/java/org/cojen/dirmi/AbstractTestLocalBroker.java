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

import java.io.IOException;

import java.util.concurrent.Executor;

import org.junit.*;

import org.cojen.dirmi.io.Broker;
import org.cojen.dirmi.io.PipedBroker;
import org.cojen.dirmi.io.StreamChannel;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@Ignore
public abstract class AbstractTestLocalBroker {
    protected static Environment env;

    @BeforeClass
    public static void createEnv() {
        env = new Environment();
    }

    @AfterClass
    public static void closeEnv() throws Exception {
        if (env != null) {
            env.close();
            env = null;
        }
    }

    static void sleep(long millis) {
        long start = System.currentTimeMillis();
        do {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
            }
            millis -= System.currentTimeMillis() - start;
        } while (millis > 0);
    }

    protected Broker<StreamChannel> localBroker;
    protected Broker<StreamChannel> remoteBroker;

    protected Session localSession;
    protected Session remoteSession;

    @Before
    public void setUp() throws Exception {
        Broker<StreamChannel>[] brokers = createBrokers(env.executor());
        localBroker = brokers[0];
        remoteBroker = brokers[1];

        class RemoteCreate implements Runnable {
            private IOException exception;
            private Session session;

            public synchronized void run() {
                try {
                    session = env.createSession(remoteBroker, createRemoteServer());
                } catch (IOException e) {
                    exception = e;
                }
                notifyAll();
            }

            public synchronized Session waitForSession() throws Exception {
                while (exception == null && session == null) {
                    wait();
                }
                if (exception != null) {
                    throw exception;
                }
                return session;
            }
        }

        RemoteCreate rc = new RemoteCreate();
        env.executor().execute(rc);

        localSession = env.createSession(localBroker, createLocalServer());
        remoteSession = rc.waitForSession();
    }

    protected Broker<StreamChannel>[] createBrokers(Executor executor) throws IOException {
        PipedBroker localBroker = new PipedBroker(executor);
        PipedBroker remoteBroker = new PipedBroker(executor, localBroker);
        return new Broker[] {localBroker, remoteBroker};
    }

    @After
    public void tearDown() throws Exception {
        if (env != null) {
            env.executor().execute(new Runnable() {
                public void run() {
                    if (remoteSession != null) {
                        try {
                            remoteSession.close();
                        } catch (IOException e) {
                        }
                        remoteSession = null;
                    }
                }
            });
        }

        if (localSession != null) {
            localSession.close();
            localSession = null;
        }
    }

    protected abstract Object createLocalServer();

    protected abstract Object createRemoteServer();
}
