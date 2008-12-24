/*
 *  Copyright 2008 Brian S O'Neill
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

package dirmi.core;

import java.io.Closeable;
import java.io.IOException;

import java.util.concurrent.ScheduledExecutorService;

import dirmi.Session;
import dirmi.SessionAcceptor;

import dirmi.io.StreamBroker;
import dirmi.io.StreamBrokerAcceptor;
import dirmi.io.StreamBrokerListener;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionServer implements Closeable {
    private final StreamBrokerAcceptor mAcceptor;
    private final ScheduledExecutorService mExecutor;

    /**
     * @param executor shared executor for remote methods
     */
    public StandardSessionServer(ScheduledExecutorService executor,
                                 StreamBrokerAcceptor acceptor)
    {
        if (acceptor == null) {
            throw new IllegalArgumentException("Broker acceptor is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        mAcceptor = acceptor;
        mExecutor = executor;
    }

    public void accept(final SessionAcceptor acceptor) {
        mAcceptor.accept(new StreamBrokerListener() {
            public void established(StreamBroker broker) {
                mAcceptor.accept(this);

                Object server = acceptor.createServer();

                Session session;
                try {
                    session = new StandardSession(mExecutor, broker, server);
                } catch (IOException e) {
                    try {
                        broker.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
                    failed(e);
                    return;
                }

                acceptor.established(session);
            }

            public void failed(IOException e) {
                mAcceptor.accept(this);
                acceptor.failed(e);
            }
        });
    }

    public void close() throws IOException {
        mAcceptor.close();
    }
}
