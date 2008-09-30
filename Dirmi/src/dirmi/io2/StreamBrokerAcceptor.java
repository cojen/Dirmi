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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.Random;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.cojen.util.IntHashMap;

/**
 * Paired with {@link StreamConnectorBroker} to adapt an acceptor into a broker
 * factory.
 *
 * @author Brian S O'Neill
 */
public class StreamBrokerAcceptor implements Closeable {
    static final int DEFAULT_PING_CHECK_MILLIS = 10000;

    final StreamAcceptor mAcceptor;
    final Random mRnd;
    final IntHashMap<Broker> mBrokerMap;
    final LinkedBlockingQueue<StreamBrokerListener> mBrokerListenerQueue;

    public StreamBrokerAcceptor(final StreamAcceptor acceptor,
                                final ScheduledExecutorService executor)
    {
        mAcceptor = acceptor;
        mRnd = new Random();
        mBrokerMap = new IntHashMap<Broker>();
        mBrokerListenerQueue = new LinkedBlockingQueue<StreamBrokerListener>();

        acceptor.accept(new StreamListener() {
            public void established(StreamChannel channel) {
                acceptor.accept(this);

                int op;
                int id;
                Broker broker;

                try {
                    DataInputStream in = new DataInputStream(channel.getInputStream());
                    int length = in.readUnsignedShort() + 1;

                    op = in.read();

                    if (op == StreamConnectorBroker.OP_OPEN) {
                        synchronized (mBrokerMap) {
                            do {
                                id = mRnd.nextInt();
                            } while (mBrokerMap.containsKey(id));
                            // Store null to reserve the id and allow broker
                            // constructor to block.
                            mBrokerMap.put(id, null);
                        }

                        try {
                            broker = new Broker(channel, id, executor);
                        } catch (IOException e) {
                            synchronized (mBrokerMap) {
                                mBrokerMap.remove(id);
                            }
                            throw e;
                        }

                        
                        StreamBrokerListener listener = pollListener();
                        if (listener != null) {
                            listener.established(broker);
                        } else {
                            // Not accepted in time, so close it.
                            try {
                                broker.close();
                            } catch (IOException e) {
                                // Ignore.
                            }
                        }

                        return;
                    }

                    id = in.readInt();
                } catch (IOException e) {
                    try {
                        channel.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
                    StreamBrokerListener listener = pollListener();
                    if (listener != null) {
                        listener.failed(e);
                    }
                    return;
                }

                synchronized (mBrokerMap) {
                    broker = mBrokerMap.get(id);
                }

                if (broker == null) {
                    // Connection is bogus.
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                    return;
                }

                switch (op) {
                case StreamConnectorBroker.OP_CONNECTED:
                    broker.connected(channel);
                    break;

                case StreamConnectorBroker.OP_CONNECT:
                    broker.accepted(channel);
                    break;

                default:
                    // Unknown operation.
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                    break;
                }
            }

            public void failed(IOException e) {
                // FIXME
                e.printStackTrace(System.out);
            }
        });
    }

    /**
     * Returns immediately and calls established method on listener
     * asynchronously. Only one broker is accepted per invocation of this
     * method.
     */
    public void accept(StreamBrokerListener listener) {
        mBrokerListenerQueue.add(listener);
    }

    public void close() throws IOException {
        // FIXME mAcceptor.close();
    }

    StreamBrokerListener pollListener() {
        try {
            return mBrokerListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    private class Broker implements StreamBroker {
        final StreamChannel mControlChannel;
        final DataOutputStream mControlOut;
        final DataInputStream mControlIn;
        final int mId;

        final ScheduledExecutorService mExecutor;

        final LinkedBlockingQueue<StreamChannel> mConnectQueue;
        final LinkedBlockingQueue<StreamListener> mListenerQueue;

        final ScheduledFuture<?> mPingTask;

        /**
         * @param controlChannel accepted channel
         */
        Broker(StreamChannel controlChannel, int id, ScheduledExecutorService executor)
            throws IOException
        {
            mControlChannel = controlChannel;
            DataOutputStream out = new DataOutputStream(controlChannel.getOutputStream());
            mControlOut = out;
            mControlIn = new DataInputStream(controlChannel.getInputStream());
            mId = id;

            mExecutor = executor;

            mConnectQueue = new LinkedBlockingQueue<StreamChannel>();
            mListenerQueue = new LinkedBlockingQueue<StreamListener>();

            synchronized (mBrokerMap) {
                mBrokerMap.put(id, this);
            }

            synchronized (out) {
                out.writeShort(4); // length of message minus one
                out.write(StreamConnectorBroker.OP_OPENED);
                out.writeInt(id);
                out.flush();
            }

            try {
                // Start ping task.
                long delay = DEFAULT_PING_CHECK_MILLIS >> 1;
                mPingTask = executor.scheduleWithFixedDelay
                    (new Runnable() {
                         public void run() {
                             doPing();
                         }
                     },
                     delay, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                try {
                    close();
                } catch (IOException e2) {
                    // Ignore.
                }
                IOException io = new IOException("Unable to start ping task");
                io.initCause(e);
                throw io;
            }
        }

        public void accept(StreamListener listener) {
            mListenerQueue.add(listener);
        }

        public StreamChannel connect() throws IOException {
            DataOutputStream out = mControlOut;
            synchronized (out) {
                out.writeShort(4); // length of message minus one
                out.write(StreamConnectorBroker.OP_CONNECT);
                out.writeInt(mId);
                out.flush();
            }
            try {
                return mConnectQueue.take();
            } catch (InterruptedException e) {
                // FIXME: The queue might get a connection later. Perhaps this
                // broker should be closed?
                throw new InterruptedIOException();
            }
        }

        public void close() throws IOException {
            try {
                mPingTask.cancel(true);
            } catch (NullPointerException e) {
                // mPingTask might not have been assigned.
            }

            mControlChannel.close();

            // FIXME: close all channels
        }

        public boolean isOpen() {
            return mControlChannel.isOpen();
        }

        void connected(StreamChannel channel) {
            mConnectQueue.add(channel);
        }

        void accepted(StreamChannel channel) {
            try {
                StreamListener listener = mListenerQueue.poll(10, TimeUnit.SECONDS);
                if (listener != null) {
                    listener.established(channel);
                    return;
                }
            } catch (InterruptedException e) {
            }

            try {
                channel.close();
            } catch (IOException e) {
                // Ignore.
            }
        }

        void doPing() {
            try {
                long delay = DEFAULT_PING_CHECK_MILLIS;
                ScheduledFuture<?> closer = mExecutor.schedule(new Runnable() {
                    public void run() {
                        // FIXME: give a reason
                        try {
                            close();
                        } catch (IOException e) {
                            // Ignore.
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS);

                DataOutputStream out = mControlOut;
                synchronized (out) {
                    out.writeShort(0); // length of message minus one
                    out.write(StreamConnectorBroker.OP_PING);
                    out.flush();
                }

                DataInputStream in = mControlIn;
                synchronized (in) {
                    int length = in.readUnsignedShort() + 1;
                    while (length > 0) {
                        int amt = (int) in.skip(length);
                        if (amt <= 0) {
                            break;
                        }
                        length -= amt;
                    }
                }

                closer.cancel(false);
            } catch (IOException e) {
                // FIXME
                try {
                    close();
                } catch (IOException e2) {
                    // Ignore.
                }
                e.printStackTrace(System.out);
            }
        }
    }
}
