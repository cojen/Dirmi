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

package dirmi.io;

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
public class SocketStreamAcceptor implements StreamAcceptor {
    final SocketAddress mBindpoint;
    final ServerSocket mServerSocket;
    final LinkedBlockingQueue<StreamListener> mListenerQueue;

    public SocketStreamAcceptor(final ScheduledExecutorService executor, SocketAddress bindpoint)
        throws IOException
    {
        if (executor == null || bindpoint == null) {
            throw new IllegalArgumentException();
        }
        mBindpoint = bindpoint;
        mServerSocket = new ServerSocket();
        mServerSocket.bind(bindpoint);
        mListenerQueue = new LinkedBlockingQueue<StreamListener>();

        final StreamListener recycler = new StreamListener() {
            public void established(StreamChannel channel) {
                accepted(channel);
            }

            public void failed(IOException e) {
                // Ignore.
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
                        StreamListener listener;
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
                        StreamChannel channel = new SocketChannel(socket);
                        accepted(new PacketStreamChannel(executor, recycler, channel));
                    } catch (IOException e) {
                        try {
                            socket.close();
                        } catch (IOException e2) {
                            // Ignore.
                        }
                        StreamListener listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                } while (!replaced);
            }
        });
    }
    
    public void accept(StreamListener listener) {
        mListenerQueue.add(listener);
    }

    public void close() throws IOException {
        mServerSocket.close();
    }

    @Override
    public String toString() {
        return "StreamAcceptor {bindpoint=" + mBindpoint + '}';
    }

    void accepted(StreamChannel channel) {
        StreamListener listener = pollListener();
        if (listener != null) {
            listener.established(channel);
        } else {
            // Not accepted in time, so disconnect.
            channel.disconnect();
        }
    }

    StreamListener pollListener() {
        try {
            return mListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
