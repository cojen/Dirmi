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

import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.nio.ByteBuffer;

import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.locks.Lock;

import org.cojen.dirmi.util.ExceptionUtils;

/**
 * Paired with {@link StreamChannelBrokerAcceptor} to adapt a connector into a
 * full broker.
 *
 * @author Brian S O'Neill
 */
public class StreamChannelConnectorBroker extends AbstractStreamBroker
    implements Broker<StreamChannel>
{
    final Connector<StreamChannel> mConnector;
    final MessageChannel mControlChannel;

    private boolean mOpened;
    private int mBrokerId;
    private IOException mOpenException;

    public StreamChannelConnectorBroker(ScheduledExecutorService executor,
                                        final MessageChannel controlChannel,
                                        Connector<StreamChannel> connector)
        throws IOException
    {
        super(executor, true);

        mControlChannel = controlChannel;
        mConnector = connector;

        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_OPEN}));

        class ControlReceiver implements MessageReceiver {
            private byte[] mMessage;

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
                case OP_OPENED: {
                    int brokerId = (mMessage[1] << 24) | ((mMessage[2] & 0xff) << 16)
                        | ((mMessage[3] & 0xff) << 8) | (mMessage[4] & 0xff);
                    setBrokerId(brokerId);
                    break;
                }

                case OP_CHANNEL_CONNECT: {
                    int channelId = reserveChannelId();
                    StreamChannel channel = null;

                    try {
                        channel = mConnector.connect();
                        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
                        out.write(OP_CHANNEL_CONNECTED);
                        out.writeInt(brokerId());
                        out.writeInt(channelId);
                        out.flush();

                        accepted(channelId, channel);
                    } catch (IOException e) {
                        unregisterAndDisconnect(channelId, channel);
                        Acceptor.Listener<StreamChannel> listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                    break;
                }

                case OP_PING: {
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
            }

            public void closed() {
                closed(null);
            }

            public void closed(IOException e) {
                if (e == null) {
                    e = new IOException("Closed");
                }
                openFailed(e);
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
        };

        ControlReceiver first = new ControlReceiver();

        controlChannel.receive(first);

        // Block until broker id has been received.
        try {
            brokerId();
        } catch (IOException e) {
            ExceptionUtils.addLocalTrace(e);
            throw e;
        }
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
            out.writeInt(brokerId());
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
    void preClose() {
    }

    @Override
    void closeControlChannel() throws IOException {
        mControlChannel.close();
    }

    @Override
    public String toString() {
        return "StreamChannelConnectorBroker {channel=" + mControlChannel + '}';
    }

    synchronized void setBrokerId(int id) {
        if (!mOpened) {
            mBrokerId = id;
            mOpened = true;
            notifyAll();
        }
    }

    synchronized void openFailed(IOException e) {
        if (!mOpened) {
            mOpenException = e;
            mOpened = true;
            notifyAll();
        }
    }

    synchronized int brokerId() throws IOException {
        try {
            while (!mOpened) {
                wait();
            }
            if (mOpenException != null) {
                throw mOpenException;
            }
            return mBrokerId;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }
}
