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

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketStreamChannelAcceptor implements Acceptor<StreamChannel> {
    final SocketAddress mBindpoint;
    final ServerSocket mServerSocket;
    final LinkedBlockingQueue<Acceptor.Listener<StreamChannel>> mListenerQueue;

    public SocketStreamChannelAcceptor(ScheduledExecutorService executor,
                                       SocketAddress bindpoint)
        throws IOException
    {
        this(executor, bindpoint, new ServerSocket());
    }

    public SocketStreamChannelAcceptor(final ScheduledExecutorService executor,
                                       SocketAddress bindpoint,
                                       ServerSocket serverSocket)
        throws IOException
    {
        if (executor == null) {
            throw new IllegalArgumentException("Must provide an executor");
        }
        if (bindpoint == null) {
            throw new IllegalArgumentException("Must provide a bindpoint");
        }
        if (serverSocket == null) {
            throw new IllegalArgumentException("Must provide a server socket");
        }
        mBindpoint = bindpoint;
        mServerSocket = serverSocket;
        mServerSocket.bind(bindpoint);
        mListenerQueue = new LinkedBlockingQueue<Acceptor.Listener<StreamChannel>>();

        final Recycler<StreamChannel> recycler = new Recycler<StreamChannel>() {
            public void recycled(StreamChannel channel) {
                accepted(channel);
            }
        };

        executor.execute(new Runnable() {
            public void run() {
                boolean replaced = false;
                do {
                    Socket socket;
                    try {
                        socket = mServerSocket.accept();
                    } catch (IOException e) {
                        Acceptor.Listener<StreamChannel> listener;
                        if ((listener = pollListener()) != null) {
                            listener.failed(e);
                        }
                        while ((listener = mListenerQueue.poll()) != null) {
                            listener.failed(e);
                        }
                        return;
                    }

                    try {
                        executor.execute(this);
                        replaced = true;
                    } catch (RejectedExecutionException e) {
                        // Accept next socket in current thread.
                    }

                    try {
                        socket.setTcpNoDelay(true);
                        StreamChannel channel = new SocketStreamChannel(socket);
                        accepted(new PacketStreamChannel(executor, recycler, channel));
                    } catch (IOException e) {
                        try {
                            socket.close();
                        } catch (IOException e2) {
                            // Ignore.
                        }
                        Acceptor.Listener<StreamChannel> listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                } while (!replaced);
            }
        });
    }
    
    public void accept(Acceptor.Listener<StreamChannel> listener) {
        mListenerQueue.add(listener);
    }

    public void close() throws IOException {
        mServerSocket.close();
    }

    @Override
    public String toString() {
        return "SocketStreamChannelAcceptor {bindpoint=" + mBindpoint + '}';
    }

    public final SocketAddress getBindpoint() {
        return mBindpoint;
    }

    void accepted(StreamChannel channel) {
        Acceptor.Listener<StreamChannel> listener = pollListener();
        if (listener != null) {
            listener.established(channel);
        } else {
            // Not accepted in time, so disconnect.
            channel.disconnect();
        }
    }

    Acceptor.Listener<StreamChannel> pollListener() {
        try {
            return mListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
