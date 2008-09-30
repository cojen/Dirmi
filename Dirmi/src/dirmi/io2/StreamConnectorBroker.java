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

package dirmi.io2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Paired with {@link StreamBrokerAcceptor} to adapt a connector into a full
 * broker.
 *
 * @author Brian S O'Neill
 */
public class StreamConnectorBroker implements StreamBroker {
    static final byte OP_OPEN = 1;
    static final byte OP_OPENED = 2;
    static final byte OP_CONNECT = 3;
    static final byte OP_CONNECTED = 4;
    static final byte OP_PING = 5;
    static final byte OP_PONG = 6;

    final StreamConnector mConnector;
    final MessageChannel mControlChannel;
    volatile int mId;

    final LinkedBlockingQueue<StreamListener> mListenerQueue;

    public StreamConnectorBroker(final MessageChannel controlChannel, StreamConnector connector)
        throws IOException
    {
        mControlChannel = controlChannel;
        mConnector = connector;

        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_OPEN}));

        mListenerQueue = new LinkedBlockingQueue<StreamListener>();

        class ControlReceiver implements MessageReceiver {
            private byte[] mMessage;
            private boolean mReceivedId;
            private IOException mException;

            public MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
                if (offset == 0) {
                    mMessage = new byte[totalSize];
                }
                buffer.get(mMessage, offset, buffer.remaining());
                return offset == 0 ? new ControlReceiver() : null;
            }

            public void process() {
                int command = mMessage[0];

                switch (command) {
                case OP_OPENED:
                    synchronized (this) {
                        mId = (mMessage[1] << 24) | ((mMessage[2] & 0xff) << 16)
                            | ((mMessage[3] & 0xff) << 8) | (mMessage[4] & 0xff);
                        mReceivedId = true;
                        notifyAll();
                    }
                    break;

                case OP_CONNECT:
                    StreamChannel channel = null;
                    try {
                        channel = mConnector.connect();
                        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
                        out.writeShort(4); // length of message minus one
                        out.write(OP_CONNECTED);
                        out.writeInt(mId);
                        out.flush();

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
                        if (channel != null) {
                            try {
                                channel.close();
                            } catch (IOException e2) {
                                // Ignore.
                            }
                        }
                        StreamListener listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                    break;

                case OP_PING:
                    // FIXME: cancel close task
                    try {
                        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_PONG}));
                    } catch (IOException e) {
                        try {
                            controlChannel.close();
                        } catch (IOException e2) {
                            // Ignore.
                        }
                        // FIXME
                        e.printStackTrace(System.out);
                    }
                    break;
                }
            }

            public void closed() {
                closed(null);
            }

            public void closed(IOException e) {
                synchronized (this) {
                    if (e == null) {
                        e = new IOException("Closed");
                    }
                    mReceivedId = true;
                    mException = e;
                    notifyAll();
                    // FIXME
                    e.printStackTrace(System.out);
                }
            }

            synchronized void waitForId() throws IOException {
                try {
                    while (!mReceivedId) {
                        wait();
                    }
                    if (mException != null) {
                        mException.fillInStackTrace();
                        throw mException;
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        };

        ControlReceiver first = new ControlReceiver();

        controlChannel.receive(first);

        first.waitForId();
    }

    public void accept(StreamListener listener) {
        mListenerQueue.add(listener);
    }

    public StreamChannel connect() throws IOException {
        StreamChannel channel = mConnector.connect();
        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
        out.writeShort(4); // length of message minus one
        out.write(OP_CONNECT);
        out.writeInt(mId);
        out.flush();
        return channel;
    }

    public void close() throws IOException {
        mControlChannel.close();
    }

    public boolean isOpen() {
        return mControlChannel.isOpen();
    }

    StreamListener pollListener() {
        try {
            return mListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
