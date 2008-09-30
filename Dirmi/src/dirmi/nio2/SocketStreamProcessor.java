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

import java.io.Closeable;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.Iterator;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
public class SocketStreamProcessor implements Closeable {
    final Executor mExecutor;

    final ConcurrentLinkedQueue<Registerable> mSelectQueue;
    final ReentrantLock mSelectLock;
    final Selector mSelector;

    int mSelectTaskCount;

    public SocketStreamProcessor(Executor executor) throws IOException {
        if (executor == null) {
            throw new IllegalArgumentException();
        }

        mExecutor = executor;

        mSelectQueue = new ConcurrentLinkedQueue<Registerable>();
        // Use unfair lock since arbitrary worker threads do reads, and so
        // waiting on a queue is not necessary.
        mSelectLock = new ReentrantLock(false);
        mSelector = Selector.open();

        mSelectLock.lock();
        try {
            mSelectTaskCount++;
        } finally {
            mSelectLock.unlock();
        }

        executor.execute(new SelectTask());
    }

    public StreamConnector newConnector(SocketAddress endpoint) {
        return newConnector(endpoint, null);
    }

    public StreamConnector newConnector(final SocketAddress endpoint,
                                        final SocketAddress bindpoint)
    {
        if (endpoint == null) {
            throw new IllegalArgumentException();
        }

        return new StreamConnector() {
            public StreamChannel connect() throws IOException {
                final SocketChannel channel = SocketChannel.open();
                channel.socket().setTcpNoDelay(true);

                if (bindpoint != null) {
                    channel.socket().bind(bindpoint);
                }

                channel.configureBlocking(true);
                channel.socket().connect(endpoint);

                return new Chan(channel);
            }

            public void execute(Runnable task) {
                mExecutor.execute(task);
            }

            @Override
            public String toString() {
                return "StreamConnector {endpoint=" + endpoint + ", bindpoint=" + bindpoint + '}';
            }
        };
    }

    public StreamAcceptor newAcceptor(final SocketAddress bindpoint) throws IOException {
        if (bindpoint == null) {
            throw new IllegalArgumentException();
        }

        final ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(bindpoint);
        serverChannel.configureBlocking(false);

        class Accept implements Registerable, Selectable<SocketChannel> {
            private final StreamListener mListener;

            Accept(StreamListener listener) {
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
                    channel.socket().setTcpNoDelay(true);
                    channel.configureBlocking(true);
                }
                return channel;
            }

            public void selectedException(IOException e) {
                mListener.failed(e);
            }

            public void selectedExecute(SocketChannel channel) {
                try {
                    mListener.established(new Chan(channel));
                } catch (IOException e) {
                    mListener.failed(e);
                }
            }
        };

        return new StreamAcceptor() {
            public void accept(StreamListener listener) {
                enqueueRegister(new Accept(listener));
            }

            public void execute(Runnable task) {
                mExecutor.execute(task);
            }

            @Override
            public String toString() {
                return "StreamAcceptor {bindpoint=" + bindpoint + '}';
            }
        };
    }

    public void close() throws IOException {
        mSelector.close();
    }

    void enqueueRegister(Registerable registerable) {
        mSelectQueue.add(registerable);
        mSelector.wakeup();
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

    private class SelectTask implements Runnable {
        SelectTask() {
        }

        public void run() {
            final ConcurrentLinkedQueue<Registerable> queue = mSelectQueue;
            final ReentrantLock lock = mSelectLock;
            final Selector selector = mSelector;

            lock.lock();
            boolean hasLock = true;
            try {
                while (true) {
                    try {
                        selector.select();
                    } catch (IOException e) {
                        uncaughtException(e);
                        --mSelectTaskCount;
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

                        if (--mSelectTaskCount == 0) {
                            // Ensure another thread is ready to select in case
                            // execute method blocks.
                            try {
                                mExecutor.execute(this);
                                mSelectTaskCount++;
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
                                mSelectTaskCount++;
                            } else {
                                // Replacement has taken control.
                                return;
                            }
                        }
                    }
                }
            } catch (ClosedSelectorException e) {
                if (hasLock) {
                    --mSelectTaskCount;
                }
            } finally {
                if (hasLock) {
                    lock.unlock();
                }
            }
        }
    }

    private class Chan implements StreamChannel {
        private final SocketChannel mChannel;
        private final Input mIn;
        private final Output mOut;
        final Reader mReader;

        Chan(SocketChannel channel) throws IOException {
            mChannel = channel;
            mIn = new Input(channel.socket().getInputStream());
            mOut = new Output(channel.socket().getOutputStream());
            mReader = new Reader(channel, this);
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public void executeWhenReadable(StreamTask task) throws IOException {
            mReader.enqueueAndRegister(task);
        }

        public Object getLocalAddress() {
            return mChannel.socket().getLocalSocketAddress();
        }

        public Object getRemoteAddress() {
            return mChannel.socket().getRemoteSocketAddress();
        }

        @Override
        public String toString() {
            return "StreamChannel {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        public void execute(Runnable task) {
            mExecutor.execute(task);
        }

        public void close() throws IOException {
            mChannel.close();
        }

        public boolean isOpen() {
            return mChannel.isOpen();
        }

        private class Input extends AbstractBufferedInputStream {
            private final InputStream mIn;

            Input(InputStream in) {
                mIn = in;
            }

            @Override
            public synchronized int available() throws IOException {
                int available = super.available();
                if (available > 0) {
                    return available;
                }
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    return mIn.available();
                } finally {
                    reader.resume(b);
                }
            }

            @Override
            protected int doRead(byte[] buffer, int offset, int length) throws IOException {
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    return mIn.read(buffer, offset, length);
                } finally {
                    reader.resume(b);
                }
            }

            @Override
            protected long doSkip(long n) throws IOException {
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    return mIn.skip(n);
                } finally {
                    reader.resume(b);
                }
            }
        }

        private class Output extends AbstractBufferedOutputStream {
            private final OutputStream mOut;

            Output(OutputStream out) {
                mOut = out;
            }

            @Override
            public synchronized void flush() throws IOException {
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    super.flush();
                    mOut.flush();
                } finally {
                    reader.resume(b);
                }
            }

            @Override
            public synchronized void close() throws IOException {
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    super.flush();
                    mOut.close();
                } finally {
                    reader.resume(b);
                }
            }

            @Override
            protected void doWrite(byte[] buffer, int offset, int length)
                throws IOException
            {
                Reader reader = mReader;
                boolean b = reader.suspend();
                try {
                    mOut.write(buffer, offset, length);
                } finally {
                    reader.resume(b);
                }
            }
        }
    }

    /**
     * Asynchronous stream reader. When suspended, channel is in blocking mode.
     */
    private class Reader implements Registerable, Selectable<StreamTask> {
        private final Executor mExecutor;
        private final SocketChannel mChannel;
        private final Chan mChan;
        private final ConcurrentLinkedQueue<StreamTask> mTaskQueue;
        private final ReentrantReadWriteLock mModeLock;

        Reader(SocketChannel channel, Chan chan) {
            mExecutor = SocketStreamProcessor.this.mExecutor;
            mChannel = channel;
            mChan = chan;
            mTaskQueue = new ConcurrentLinkedQueue<StreamTask>();
            mModeLock = new ReentrantReadWriteLock();
        }

        void enqueueAndRegister(StreamTask task) throws IOException {
            if (task == null) {
                throw new IllegalArgumentException();
            }

            ReentrantReadWriteLock modeLock = mModeLock;
            // This will wait for any blocking I/O to complete.
            modeLock.writeLock().lock();
            try {
                mTaskQueue.add(task);
                if (mChannel.isBlocking()) {
                    mChannel.configureBlocking(false);
                    enqueueRegister(this);
                }
            } finally {
                modeLock.writeLock().unlock();
            }
        }

        boolean suspend() throws IOException {
            ReentrantReadWriteLock modeLock = mModeLock;

            modeLock.readLock().lock();
            if (mChannel.isBlocking()) {
                return false;
            }

            // Release and acquire write lock.
            modeLock.readLock().unlock();
            modeLock.writeLock().lock();
            modeLock.readLock().lock();

            if (mChannel.isBlocking()) {
                // Release write lock but keep read lock.
                modeLock.writeLock().unlock();
                return false;
            }

            try {
                mChannel.configureBlocking(true);
            } catch (IOException e) {
                modeLock.readLock().unlock();
                modeLock.writeLock().unlock();
                throw e;
            }

            // Keep write and read locks held to prevent reader from resuming.
            return true;
        }

        void resume(boolean suspended) throws IOException {
            ReentrantReadWriteLock modeLock = mModeLock;
            modeLock.readLock().unlock();
            if (suspended) {
                // Write lock is still held.
                try {
                    if (mChannel.isBlocking() && !mTaskQueue.isEmpty()) {
                        mChannel.configureBlocking(false);
                        enqueueRegister(this);
                    }
                } finally {
                    modeLock.writeLock().unlock();
                }
            }
        }

        public void register(Selector selector) throws IOException {
            mChannel.register(selector, SelectionKey.OP_READ, this);
        }

        public void registerException(IOException e) {
            handleException(e);
        }

        public StreamTask selected(SelectionKey key) throws IOException {
            key.cancel();
            mChannel.configureBlocking(true);
            return mTaskQueue.poll();
        }

        public void selectedException(IOException e) {
            handleException(e);
        }

        public void selectedExecute(StreamTask task) {
            task.run();
        }

        private void handleException(IOException e) {
            try {
                mChan.close();
            } catch (IOException e2) {
                uncaughtException(e2);
            } finally {
                StreamTask task;
                while ((task = mTaskQueue.poll()) != null) {
                    task.closed(e);
                }
            }
        }
    }
}
