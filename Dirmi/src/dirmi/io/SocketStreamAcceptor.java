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

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
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

    public SocketStreamAcceptor(SocketAddress bindpoint, final Executor executor)
        throws IOException
    {
        if (bindpoint == null) {
            throw new IllegalArgumentException();
        }
        mBindpoint = bindpoint;
        mServerSocket = new ServerSocket();
        mServerSocket.bind(bindpoint);
        mListenerQueue = new LinkedBlockingQueue<StreamListener>();

        executor.execute(new Runnable() {
            public void run() {
                Socket socket;
                try {
                    socket = mServerSocket.accept();
                    executor.execute(this);
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
                    socket.setTcpNoDelay(true);
                    SocketChannel channel = new SocketChannel(socket);

                    StreamListener listener = pollListener();
                    if (listener != null) {
                        listener.established(channel);
                    } else {
                        // Not accepted in time, so close it.
                        try {
                            channel.close();
                        } catch (IOException e) {
                            // Ignore.
                        }
                    }
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
        return "StreamAcceptor{bindpoint=" + mBindpoint + '}';
    }

    StreamListener pollListener() {
        try {
            return mListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
