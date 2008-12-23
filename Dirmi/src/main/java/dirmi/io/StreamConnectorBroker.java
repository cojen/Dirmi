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

import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.nio.ByteBuffer;

import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.locks.Lock;

/**
 * Paired with {@link StreamBrokerAcceptor} to adapt a connector into a full
 * broker.
 *
 * @author Brian S O'Neill
 */
public class StreamConnectorBroker extends AbstractStreamBroker implements StreamBroker {
    final StreamConnector mConnector;
    final MessageChannel mControlChannel;
    volatile int mBrokerId;

    public StreamConnectorBroker(ScheduledExecutorService executor,
                                 final MessageChannel controlChannel, StreamConnector connector)
        throws IOException
    {
        super(executor);

        mControlChannel = controlChannel;
        mConnector = connector;

        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_OPEN}));

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
                        mBrokerId = (mMessage[1] << 24) | ((mMessage[2] & 0xff) << 16)
                            | ((mMessage[3] & 0xff) << 8) | (mMessage[4] & 0xff);
                        mReceivedId = true;
                        notifyAll();
                    }
                    break;

                case OP_CHANNEL_CONNECT:
                    int channelId = reserveChannelId();
                    StreamChannel channel = null;

                    try {
                        channel = mConnector.connect();
                        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
                        out.write(OP_CHANNEL_CONNECTED);
                        out.writeInt(mBrokerId);
                        out.writeInt(channelId);
                        out.flush();

                        accepted(channelId, channel);
                    } catch (IOException e) {
                        unregisterAndDisconnect(channelId, channel);
                        StreamListener listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                    break;

                case OP_PING:
                    mPinged = true;
                    try {
                        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_PONG}));
                    } catch (IOException e) {
                        String message = "Broker is closed: Ping failure";
                        if (e.getMessage() != null) {
                            message = message + ": " + e.getMessage();
                        }
                        try {
                            close(message);
                        } catch (IOException e2) {
                            // Ignore.
                        }
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
                    String message = "Broker is closed";
                    if (e.getMessage() != null) {
                        message = message + ": " + e.getMessage();
                    }
                    try {
                        close(message);
                    } catch (IOException e2) {
                        // Ignore.
                    }
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

    public StreamChannel connect() throws IOException {
        // Quick check to see if closed.
        closeLock().unlock();

        int channelId = reserveChannelId();
        StreamChannel channel = null;

        try {
            channel = mConnector.connect();
            DataOutputStream out = new DataOutputStream(channel.getOutputStream());
            out.write(OP_CHANNEL_CONNECTED_DIRECT);
            out.writeInt(mBrokerId);
            out.writeInt(channelId);
            out.flush();

            Lock lock = closeLock();
            try {
                register(channelId, channel);
                return channel;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            unregisterAndDisconnect(channelId, channel);
            throw e;
        }
    }

    @Override
    protected void preClose() {
    }

    @Override
    protected void closeControlChannel() throws IOException {
        mControlChannel.close();
    }

    @Override
    public String toString() {
        return "StreamConnectorBroker {channel=" + mControlChannel + '}';
    }
}
