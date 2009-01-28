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
public class SocketStreamChannelConnector implements Connector<StreamChannel> {
    private final ScheduledExecutorService mExecutor;
    private final StreamChannelPool mPool;
    private final SocketAddress mRemoteAddress;
    private final SocketAddress mLocalAddress;

    public SocketStreamChannelConnector(ScheduledExecutorService executor,
                                        SocketAddress remoteAddress)
    {
        this(executor, remoteAddress, null);
    }

    public SocketStreamChannelConnector(ScheduledExecutorService executor,
                                        SocketAddress remoteAddress, SocketAddress localAddress)
    {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("Must provide a remote address");
        }
        mExecutor = executor;
        mPool = new StreamChannelPool(executor);
        mRemoteAddress = remoteAddress;
        mLocalAddress = localAddress;
    }

    public StreamChannel connect() throws IOException {
        StreamChannel channel = mPool.dequeue();
        if (channel != null) {
            return channel;
        }

        Socket socket = createSocket();
        if (mLocalAddress != null) {
            socket.bind(mLocalAddress);
        }
        socket.connect(mRemoteAddress);
        socket.setTcpNoDelay(true);

        channel = new SocketStreamChannel(socket);

        return new PacketStreamChannel(mExecutor, mPool, channel);
    }

    @Override
    public String toString() {
        return "SocketStreamChannelConnector {remoteAddress=" + mRemoteAddress +
            ", localAddress=" + mLocalAddress + '}';
    }

    public final SocketAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    public final SocketAddress getLocalAddress() {
        return mLocalAddress;
    }

    protected Socket createSocket() throws IOException {
        return new Socket();
    }
}
