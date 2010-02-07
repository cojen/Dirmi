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

package org.cojen.dirmi.io;

import java.io.Closeable;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.util.Timer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface ChannelBrokerAcceptor extends Closeable {
    /**
     * @return local address of accepted channels or null if unknown
     */
    Object getLocalAddress();

    /**
     * Blocks until a broker is accepted.
     */
    ChannelBroker accept() throws IOException;

    /**
     * Blocks until a broker is accepted.
     */
    ChannelBroker accept(long timeout, TimeUnit unit) throws IOException;

    /**
     * Blocks until a broker is accepted.
     */
    ChannelBroker accept(Timer timer) throws IOException;

    /**
     * Register a listener which is asynchronously given an accepted broker.
     * Listener is called at most once per registration.
     */
    void accept(Listener listener);

    /**
     * Prevents new brokers from being accepted and closes all accepted
     * brokers.
     */
    void close();

    /**
     * Listener for acceping channels asynchronously.
     */
    public static interface Listener {
        /**
         * Called as soon as a broker has been accepted. This method is always
         * invoked in a separate thread from registration, and so it may safely
         * block.
         */
        void accepted(ChannelBroker broker);

        /**
         * Called if no threads are available to invoke any listeners, but does
         * not imply that acceptor is closed. This method is usually invoked by
         * a registration thread.
         */
        void rejected(RejectedException cause);

        /**
         * Called when broker cannot be accepted, but does not imply that
         * acceptor is closed. This method may be invoked by a registration
         * thread.
         */
        void failed(IOException cause);

        /**
         * Called when broker cannot be accepted because acceptor is closed.
         * This method may be invoked by a registration thread.
         */
        void closed(IOException cause);
    }
}
