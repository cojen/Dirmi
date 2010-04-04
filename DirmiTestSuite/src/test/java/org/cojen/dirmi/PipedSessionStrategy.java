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

import org.cojen.dirmi.io.Channel;
import org.cojen.dirmi.io.ChannelBroker;
import org.cojen.dirmi.io.IOExecutor;
import org.cojen.dirmi.io.PipedChannelBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PipedSessionStrategy extends SessionStrategy {
    protected volatile ChannelBroker localBroker;
    protected volatile ChannelBroker remoteBroker;

    protected PipedSessionStrategy(final Environment env,
                                   final Object localServer, final Object remoteServer)
        throws Exception
    {
        super(env);

        ChannelBroker[] brokers = createBrokers(new IOExecutor(env.executor()));
        localBroker = brokers[0];
        remoteBroker = brokers[1];

        class RemoteCreate implements Runnable {
            private IOException exception;
            private Session session;

            public synchronized void run() {
                try {
                    session = env.newSession(remoteBroker);
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

        localSession = env.newSession(localBroker);
        remoteSession = rc.waitForSession();

        localSession.send(localServer);
        remoteSession.send(remoteServer);

        // Exchange servers.
        this.localServer = remoteSession.receive();
        this.remoteServer = localSession.receive();
    }

    protected ChannelBroker[] createBrokers(IOExecutor executor) throws IOException {
        return PipedChannelBroker.newPair(executor);
    }
}
