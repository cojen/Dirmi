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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Random;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    final ScheduledExecutorService mExecutor;

    private final ReadWriteLock mCloseLock;
    private boolean mClosed;

    public StreamBrokerAcceptor(final StreamAcceptor acceptor,
                                final ScheduledExecutorService executor)
    {
        mAcceptor = acceptor;
        mRnd = new Random();
        mBrokerMap = new IntHashMap<Broker>();
        mBrokerListenerQueue = new LinkedBlockingQueue<StreamBrokerListener>();
        mExecutor = executor;
        mCloseLock = new ReentrantReadWriteLock(true);

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

                        Lock lock = closeLock();
                        try {
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
                        } finally {
                            lock.unlock();
                        }
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
    public void accept(final StreamBrokerListener listener) {
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

        // Synchronize on mControlOut to access.
        int mConnectionsInterrupted;

        volatile boolean mPinged;

        private final ReadWriteLock mCloseLock;
        private boolean mClosed;
        private volatile String mClosedReason;

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
            mCloseLock = new ReentrantReadWriteLock(true);

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

        public void accept(final StreamListener listener) {
            try {
                Lock lock = closeLock();
                try {
                    mListenerQueue.add(listener);
                } finally {
                    lock.unlock();
                }
            } catch (final IOException e) {
                mExecutor.execute(new Runnable() {
                    public void run() {
                        listener.failed(e);
                    }
                });
            }
        }

        public StreamChannel connect() throws IOException {
            // Quick check to see if closed.
            closeLock().unlock();

            DataOutputStream out = mControlOut;
            synchronized (out) {
                if (mConnectionsInterrupted > 0) {
                    // Use a connection which was interrupted earlier.
                    mConnectionsInterrupted--;
                } else {
                    out.writeShort(4); // length of message minus one
                    out.write(StreamConnectorBroker.OP_CONNECT);
                    out.writeInt(mId);
                    out.flush();
                }
            }

            StreamChannel channel;
            try {
                channel = mConnectQueue.take();
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

        public void close() throws IOException {
            close(null);
        }

        void close(String reason) throws IOException {
            Lock lock = mCloseLock.writeLock();
            lock.lock();
            try {
                if (mClosed) {
                    return;
                }

                mClosed = true;

                if (reason == null) {
                    reason = "Broker is closed";
                }
                mClosedReason = reason;

                closed(this);

                mConnectQueue.add(new ClosedStream());

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

                StreamListener listener;
                while ((listener = mListenerQueue.poll()) != null) {
                    listener.failed(new IOException(reason));
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
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String toString() {
            return "StreamBrokerAcceptor.Broker{channel=" + mControlChannel + '}';
        }

        Lock closeLock() throws IOException {
            Lock lock = mCloseLock.readLock();
            lock.lock();
            if (mClosed) {
                lock.unlock();
                throw new IOException(mClosedReason);
            }
            return lock;
        }

        void connected(StreamChannel channel) {
            try {
                Lock lock = closeLock();
                try {
                    register(channel);
                    mConnectQueue.add(channel);
                } finally {
                    lock.unlock();
                }
            } catch (IOException e) {
                try {
                    channel.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            }
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

            unregisterAndClose(channel);
        }

        private void register(StreamChannel channel) {
            synchronized (mChannels) {
                mChannels.put(channel, "");
            }
        }

        private void unregisterAndClose(StreamChannel channel) {
            synchronized (mChannels) {
                mChannels.remove(channel);
            }

            try {
                channel.close();
            } catch (IOException e) {
                // Ignore.
            }
        }

        void doPingCheck() {
            if (!mPinged) {
                try {
                    close("Broker is closed: Ping failure");
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

        public void disconnect() {
        }
    }
}
