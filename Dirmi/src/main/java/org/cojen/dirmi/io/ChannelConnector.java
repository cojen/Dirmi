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
 * Supports direct channel connection to a remote endpoint. All channels are
 * linked to the same remote endpoint.
 *
 * @author Brian S O'Neill
 */
public interface ChannelConnector extends Closeable {
    /**
     * @return remote address of connected channels or null if unknown
     */
    Object getRemoteAddress();

    /**
     * @return local address of connected channels or null if unknown
     */
    Object getLocalAddress();

    /**
     * Returns a new channel, possibly blocking until it has been established.
     */
    Channel connect() throws IOException;

    /**
     * Returns a new channel, possibly blocking until it has been established.
     */
    Channel connect(long timeout, TimeUnit unit) throws IOException;

    /**
     * Returns a new channel, possibly blocking until it has been established.
     */
    Channel connect(Timer timer) throws IOException;

    /**
     * Register a listener which is asynchronously given a connected channel.
     * Listener is called at most once per registration.
     */
    void connect(Listener listener);

    /**
     * Prevents new channels from being connected and closes all connected
     * channels.
     */
    void close();

    /**
     * Listener for acceping channels asynchronously.
     */
    public static interface Listener {
        /**
         * Called as soon as a channel has been connected. This method is
         * always invoked in a separate thread from registration, and so it may
         * safely block.
         */
        void connected(Channel channel);

        /**
         * Called if no threads are available to invoke any listeners, but does
         * not imply that connector is closed. This method is usually invoked
         * by a registration thread.
         */
        void rejected(RejectedException cause);

        /**
         * Called when channel cannot be connected. This method may be invoked
         * by a registration thread.
         */
        void failed(IOException cause);

        /**
         * Called when channel cannot be connected because connector is
         * closed. This method may be invoked by a registration thread.
         */
        void closed(IOException cause);
    }
}
