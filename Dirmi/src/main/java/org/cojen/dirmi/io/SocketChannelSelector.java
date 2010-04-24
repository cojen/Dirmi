/*
 *  Copyright 2010 Brian S O'Neill
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

import java.nio.channels.SocketChannel;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface SocketChannelSelector extends Closeable {
    /**
     * Register a listener which is asynchronously notified when channel can be
     * read from. Listener is called at most once per registration.
     */
    void inputNotify(SocketChannel channel, Channel.Listener listener);

    /**
     * Register a listener which is asynchronously notified when channel can be
     * written to. Listener is called at most once per registration.
     */
    void outputNotify(SocketChannel channel, Channel.Listener listener);

    IOExecutor executor();
}
