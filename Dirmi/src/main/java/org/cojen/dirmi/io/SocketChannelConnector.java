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

import java.io.InterruptedIOException;
import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.util.concurrent.TimeUnit;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.net.SocketFactory;

import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Implements a connector using TCP/IP.
 *
 * @author Brian S O'Neill
 */
abstract class SocketChannelConnector implements ChannelConnector {
    private final IOExecutor mExecutor;
    private final SocketAddress mRemoteAddress;
    private final SocketAddress mLocalAddress;
    private final SocketFactory mFactory;
    private final AccessControlContext mContext;

    /**
     * @param remoteAddress address to connect to
     */
    public SocketChannelConnector(IOExecutor executor, SocketAddress remoteAddress) {
        this(executor, remoteAddress, null);
    }

    /**
     * @param remoteAddress address to connect to
     * @param localAddress local address to bind to; pass null for any
     */
    public SocketChannelConnector(IOExecutor executor,
                                  SocketAddress remoteAddress, SocketAddress localAddress)
    {
        this(executor, remoteAddress, localAddress, SocketFactory.getDefault());
    }

    /**
     * @param remoteAddress address to connect to
     * @param localAddress local address to bind to; pass null for any
     */
    public SocketChannelConnector(IOExecutor executor,
                                  SocketAddress remoteAddress, SocketAddress localAddress,
                                  SocketFactory factory)
    {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("Must provide a remote address");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Must provide a SocketFactory");
        }
        mExecutor = executor;
        mRemoteAddress = remoteAddress;
        mLocalAddress = localAddress;
        mFactory = factory;
        mContext = AccessController.getContext();
    }

    @Override
    public Channel connect() throws IOException {
        return connect(-1, null);
    }

    @Override
    public Channel connect(final long timeout, final TimeUnit unit) throws IOException {
        if (timeout == 0) {
            throw new RemoteTimeoutException(timeout, unit);
        }

        Socket socket;
        try {
            socket = AccessController.doPrivileged(new PrivilegedExceptionAction<Socket>() {
                public Socket run() throws IOException {
                    return connectSocket(timeout, unit);
                }
            }, mContext);
        } catch (PrivilegedActionException e) {
            throw (IOException) e.getCause();
        }

        return createChannel(SocketChannel.toSimpleSocket(socket));
    }

    @Override
    public Channel connect(Timer timer) throws IOException {
        return connect(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
    public void connect(final Listener listener) {
        try {
            mExecutor.execute(new Runnable() {
                public void run() {
                    Channel channel;
                    try {
                        channel = connect();
                    } catch (IOException e) {
                        listener.failed(e);
                        return;
                    }

                    listener.connected(channel);
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    Socket connectSocket(long timeout, TimeUnit unit) throws IOException {
        Socket socket = mFactory.createSocket();
        try {
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
            return socket;
        } catch (SecurityException e) {
            disconnect(socket);
            throw e;
        } catch (IOException e) {
            disconnect(socket);
            throw e;
        }
    }

    @Override
    public String toString() {
        return "ChannelConnector {localAddress=" + mLocalAddress +
            ", remoteAddress=" + mRemoteAddress + '}';
    }

    @Override
    public final SocketAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    @Override
    public final SocketAddress getLocalAddress() {
        return mLocalAddress;
    }

    protected IOExecutor executor() {
        return mExecutor; 
    }

    abstract Channel createChannel(SimpleSocket socket) throws IOException;

    private static void disconnect(Socket socket) {
        try {
            socket.close();
        } catch (IOException e2) {
            // Ignore.
        }
    }
}
