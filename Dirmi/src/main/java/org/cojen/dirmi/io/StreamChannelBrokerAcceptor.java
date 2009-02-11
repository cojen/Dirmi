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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.util.IntHashMap;

import org.cojen.dirmi.util.Random;

/**
 * Paired with {@link StreamChannelConnectorBroker} to adapt an acceptor into a
 * broker factory.
 *
 * @author Brian S O'Neill
 */
public class StreamChannelBrokerAcceptor implements Acceptor<Broker<StreamChannel>> {
    final Acceptor<StreamChannel> mAcceptor;
    final IntHashMap<TheBroker> mBrokerMap;
    final LinkedBlockingQueue<AcceptListener<Broker<StreamChannel>>> mBrokerListenerQueue;

    final ScheduledExecutorService mExecutor;

    // Set of channels which haven't yet been assigned to a broker yet.
    final Set<StreamChannel> mAcceptedChannels;

    private final ReadWriteLock mCloseLock;
    private boolean mClosed;

    public StreamChannelBrokerAcceptor(final ScheduledExecutorService executor,
                                       final Acceptor<StreamChannel> acceptor)
    {
        mAcceptor = acceptor;
        mBrokerMap = new IntHashMap<TheBroker>();
        mBrokerListenerQueue = new LinkedBlockingQueue<AcceptListener<Broker<StreamChannel>>>();
        mExecutor = executor;
        mAcceptedChannels = Collections.synchronizedSet(new HashSet<StreamChannel>());
        mCloseLock = new ReentrantReadWriteLock(true);

        acceptor.accept(new AcceptListener<StreamChannel>() {
            public void established(StreamChannel channel) {
                acceptor.accept(this);

                int op;
                int brokerId;
                TheBroker broker;

                try {
                    Lock lock = closeLock();
                    try {
                        mAcceptedChannels.add(channel);
                    } finally {
                        lock.unlock();
                    }

                    DataInputStream in = new DataInputStream(channel.getInputStream());
                    op = in.readByte();

                    if (op == AbstractStreamBroker.OP_OPEN) {
                        synchronized (mBrokerMap) {
                            do {
                                brokerId = Random.randomInt();
                            } while (mBrokerMap.containsKey(brokerId));
                            // Store null to reserve the id and allow broker
                            // constructor to block.
                            mBrokerMap.put(brokerId, null);
                        }

                        try {
                            broker = new TheBroker(executor, channel, brokerId);
                        } catch (IOException e) {
                            synchronized (mBrokerMap) {
                                mBrokerMap.remove(brokerId);
                            }
                            throw e;
                        }

                        lock = closeLock();
                        try {
                            AcceptListener<Broker<StreamChannel>> listener = pollListener();
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
                        } finally {
                            lock.unlock();
                        }
                    }

                    brokerId = in.readInt();

                    synchronized (mBrokerMap) {
                        broker = mBrokerMap.get(brokerId);
                    }

                    if (broker == null) {
                        // Connection is bogus.
                        channel.disconnect();
                        return;
                    }

                    switch (op) {
                    case AbstractStreamBroker.OP_CHANNEL_CONNECTED: {
                        int channelId = in.readInt();
                        broker.connected(channelId, channel);
                        break;
                    }

                    case AbstractStreamBroker.OP_CHANNEL_CONNECTED_DIRECT: {
                        int channelId = in.readInt();
                        broker.accepted(channelId, channel);
                        break;
                    }

                    case AbstractStreamBroker.OP_CHANNEL_CLOSE: {
                        int channelId = in.readInt();
                        // Close is expected to recycle channel: PacketStreamChannel.
                        channel.close();
                        broker.remoteChannelClose(channelId);
                        break;
                    }

                    default:
                        // Unknown operation.
                        channel.disconnect();
                        break;
                    }
                } catch (IOException e) {
                    // Most likely caused by remote endpoint closing during
                    // connection establishment or against a pooled
                    // channel. Don't bother passing exception to listener
                    // since this is not important.

                    channel.disconnect();

                    if (e.getCause() instanceof RejectedExecutionException) {
                        // Okay, this is important. It was thrown by Broker constructor.
                        AcceptListener<Broker<StreamChannel>> listener = pollListener();
                        if (listener != null) {
                            listener.failed(e);
                        }
                    }
                } finally {
                    mAcceptedChannels.remove(channel);
                }
            }

            public void failed(IOException e) {
                AcceptListener<Broker<StreamChannel>> listener;
                if ((listener = pollListener()) != null) {
                    listener.failed(e);
                }
                while ((listener = mBrokerListenerQueue.poll()) != null) {
                    listener.failed(e);
                }
            }
        });
    }

    public Object getLocalAddress() {
        return mAcceptor.getLocalAddress();
    }

    /**
     * Returns immediately and calls established method on listener
     * asynchronously. Only one broker is accepted per invocation of this
     * method.
     */
    public void accept(final AcceptListener<Broker<StreamChannel>> listener) {
        try {
            Lock lock = closeLock();
            try {
                mBrokerListenerQueue.add(listener);
            } finally {
                lock.unlock();
            }
        } catch (final IOException e) {
            try {
                mExecutor.execute(new Runnable() {
                    public void run() {
                        listener.failed(e);
                    }
                });
            } catch (RejectedExecutionException e2) {
                listener.failed(e);
            }
        }
    }

    /**
     * Prevents new brokers from being accepted and closes all existing brokers.
     */
    public void close() throws IOException {
        Lock lock = mCloseLock.writeLock();
        lock.lock();
        try {
            if (mClosed) {
                return;
            }

            mClosed = true;

            IOException exception = null;

            try {
                mAcceptor.close();
            } catch (IOException e) {
                exception = e;
            }

            synchronized (mBrokerMap) {
                for (Broker broker : mBrokerMap.values()) {
                    if (broker == null) {
                        continue;
                    }
                    try {
                        broker.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        }
                    }
                }
                mBrokerMap.clear();
            }

            for (StreamChannel channel : mAcceptedChannels) {
                channel.disconnect();
            }
            mAcceptedChannels.clear();

            if (exception != null) {
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    Lock closeLock() throws IOException {
        Lock lock = mCloseLock.readLock();
        lock.lock();
        if (mClosed) {
            lock.unlock();
            throw new IOException("Broker acceptor is closed");
        }
        return lock;
    }

    AcceptListener<Broker<StreamChannel>> pollListener() {
        try {
            return mBrokerListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    void closed(TheBroker broker) {
        synchronized (mBrokerMap) {
            mBrokerMap.remove(broker.mBrokerId);
        }
    }

    private class TheBroker extends AbstractStreamBroker {
        final StreamChannel mControlChannel;
        final DataOutputStream mControlOut;
        final DataInputStream mControlIn;
        final int mBrokerId;

        final LinkedBlockingQueue<StreamChannel> mConnectQueue;

        final ScheduledFuture<?> mPingTask;

        // Synchronize on mControlOut to access.
        int mConnectionsInterrupted;

        /**
         * @param controlChannel accepted channel
         */
        TheBroker(ScheduledExecutorService executor, StreamChannel controlChannel, int id)
            throws IOException
        {
            super(executor, true);

            mControlChannel = controlChannel;
            DataOutputStream out = new DataOutputStream(controlChannel.getOutputStream());
            mControlOut = out;
            mControlIn = new DataInputStream(controlChannel.getInputStream());
            mBrokerId = id;

            mConnectQueue = new LinkedBlockingQueue<StreamChannel>();

            synchronized (mBrokerMap) {
                mBrokerMap.put(id, this);
            }

            synchronized (out) {
                out.write(OP_OPENED);
                out.writeInt(id);
                out.flush();
            }

            try {
                // Start ping task.
                long delay = DEFAULT_PING_CHECK_MILLIS >> 1;
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

        public Object getLocalAddress() {
            return mControlChannel.getLocalAddress();
        }

        public Object getRemoteAddress() {
            return mControlChannel.getRemoteAddress();
        }

        public StreamChannel connect() throws IOException {
            return connect(-1, null);
        }

        public StreamChannel connect(long timeout, TimeUnit unit) throws IOException {
            // Quick check to see if closed.
            closeLock().unlock();

            DataOutputStream out = mControlOut;
            synchronized (out) {
                if (mConnectionsInterrupted > 0) {
                    // Use a connection which was interrupted earlier.
                    mConnectionsInterrupted--;
                } else {
                    // The timeout cannot be applied to the channel write, but
                    // this isn't expected to be a problem. The message is
                    // small and almost certain to fit in the send buffer.
                    // Also, control channel traffic is light. If the control
                    // channel is not draining, the ping checks will likely
                    // fail before it fills up to capacity.
                    out.write(OP_CHANNEL_CONNECT);
                    out.writeInt(mBrokerId);
                    out.flush();
                }
            }

            StreamChannel channel;
            try {
                if (timeout < 0) {
                    channel = mConnectQueue.take();
                } else {
                    channel = mConnectQueue.poll(timeout, unit);
                }
            } catch (InterruptedException e) {
                synchronized (out) {
                    mConnectionsInterrupted++;
                }
                throw new InterruptedIOException();
            }

            if (channel instanceof ClosedStream) {
                mConnectQueue.add(channel);
                throw new IOException(mClosedReason);
            }

            return channel;
        }

        @Override
        void channelClosed(int channelId) throws IOException {
            if (!isClosed()) {
                DataOutputStream out = mControlOut;
                synchronized (out) {
                    out.write(OP_CHANNEL_CLOSE);
                    out.writeInt(channelId);
                    out.flush();
                }
            }
        }

        @Override
        void preClose() {
            closed(this);

            mConnectQueue.add(new ClosedStream());

            try {
                mPingTask.cancel(true);
            } catch (NullPointerException e) {
                // mPingTask might not have been assigned.
            }
        }

        @Override
        void closeControlChannel() throws IOException {
            mControlChannel.disconnect();
        }

        @Override
        public String toString() {
            return "StreamChannelBrokerAcceptor.Broker {channel=" + mControlChannel + '}';
        }

        void connected(int channelId, StreamChannel channel) {
            try {
                Lock lock = closeLock();
                try {
                    register(channelId, channel);
                    mConnectQueue.add(channel);
                } finally {
                    lock.unlock();
                }
            } catch (IOException e) {
                channel.disconnect();
            }
        }

        void doPing() {
            try {
                DataOutputStream out = mControlOut;
                synchronized (out) {
                    out.write(OP_PING);
                    out.flush();
                }

                DataInputStream in = mControlIn;
                synchronized (in) {
                    // Read pong response.
                    in.readByte();
                }

                mPinged = true;
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
        }
    }

    private static class PingTask extends AbstractPingTask<TheBroker> {
        PingTask(TheBroker broker) {
            super(broker);
        }

        public void run() {
            TheBroker broker = broker();
            if (broker != null) {
                broker.doPing();
            }
        }
    }

    private static class ClosedStream implements StreamChannel {
        public InputStream getInputStream() throws IOException {
            throw new IOException("Closed");
        }

        public OutputStream getOutputStream() throws IOException {
            throw new IOException("Closed");
        }

        public Object getLocalAddress() {
            return null;
        }

        public Object getRemoteAddress() {
            return null;
        }

        public void close() {
        }

        public boolean isOpen() {
            return false;
        }

        public void remoteClose() {
        }

        public void disconnect() {
        }

        public Closeable getCloser() {
            return null;
        }

        public void addCloseListener(CloseListener listener) {
        }
    }
}
