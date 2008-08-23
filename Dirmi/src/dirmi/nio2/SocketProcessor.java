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

package dirmi.nio2;

import java.io.IOException;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.Iterator;
import java.util.Set;

import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.locks.ReentrantLock;

import java.nio.ByteBuffer;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SocketProcessor {
    final Executor mExecutor;

    final ConcurrentLinkedQueue<Registerable> mReadQueue;
    final ReentrantLock mReadLock;
    final Selector mReadSelector;

    int mReadTaskCount;

    public SocketProcessor(Executor executor) throws IOException {
        if (executor == null) {
            throw new IllegalArgumentException();
        }

        mExecutor = executor;

        mReadQueue = new ConcurrentLinkedQueue<Registerable>();
        // Use unfair lock since arbitrary worker threads do reads.
        mReadLock = new ReentrantLock(false);
        mReadSelector = Selector.open();

        mReadLock.lock();
        try {
            mReadTaskCount++;
        } finally {
            mReadLock.unlock();
        }

        executor.execute(new ReadTask());
    }

    public MessageConnector newConnector(SocketAddress endpoint) {
        return newConnector(endpoint, null);
    }

    public MessageConnector newConnector(final SocketAddress endpoint,
                                         final SocketAddress bindpoint)
    {
        if (endpoint == null) {
            throw new IllegalArgumentException();
        }

        return new MessageConnector() {
            public MessageSender connect(MessageReceiver receiver) throws IOException {
                final SocketChannel channel = SocketChannel.open();

                if (bindpoint != null) {
                    channel.socket().bind(bindpoint);
                }

                channel.configureBlocking(true);
                channel.socket().connect(endpoint);
                channel.configureBlocking(false);

                return new Sender(SocketProcessor.this, channel, receiver);
            }

            @Override
            public String toString() {
                return "MessageConnector {endpoint=" + endpoint + ", bindpoint=" + bindpoint + '}';
            }
        };
    }

    public MessageAcceptor newAcceptor(final SocketAddress bindpoint) throws IOException {
        if (bindpoint == null) {
            throw new IllegalArgumentException();
        }

        final ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(bindpoint);
        serverChannel.configureBlocking(false);

        class Accept implements Registerable, Selectable<SocketChannel> {
            private final MessageReceiver mReceiver;

            Accept(MessageReceiver receiver) {
                mReceiver = receiver;
            }

            public void register(Selector selector) throws IOException {
                serverChannel.register(selector, SelectionKey.OP_ACCEPT, this);
            }

            public void registerException(IOException e) {
                mReceiver.closed(e);
            }

            public SocketChannel selected(SelectionKey key) throws IOException {
                key.cancel();
                SocketChannel channel = serverChannel.accept();
                if (channel != null) {
                    channel.configureBlocking(false);
                }
                return channel;
            }

            public void selectedException(IOException e) {
                mReceiver.closed(e);
            }

            public void selectedExecute(SocketChannel channel) {
                try {
                    new Sender(SocketProcessor.this, channel, mReceiver);
                } catch (IOException e) {
                    mReceiver.closed(e);
                }
            }
        };

        return new MessageAcceptor() {
            public void accept(MessageReceiver receiver) {
                enqueueRead(new Accept(receiver));
            }

            @Override
            public String toString() {
                return "MessageAcceptor {bindpoint=" + bindpoint + '}';
            }
        };
    }

    void enqueueRead(Registerable registerable) {
        mReadQueue.add(registerable);
        mReadSelector.wakeup();
    }

    void executeTask(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    private static interface Registerable {
        /**
         * Called to allow a selector to be registered to a socket. Only one
         * thread at a time will call this method, and implementation should
         * not block.
         */
        void register(Selector selector) throws IOException;

        /**
         * Called to pass exception which was thrown by register method. This
         * method may block.
         */
        void registerException(IOException e);
    }

    private static interface Selectable<S> {
        /**
         * Called when a non-blocking operation is ready. Only one thread at a
         * time will call this method, and implementation should not block.
         *
         * @return non-null state object if selectedExecute method should be called
         */
        S selected(SelectionKey key) throws IOException;

        /**
         * Called to pass exception which was thrown by selected method. This
         * method may block.
         */
        void selectedException(IOException e);

        /**
         * Perform task, possibly blocking. Is guaranteed to be called by same
         * thread that called selected.
         *
         * @param state non-null object which was returned from selected method
         */
        void selectedExecute(S state);
    }

    private class ReadTask implements Runnable {
        ReadTask() {
        }

        public void run() {
            final ConcurrentLinkedQueue<Registerable> queue = mReadQueue;
            final ReentrantLock lock = mReadLock;
            final Selector selector = mReadSelector;

            lock.lock();
            boolean hasLock = true;
            try {
                while (true) {
                    try {
                        selector.select();
                    } catch (IOException e) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                        --mReadTaskCount;
                        return;
                    }

                    Registerable r;
                    while ((r = queue.poll()) != null) {
                        try {
                            r.register(selector);
                        } catch (final IOException e) {
                            // Launch separate thread to allow it to block.
                            final Registerable fr = r;
                            executeTask(new Runnable() {
                                public void run() {
                                    fr.registerException(e);
                                }
                            });
                        }
                    }

                    while (true) {
                        SelectionKey key;
                        {
                            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                            if (!it.hasNext()) {
                                break;
                            }
                            key = it.next();
                            it.remove();
                        }

                        final Selectable selectable = (Selectable) key.attachment();

                        Object state;
                        try {
                            if ((state = selectable.selected(key)) == null) {
                                continue;
                            }
                        } catch (final IOException e) {
                            key.cancel();
                            // Launch separate thread to allow it to block.
                            executeTask(new Runnable() {
                                public void run() {
                                    selectable.selectedException(e);
                                }
                            });
                            continue;
                        } catch (Throwable e) {
                            Thread t = Thread.currentThread();
                            t.getUncaughtExceptionHandler().uncaughtException(t, e);
                            continue;
                        }

                        if (--mReadTaskCount == 0) {
                            // Ensure another thread is ready to select in case
                            // execute method blocks.
                            try {
                                mExecutor.execute(this);
                                mReadTaskCount++;
                            } catch (RejectedExecutionException e) {
                            }
                        }

                        // Release lock to allow replacement thread to select.
                        lock.unlock();
                        hasLock = false;
                        try {
                            selectable.selectedExecute(state);
                        } catch (Throwable e) {
                            Thread t = Thread.currentThread();
                            t.getUncaughtExceptionHandler().uncaughtException(t, e);
                        } finally {
                            if (lock.tryLock()) {
                                hasLock = true;
                                mReadTaskCount++;
                            } else {
                                // Replacement has taken control.
                                return;
                            }
                        }
                    }
                }
            } finally {
                if (hasLock) {
                    lock.unlock();
                }
            }
        }
    }

    private static class Sender implements MessageSender {
        private static final int MAX_MESSAGE_SIZE = 65536;

        private final SocketChannel mChannel;
        private final MessageReceiver mReceiver;

        private final ReentrantLock mLock;
        private final ByteBuffer[] mBuffers;
        private final Selector mSelector;

        private volatile IOException mCause;

        Sender(SocketProcessor processor, SocketChannel channel, MessageReceiver receiver)
            throws IOException
        {
            mChannel = channel;
            mReceiver = receiver;

            // Use fair lock because caller blocks when sending message.
            mLock = new ReentrantLock(true);
            mBuffers = new ByteBuffer[] {ByteBuffer.allocate(2), null};
            channel.register(mSelector = Selector.open(), SelectionKey.OP_WRITE);

            processor.enqueueRead(new Read(channel, this, receiver));

            receiver.established(this);
        }

        public void send(ByteBuffer buffer) throws IOException {
            int size = buffer.remaining();

            if (size < 1 || size > MAX_MESSAGE_SIZE) {
                throw new IllegalArgumentException("Message size: " + size);
            }

            ByteBuffer[] buffers = mBuffers;
            ByteBuffer prefix = buffers[0];
            Selector selector = mSelector;

            ReentrantLock lock = mLock;
            lock.lock();
            try {
                prefix.clear();
                prefix.put((byte) (size >> 8));
                prefix.put((byte) size);
                prefix.flip();

                buffers[1] = buffer;

                try {
                    // Account for prefix.
                    size += 2;
                    do {
                        selector.select();
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                        size -= mChannel.write(buffers);
                    } while (size > 0);
                } catch (ClosedSelectorException e) {
                    throwException(new ClosedChannelException());
                } catch (IOException e) {
                    throwException(e);
                } finally {
                    buffers[1] = null;
                }
            } finally {
                lock.unlock();
            }
        }

        public int getMaximumMessageSize() {
            return MAX_MESSAGE_SIZE;
        }

        public Object getLocalAddress() {
            return mChannel.socket().getLocalSocketAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.socket().getRemoteSocketAddress();
        }

        @Override
        public String toString() {
            return "MessageSender {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        public void close() throws IOException {
            try {
                close(null);
            } finally {
                mReceiver.closed();
            }
        }

        // Called directly by Read.
        void close(IOException cause) throws IOException {
            synchronized (mChannel.blockingLock()) {
                if (cause == null || mCause == null) {
                    mCause = cause;
                }
                if (mChannel.isOpen()) {
                    try {
                        mChannel.close();
                    } finally {
                        try {
                            mSelector.close();
                        } catch (IOException e) {
                            // Don't care.
                        }
                    }
                }
            }
        }

        private void throwException(IOException e) throws IOException {
            synchronized (mChannel.blockingLock()) {
                try {
                    close(e);
                } catch (IOException e2) {
                    // Don't care.
                }
                IOException cause = mCause;
                if (cause != null) {
                    cause.fillInStackTrace();
                    throw cause;
                }
            }
            throw e;
        }
    }

    private static class Read implements Registerable, Selectable {
        // Allocate extra two for size prefix.
        private static final int BUFFER_SIZE = 2 + 8192;

        private final SocketChannel mChannel;
        private final Sender mSender;
        private final MessageReceiver mReceiver;

        private final ByteBuffer mBuffer;

        /*
         * Size of message to receive. Is zero when no message is being
         * received, is negative when size is partially known, and is
         * positive when message size is known.
         */
        private int mSize;

        // Amount of message read so far.
        private int mAmount;

        // Receiver's state object.
        private Object mReceiverState;

        Read(SocketChannel channel, Sender sender, MessageReceiver receiver) {
            mChannel = channel;
            mSender = sender;
            mReceiver = receiver;

            mBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        }

        public void register(Selector selector) throws IOException {
            mChannel.register(selector, SelectionKey.OP_READ, this);
        }

        public void registerException(IOException e) {
            handleException(e);
        }

        public Object selected(SelectionKey key) throws IOException {
            ByteBuffer buffer = mBuffer;

            int amt = mChannel.read(buffer);

            if (amt <= 0) {
                if (amt != 0) {
                    // Throw exception so that the sender close may safely block.
                    throw new EOF();
                }
                return null;
            }

            int size = mSize;

            if (size <= 0) {
                if (size == 0) {
                    // Nothing is known about message yet.
                    if (amt == 1) {
                        // Only first byte of size known so far.
                        mSize = ~(buffer.get(0) & 0xff) << 8;
                        return null;
                    } 
                    // Size is fully known.
                    mSize = size = ((buffer.get(0) & 0xff) << 8) | (buffer.get(1) & 0xff);
                    amt -= 2;
                } else {
                    // Size is partially known, but now is fully known.
                    mSize = size = (~size) | (buffer.get(1) & 0xff);
                    amt--;
                }

                // Prepare for passing to receiver and skip size prefix.
                buffer.position(2);

                if (amt <= 0) {
                    return null;
                }
            }

            Object receiverState = mReceiver.receive(mReceiverState, size, mAmount, buffer);

            if ((mAmount += amt) < size) {
                mReceiverState = receiverState;
                // Prepare for next read.
                buffer.position(2);
                return null;
            }

            // Message fully received. Prepare for next message and return
            // receiver state object for message processing.
            buffer.clear();
            mSize = 0;
            mAmount = 0;
            mReceiverState = null;

            return receiverState;
        }

        public void selectedException(IOException e) {
            handleException(e);
        }

        public void selectedExecute(Object state) {
            mReceiver.process(state, mSender);
        }

        private void handleException(IOException e) {
            try {
                if (e instanceof EOF) {
                    e = null;
                }
                mSender.close(e);
            } catch (IOException e2) {
                // Don't care.
            } finally {
                mReceiver.closed(e);
            }
        }

        private static class EOF extends IOException {
        }
    }
}
