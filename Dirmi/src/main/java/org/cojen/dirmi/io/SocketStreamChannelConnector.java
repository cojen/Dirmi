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

import java.io.Closeable;
import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.RemoteTimeoutException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketStreamChannelConnector implements Connector<StreamChannel>, Closeable {
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
        return connect(-1, null);
    }

    public StreamChannel connect(long timeout, TimeUnit unit) throws IOException {
        StreamChannel channel = mPool.dequeue();
        if (channel != null) {
            return channel;
        }

        if (timeout == 0) {
            throw new RemoteTimeoutException(timeout, unit);
        }

        Socket socket = createSocket();
        if (mLocalAddress != null) {
            socket.bind(mLocalAddress);
        }

        if (timeout < 0) {
            socket.connect(mRemoteAddress);
        } else {
            long millis = unit.toMillis(timeout);
            if (millis <= 0) {
                throw new RemoteTimeoutException(timeout, unit);
            } else if (millis > Integer.MAX_VALUE) {
                socket.connect(mRemoteAddress);
            } else {
                try {
                    socket.connect(mRemoteAddress, (int) millis);
                } catch (SocketTimeoutException e) {
                    throw new RemoteTimeoutException(timeout, unit);
                }
            }
        }

        socket.setTcpNoDelay(true);

        channel = new SocketStreamChannel(socket);

        return new PacketStreamChannel(mExecutor, mPool, channel);
    }

    public void close() {
        mPool.close();
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
