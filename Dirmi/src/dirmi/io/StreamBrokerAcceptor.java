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
import org.cojen.util.WeakIdentityMap;

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
                StreamBrokerListener listener;
                if ((listener = pollListener()) != null) {
                    listener.failed(e);
                }
                while ((listener = mBrokerListenerQueue.poll()) != null) {
                    listener.failed(e);
                }
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
        mAcceptor.close();
    }

    StreamBrokerListener pollListener() {
        try {
            return mBrokerListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    void closed(Broker broker) {
        synchronized (mBrokerMap) {
            mBrokerMap.remove(broker.mId);
        }
    }

    private class Broker implements StreamBroker {
        final StreamChannel mControlChannel;
        final DataOutputStream mControlOut;
        final DataInputStream mControlIn;
        final int mId;

        final ScheduledExecutorService mExecutor;

        final WeakIdentityMap<StreamChannel, Object> mChannels;

        final LinkedBlockingQueue<StreamChannel> mConnectQueue;
        final LinkedBlockingQueue<StreamListener> mListenerQueue;

        final ScheduledFuture<?> mPingCheckTask;
        final ScheduledFuture<?> mPingTask;

        volatile boolean mPinged;

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

            mChannels = new WeakIdentityMap<StreamChannel, Object>();

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
                // Start ping check task.
                long checkRate = DEFAULT_PING_CHECK_MILLIS;
                PingCheckTask checkTask = new PingCheckTask(this);
                mPingCheckTask = executor.scheduleAtFixedRate
                    (checkTask, checkRate, checkRate, TimeUnit.MILLISECONDS);
                checkTask.setFuture(mPingCheckTask);

                // Start ping task.
                long delay = checkRate >> 1;
                PingTask task = new PingTask(this);
                mPingTask = executor.scheduleWithFixedDelay
                    (task, delay, delay, TimeUnit.MILLISECONDS);
                task.setFuture(mPingTask);
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
                // broker should be closed? Keep a counter of connect requests?
                throw new InterruptedIOException();
            }
        }

        public void close() throws IOException {
            closed(this);

            try {
                mPingCheckTask.cancel(true);
            } catch (NullPointerException e) {
                // mPingCheckTask might not have been assigned.
            }
            try {
                mPingTask.cancel(true);
            } catch (NullPointerException e) {
                // mPingTask might not have been assigned.
            }

            IOException exception = null;

            try {
                mControlChannel.close();
            } catch (IOException e) {
                exception = e;
            }

            synchronized (mChannels) {
                for (StreamChannel channel : mChannels.keySet()) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }

                mChannels.clear();
            }

            if (exception != null) {
                throw exception;
            }
        }

        @Override
        public String toString() {
            return "StreamBrokerAcceptor.Broker{channel=" + mControlChannel + '}';
        }

        void connected(StreamChannel channel) {
            register(channel);
            mConnectQueue.add(channel);
        }

        void accepted(StreamChannel channel) {
            register(channel);

            try {
                StreamListener listener = mListenerQueue.poll(10, TimeUnit.SECONDS);
                if (listener != null) {
                    listener.established(channel);
                    return;
                }
            } catch (InterruptedException e) {
            }

            unregister(channel);

            try {
                channel.close();
            } catch (IOException e) {
                // Ignore.
            }
        }

        private void register(StreamChannel channel) {
            synchronized (mChannels) {
                mChannels.put(channel, "");
            }
        }

        private void unregister(StreamChannel channel) {
            synchronized (mChannels) {
                mChannels.remove(channel);
            }
        }

        void doPingCheck() {
            if (!mPinged) {
                // FIXME: give a reason
                try {
                    close();
                } catch (IOException e) {
                    // Ignore.
                }
            } else {
                mPinged = false;
            }
        }

        void doPing() {
            try {
                DataOutputStream out = mControlOut;
                synchronized (out) {
                    out.writeShort(0); // length of message minus one
                    out.write(StreamConnectorBroker.OP_PING);
                    out.flush();
                }

                DataInputStream in = mControlIn;
                synchronized (in) {
                    // Read pong response.
                    int length = in.readUnsignedShort() + 1;
                    while (length > 0) {
                        int amt = (int) in.skip(length);
                        if (amt <= 0) {
                            break;
                        }
                        length -= amt;
                    }
                }

                mPinged = true;
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

    private static class PingCheckTask extends AbstractPingTask<Broker> {
        PingCheckTask(Broker broker) {
            super(broker);
        }

        public void run() {
            Broker broker = broker();
            if (broker != null) {
                broker.doPingCheck();
            }
        }
    }

    private static class PingTask extends AbstractPingTask<Broker> {
        PingTask(Broker broker) {
            super(broker);
        }

        public void run() {
            Broker broker = broker();
            if (broker != null) {
                broker.doPing();
            }
        }
    }
}
