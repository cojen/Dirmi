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

package org.cojen.dirmi.io;

import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketStreamConnector implements StreamConnector {
    private final ScheduledExecutorService mExecutor;
    private final StreamChannelPool mPool;
    private final SocketAddress mEndpoint;
    private final SocketAddress mBindpoint;

    public SocketStreamConnector(ScheduledExecutorService executor, SocketAddress endpoint) {
        this(executor, endpoint, null);
    }

    public SocketStreamConnector(ScheduledExecutorService executor,
                                 SocketAddress endpoint, SocketAddress bindpoint)
    {
        if (executor == null || endpoint == null) {
            throw new IllegalArgumentException();
        }
        mExecutor = executor;
        mPool = new StreamChannelPool(executor);
        mEndpoint = endpoint;
        mBindpoint = bindpoint;
    }

    public StreamChannel connect() throws IOException {
        StreamChannel channel = mPool.dequeue();
        if (channel != null) {
            return channel;
        }
        Socket socket = new Socket();
        if (mBindpoint != null) {
            socket.bind(mBindpoint);
        }
        socket.connect(mEndpoint);
        socket.setTcpNoDelay(true);
        channel = new SocketChannel(socket);
        return new PacketStreamChannel(mExecutor, mPool, channel);
    }

    @Override
    public String toString() {
        return "StreamConnector {endpoint=" + mEndpoint + ", bindpoint=" + mBindpoint + '}';
    }
}
