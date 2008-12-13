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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.cojen.util.WeakIdentityMap;

/**
 * Paired with {@link StreamBrokerAcceptor} to adapt a connector into a full
 * broker.
 *
 * @author Brian S O'Neill
 */
public class StreamConnectorBroker implements StreamBroker {
    static final int DEFAULT_PING_CHECK_MILLIS = 10000;

    static final byte OP_OPEN = 1;
    static final byte OP_OPENED = 2;
    static final byte OP_CONNECT = 3;
    static final byte OP_CONNECTED = 4;
    static final byte OP_PING = 5;
    static final byte OP_PONG = 6;

    final StreamConnector mConnector;
    final MessageChannel mControlChannel;
    volatile int mId;

    final ScheduledExecutorService mExecutor;

    // FIXME: Use of weak references means that channels could leak, even if
    // remote session is closed.
    final WeakIdentityMap<StreamChannel, Object> mChannels;

    final LinkedBlockingQueue<StreamListener> mListenerQueue;

    final ScheduledFuture<?> mPingCheckTask;

    volatile boolean mPinged;

    private final ReadWriteLock mCloseLock;
    private boolean mClosed;
    private volatile String mClosedReason;

    public StreamConnectorBroker(ScheduledExecutorService executor,
                                 final MessageChannel controlChannel, StreamConnector connector)
        throws IOException
    {
        mControlChannel = controlChannel;
        mConnector = connector;
        mCloseLock = new ReentrantReadWriteLock(true);

        mExecutor = executor;

        mChannels = new WeakIdentityMap<StreamChannel, Object>();

        controlChannel.send(ByteBuffer.wrap(new byte[] {OP_OPEN}));

        mListenerQueue = new LinkedBlockingQueue<StreamListener>();

        try {
            // Start ping check task.
            long checkRate = DEFAULT_PING_CHECK_MILLIS;
            PingCheckTask checkTask = new PingCheckTask(this);
            mPingCheckTask = executor.scheduleAtFixedRate
                (checkTask, checkRate, checkRate, TimeUnit.MILLISECONDS);
            checkTask.setFuture(mPingCheckTask);
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
                        out.write(OP_CONNECTED);
                        out.writeInt(mId);
                        out.flush();

                        register(channel);

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
                            unregisterAndClose(channel);
                        }
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

    public void accept(final StreamListener listener) {
        try {
            Lock lock = closeLock();
            try {
                mListenerQueue.add(listener);
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

    public StreamChannel connect() throws IOException {
        // Quick check to see if closed.
        closeLock().unlock();

        StreamChannel channel = mConnector.connect();
        DataOutputStream out = new DataOutputStream(channel.getOutputStream());
        out.write(OP_CONNECT);
        out.writeInt(mId);
        out.flush();

        try {
            Lock lock = closeLock();
            try {
                register(channel);
                return channel;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        }
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

            try {
                mPingCheckTask.cancel(true);
            } catch (NullPointerException e) {
                // mPingCheckTask might not have been assigned.
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
        return "StreamConnectorBroker {channel=" + mControlChannel + '}';
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

    StreamListener pollListener() {
        try {
            return mListenerQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
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

    private static class PingCheckTask extends AbstractPingTask<StreamConnectorBroker> {
        PingCheckTask(StreamConnectorBroker broker) {
            super(broker);
        }

        public void run() {
            StreamConnectorBroker broker = broker();
            if (broker != null) {
                broker.doPingCheck();
            }
        }
    }
}
