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

import java.util.concurrent.ConcurrentLinkedQueue;
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
public class SocketMessageProcessor {
    final Executor mExecutor;

    final ConcurrentLinkedQueue<Registerable> mReadQueue;
    final ReentrantLock mReadLock;
    final Selector mReadSelector;

    int mReadTaskCount;

    public SocketMessageProcessor(Executor executor) throws IOException {
        if (executor == null) {
            throw new IllegalArgumentException();
        }

        mExecutor = executor;

        mReadQueue = new ConcurrentLinkedQueue<Registerable>();
        // Use unfair lock since arbitrary worker threads do reads, and so
        // waiting on a queue is not necessary.
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
            public MessageConnection connect() throws IOException {
                final SocketChannel channel = SocketChannel.open();

                if (bindpoint != null) {
                    channel.socket().bind(bindpoint);
                }

                channel.configureBlocking(true);
                channel.socket().connect(endpoint);
                channel.configureBlocking(false);

                return new Con(channel);
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
            private final MessageListener mListener;

            Accept(MessageListener listener) {
                mListener = listener;
            }

            public void register(Selector selector) throws IOException {
                serverChannel.register(selector, SelectionKey.OP_ACCEPT, this);
            }

            public void registerException(IOException e) {
                mListener.failed(e);
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
                mListener.failed(e);
            }

            public void selectedExecute(SocketChannel channel) {
                try {
                    mListener.established(new Con(channel));
                } catch (IOException e) {
                    mListener.failed(e);
                }
            }
        };

        return new MessageAcceptor() {
            public void accept(MessageListener listener) {
                enqueueRegister(new Accept(listener));
            }

            @Override
            public String toString() {
                return "MessageAcceptor {bindpoint=" + bindpoint + '}';
            }
        };
    }

    void enqueueRegister(Registerable registerable) {
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

    void uncaughtException(Throwable e) {
        try {
            Thread t = Thread.currentThread();
            t.getUncaughtExceptionHandler().uncaughtException(t, e);
        } catch (Throwable e2) {
            // I give up.
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
                        uncaughtException(e);
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
                            uncaughtException(e);
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
                            uncaughtException(e);
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

    private class Con implements MessageConnection {
        private static final int MAX_MESSAGE_SIZE = 65536;

        private final SocketChannel mChannel;

        private final ReentrantLock mLock;
        private final ByteBuffer[] mBuffers;
        private final Selector mSelector;

        private final Reader mReader;

        private volatile IOException mCause;

        Con(SocketChannel channel) throws IOException {
            mChannel = channel;

            // Use fair lock because caller blocks when sending message.
            mLock = new ReentrantLock(true);
            mBuffers = new ByteBuffer[] {ByteBuffer.allocate(2), null};
            channel.register(mSelector = Selector.open(), SelectionKey.OP_WRITE);

            mReader = new Reader(channel, this);
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

        public void receive(MessageReceiver receiver) {
            mReader.enqueueAndRegister(receiver);
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
            return "MessageConnection {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        public void close() throws IOException {
            close(null);
        }

        // Called directly by Reader.
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
                            uncaughtException(e);
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
                    uncaughtException(e2);
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

    private class Reader implements Registerable, Selectable<MessageReceiver>, Runnable {
        // Allocate extra two for size prefix.
        private static final int BUFFER_SIZE = 2 + 8192;

        private final Executor mExecutor;

        private final SocketChannel mChannel;
        private final Con mCon;

        private final ByteBuffer mBuffer;

        private final ConcurrentLinkedQueue<MessageReceiver> mReceiverQueue;

        // Current receiver of message.
        private volatile MessageReceiver mReceiver;

        // Size of message to receive. Is zero when no message is being
        // received, is negative when size is partially known, and is
        // positive when message size is known.
        private int mSize;

        // Amount of message read so far.
        private int mOffset;

        Reader(SocketChannel channel, Con con) {
            mExecutor = SocketMessageProcessor.this.mExecutor;

            mChannel = channel;
            mCon = con;

            mBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            mBuffer.limit(0);

            mReceiverQueue = new ConcurrentLinkedQueue<MessageReceiver>();
        }

        public void enqueueAndRegister(MessageReceiver receiver) {
            enqueue(receiver);
            enqueueRegister(this);
        }

        public void register(Selector selector) throws IOException {
            SelectionKey key = mChannel.register(selector, SelectionKey.OP_READ, this);
            if (mBuffer.hasRemaining()) {
                MessageReceiver receiver = doReceive(key);
                if (receiver != null) {
                    selectedExecute(receiver);
                }
            }
        }

        public void registerException(IOException e) {
            handleException(e);
        }

        public MessageReceiver selected(SelectionKey key) throws IOException {
            ByteBuffer buffer = mBuffer;
            buffer.mark();
            buffer.limit(buffer.capacity());

            int amt = mChannel.read(buffer);

            if (amt <= 0) {
                if (amt != 0) {
                    // Throw exception so that closing the connection may safely block.
                    throw new EOF();
                }
                return null;
            }

            buffer.limit(buffer.position());
            buffer.reset();

            return doReceive(key);
        }

        /**
         * Caller is responsible for ensuring that buffer has remaining data.
         */
        private MessageReceiver doReceive(SelectionKey key) throws IOException {
            MessageReceiver receiver = mReceiver;
            if (receiver == null) {
                if ((mReceiver = receiver = dequeue()) == null) {
                    if (key != null) {
                        key.cancel();
                    }
                    return null;
                }
            }

            ByteBuffer buffer = mBuffer;
            int size = mSize;

            while (true) {
                if (size <= 0) {
                    if (size == 0) {
                        // Nothing is known about message yet.
                        if (buffer.remaining() == 1) {
                            // Only first byte of size known so far.
                            mSize = ~(buffer.get() & 0xff);
                            buffer.clear();
                            return null;
                        } 
                        // Size is fully known.
                        mSize = size = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
                    } else {
                        // Size is partially known, but now is fully known.
                        mSize = size = ((~size) << 8) | (buffer.get() & 0xff);
                    }
                    if (!buffer.hasRemaining()) {
                        // Buffer fully drained, but no message received yet.
                        buffer.clear();
                        return null;
                    }
                }

                int originalPos = buffer.position();
                int originalLimit = buffer.limit();
                int len = Math.min(size - mOffset, originalLimit - originalPos);
                buffer.limit(originalPos + len);

                enqueue(receiver.receive(size, mOffset, buffer));

                if ((mOffset += len) < size) {
                    // Buffer fully drained, but message is not fully received.
                    buffer.clear();
                    return null;
                }

                // If this point is reached, message has been fully received.

                // Prepare for next message.
                mSize = size = 0;
                mOffset = 0;

                if ((originalLimit - originalPos - len) <= 0) {
                    // Buffer fully drained, so return receiver for processing.
                    mReceiver = dequeue();
                    buffer.clear();
                    return receiver;
                }

                buffer.position(originalPos + len);
                buffer.limit(originalLimit);

                final MessageReceiver nextReceiver;
                if ((nextReceiver = dequeue()) == null) {
                    // No more receivers, so stop selecting and return current
                    // receiver for processing.
                    mReceiver = null;
                    if (key != null) {
                        key.cancel();
                    }
                    return receiver;
                }

                // Link up the receivers for processing as soon as buffer is drained.
                mReceiver = receiver = new LinkedReceiver(mExecutor, receiver, nextReceiver);
            }
        }

        public void selectedException(IOException e) {
            handleException(e);
        }

        public void selectedExecute(MessageReceiver receiver) {
            receiver.process();
        }

        public void run() {
            selectedExecute(null);
        }

        private MessageReceiver dequeue() {
            return mReceiverQueue.poll();
        }

        private void enqueue(MessageReceiver receiver) {
            if (receiver != null) {
                mReceiverQueue.add(receiver);
            }
        }

        private void handleException(IOException e) {
            try {
                if (e instanceof EOF) {
                    e = null;
                }
                mCon.close(e);
            } catch (IOException e2) {
                uncaughtException(e2);
            } finally {
                MessageReceiver receiver = mReceiver;
                if (receiver != null) {
                    if (e == null) {
                        receiver.closed();
                    } else {
                        receiver.closed(e);
                    }
                } else if (e != null) {
                    uncaughtException(e);
                }
            }
        }

        private class EOF extends IOException {
        }
    }

    /**
     * Forms a linked list of receivers which process concurrently.
     */
    private static class LinkedReceiver implements MessageReceiver, Runnable {
        private final Executor mExecutor;
        private final MessageReceiver mFirst;
        private final MessageReceiver mNext;

        LinkedReceiver(Executor executor, MessageReceiver first, MessageReceiver next) {
            mExecutor = executor;
            mFirst = first;
            mNext = next;
        }

        public MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
            return mNext.receive(totalSize, offset, buffer);
        }

        public void closed() {
            mNext.closed();
        }

        public void closed(IOException e) {
            mNext.closed();
        }

        public void process() {
            try {
                // Enqueue next receiver under the assumption that a context
                // switch will need to occur before it actually executes. This
                // makes it more likely that first receiver is actually
                // processed first, although this is not required.
                mExecutor.execute(this);
            } catch (RejectedExecutionException e) {
                // Execute sequentially instead.
                mFirst.process();
                mNext.process();
                return;
            }
            mFirst.process();
        }

        public void run() {
            mNext.process();
        }
    }
}
