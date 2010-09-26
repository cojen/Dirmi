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

import java.io.IOException;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.util.concurrent.TimeUnit;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.net.ssl.SSLException;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;
import org.cojen.dirmi.RemoteTimeoutException;
import org.cojen.dirmi.util.Timer;

/**
 * Implements an acceptor using TCP/IP.
 *
 * @author Brian S O'Neill
 */
abstract class SocketChannelAcceptor implements ChannelAcceptor {
    private static final int LISTEN_BACKLOG = 1000;

    private final IOExecutor mExecutor;
    private final SocketAddress mLocalAddress;
    private final ServerSocket mServerSocket;
    private final AccessControlContext mContext;

    private final CloseableGroup<Channel> mAccepted;
    volatile boolean mAnyAccepted;

    public SocketChannelAcceptor(IOExecutor executor, SocketAddress localAddress)
        throws IOException
    {
        this(executor, localAddress, new ServerSocket());
    }

    public SocketChannelAcceptor(IOExecutor executor,
                                 SocketAddress localAddress,
                                 ServerSocket serverSocket)
        throws IOException
    {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        if (serverSocket == null) {
            throw new IllegalArgumentException("Must provide a server socket");
        }
        mExecutor = executor;
        mServerSocket = serverSocket;
        serverSocket.setReuseAddress(true);
        serverSocket.bind(localAddress, LISTEN_BACKLOG);
        mLocalAddress = serverSocket.getLocalSocketAddress();
        mContext = AccessController.getContext();
        mAccepted = new CloseableGroup<Channel>();
    }
    
    @Override
    public Channel accept() throws IOException {
        return accept(-1, null);
    }

    @Override
    public synchronized Channel accept(long timeout, TimeUnit unit) throws IOException {
        mAccepted.checkClosed();

        if (timeout < 0) {
            mServerSocket.setSoTimeout(0);
        } else {
            long millis = unit.toMillis(timeout);
            if (millis <= 0) {
                throw new RemoteTimeoutException(timeout, unit);
            } else if (millis > Integer.MAX_VALUE) {
                mServerSocket.setSoTimeout(0);
            } else {
                mServerSocket.setSoTimeout((int) millis);
            }
        }

        Socket socket;
        try {
            socket = acceptSocket();
        } catch (SocketTimeoutException e) {
            throw new RemoteTimeoutException(timeout, unit);
        } catch (IOException e) {
            mAccepted.checkClosed();
            throw e;
        }

        socket.setTcpNoDelay(true);

        Channel channel = createChannel(SocketChannel.toSimpleSocket(socket));
        channel.register(mAccepted);
        return channel;
    }

    @Override
    public Channel accept(Timer timer) throws IOException {
        mAccepted.checkClosed();
        return accept(RemoteTimeoutException.checkRemaining(timer), timer.unit());
    }

    @Override
    public void accept(final Listener listener) {
        try {
            mExecutor.execute(new Runnable() {
                public void run() {
                    if (mAccepted.isClosed()) {
                        listener.closed(new ClosedException());
                        return;
                    }

                    Channel channel;
                    try {
                        try {
                            channel = accept();
                            mAnyAccepted = true;
                        } catch (SSLException e) {
                            if (!mAnyAccepted && e.getClass() == SSLException.class) {
                                // General SSL exception upon first accept
                                // indicates SSL subsystem is not configured
                                // correctly.
                                close();
                            }
                            throw e;
                        }
                    } catch (IOException e) {
                        if (mAccepted.isClosed()) {
                            listener.closed(e);
                        } else {
                            listener.failed(e);
                        }
                        return;
                    }

                    listener.accepted(channel);
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    @Override
    public void close() {
        mAccepted.close();
        try {
            mServerSocket.close();
        } catch (IOException e) {
            // Ignore.
        }
    }

    @Override
    public String toString() {
        return "ChannelAcceptor {localAddress=" + mLocalAddress + '}';
    }

    @Override
    public final SocketAddress getLocalAddress() {
        return mLocalAddress;
    }

    protected IOExecutor executor() {
        return mExecutor;
    }

    abstract Channel createChannel(SimpleSocket socket) throws IOException;

    private Socket acceptSocket() throws IOException {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Socket>() {
                public Socket run() throws IOException {
                    return mServerSocket.accept();
                }
            }, mContext);
        } catch (PrivilegedActionException e) {
            throw (IOException) e.getCause();
        }
    }
}

