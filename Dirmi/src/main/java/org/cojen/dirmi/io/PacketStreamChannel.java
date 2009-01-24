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
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buffers I/O for a channel and supports channel recycling. Protocol
 * implemented by this channel is also understood by SocketMessageProcessor.
 *
 * @author Brian S O'Neill
 */
class PacketStreamChannel extends AbstractChannel implements StreamChannel {
    final Executor mExecutor;
    private final Recycler<StreamChannel> mRecycler;

    private final StreamChannel mChannel;
    private final In mIn;
    private final Out mOut;

    private final AtomicBoolean mClosed = new AtomicBoolean();

    PacketStreamChannel(Executor executor, Recycler<StreamChannel> recycler, StreamChannel channel)
        throws IOException
    {
        mExecutor = executor;
        mRecycler = recycler;
        mChannel = channel;
        mIn = new In(channel.getInputStream());
        mOut = new Out(channel.getOutputStream());
    }

    // Copy constructor which is reset to read and write again.
    PacketStreamChannel(PacketStreamChannel channel) {
        mExecutor = channel.mExecutor;
        mRecycler = channel.mRecycler;
        mChannel = channel.mChannel;
        mIn = new In(channel.mIn);
        mOut = new Out(channel.mOut);
    }

    public InputStream getInputStream() throws IOException {
        return mIn;
    }

    public OutputStream getOutputStream() throws IOException {
        return mOut;
    }

    public Object getLocalAddress() {
        return mChannel.getLocalAddress();
    }

    public Object getRemoteAddress() {
        return mChannel.getRemoteAddress();
    }

    @Override
    public String toString() {
        return "PacketStreamChannel {localAddress=" + getLocalAddress() +
            ", remoteAddress=" + getRemoteAddress() + '}';
    }

    public void close() throws IOException {
        if (mClosed.compareAndSet(false, true)) {
            // Flush and close output before input to prevent channel from
            // being recycled before flush has finished.
            try {
                mOut.doClose();
            } catch (IOException e) {
                mChannel.disconnect();
                throw e;
            }

            mIn.doClose(mChannel);

            closed();
        }
    }

    public boolean isOpen() {
        return mChannel.isOpen() && mOut.isOpen() && !mClosed.get();
    }

    public void remoteClose() throws IOException {
        // Only close the output in order to allow remaining input to be read.
        mOut.remoteClose();
    }

    public void disconnect() {
        if (mClosed.compareAndSet(false, true)) {
            mChannel.disconnect();
            mIn.doClose(null);
        }
    }

    public Closeable getCloser() {
        // Don't return direct reference to wrapped channel, as this interferes
        // with channel recycling.
        return new Closer(mChannel, mClosed);
    }

    // Should only be called by executed task, allowing recycler operation to
    // safely block.
    void reachedEOF() {
        try {
            if (mClosed.compareAndSet(false, true)) {
                mOut.doClose();
            }
            if (mRecycler != null) {
                mRecycler.recycled(new PacketStreamChannel(this));
            } else {
                mChannel.disconnect();
            }
        } catch (IOException e) {
            disconnect();
        }
    }

    private static class Closer implements Closeable {
        private final StreamChannel mChannel;
        private final AtomicBoolean mClosed;

        Closer(StreamChannel channel, AtomicBoolean closed) {
            mChannel = channel;
            mClosed = closed;
        }

        public void close() throws IOException {
            if (mClosed.compareAndSet(false, true)) {
                mChannel.close();
            }
        }
    }

    private class In extends BufferedInputStream {
        private static final int INPUT_CLOSED = -2, INPUT_EOF = -1;

        // INPUT_CLOSED: Throw IOException
        // INPUT_EOF: No more packets; return EOF
        //  0: Call readNext to read next packet size
        // >0: Bytes remaining in current packet
        private int mRemaining;

        In(InputStream in) {
            super(in);
        }

        In(InputStream in, int size) {
            super(in, size);
        }

        // Copy constructor which is reset to read again.
        In(In in) {
            super(in);
        }

        @Override
        public synchronized int read() throws IOException {
            int remaining = mRemaining;
            if (remaining <= 0 && (remaining = remaining()) <= 0) {
                return INPUT_EOF;
            }
            int b = super.read();
            if (b < 0) {
                mRemaining = INPUT_CLOSED;
                throw new IOException("Closed");
            }
            mRemaining = remaining - 1;
            return b;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            int remaining = mRemaining;
            if (remaining <= 0 && (remaining = remaining()) <= 0) {
                return INPUT_EOF;
            }
            if (len > remaining) {
                len = remaining;
            }
            int amt = super.read(b, off, len);
            if (amt <= 0) {
                if (len <= 0) {
                    return amt;
                }
                mRemaining = INPUT_CLOSED;
                throw new IOException("Closed");
            }
            mRemaining = remaining - amt;
            return amt;
        }

        @Override
        public synchronized long skip(long n) throws IOException {
            int remaining = mRemaining;
            if (remaining <= 0 && (remaining = remaining()) <= 0) {
                return 0;
            }
            if (n > remaining) {
                n = remaining;
            }
            n = super.skip(n);
            mRemaining = remaining - (int) n;
            return n;
        }

        @Override
        public synchronized int available() throws IOException {
            int remaining = mRemaining;

            if (remaining <= 0) {
                if (remaining < 0) {
                    if (remaining == INPUT_EOF) {
                        return 0;
                    }
                    throw new IOException("Closed");
                }

                remaining = super.available();
                if (remaining <= 2) {
                    // Not enough available to safely read next packet size.
                    return 0;
                }

                if ((remaining = remaining()) < 0) {
                    return 0;
                }
            }

            return remaining;
        }

        @Override
        public void close() throws IOException {
            PacketStreamChannel.this.close();
        }

        /**
         * @param channel pass underlying channel if stream should be drained
         */
        void doClose(final StreamChannel channel) {
            final int remaining;
            synchronized (this) {
                remaining = mRemaining;
                mRemaining = INPUT_CLOSED;
            }

            if (channel != null && remaining >= 0) {
                try {
                    mExecutor.execute(new Runnable() {
                        public void run() {
                            if (!drain(remaining)) {
                                channel.disconnect();
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // Don't recycle channel.
                    channel.disconnect();
                }
            }
        }

        /**
         * @return false if channel should be disconnected
         */
        synchronized boolean drain(int remaining) {
            try {
                while (true) {
                    skipAll(remaining);

                    // Read next packet size.
                    int b = super.read();
                    if (b <= 0) {
                        if (b == 0) {
                            reachedEOF();
                            return true;
                        } else {
                            return false;
                        }
                    } else if (b < 128) {
                        remaining = b;
                    } else {
                        remaining = ((b & 0x7f) << 8) + 128;
                        b = super.read();
                        if (b < 0) {
                            return false;
                        }
                        remaining += b;
                    }
                }
            } catch (IOException e) {
                return false;
            }
        }

        private void skipAll(int amount) throws IOException {
            while (amount > 0) {
                long actual = super.skip(amount);
                if (actual <= 0) {
                    throw new IOException("Closed");
                }
                amount -= actual;
            }
        }

        @Override
        protected void disconnect() {
            PacketStreamChannel.this.disconnect();
        }

        /**
         * @return negative if EOF; non-zero value otherwise
         */
        private int remaining() throws IOException {
            int remaining = mRemaining;
            if (remaining <= 0) {
                if (remaining == 0) {
                    // Read next packet size.
                    int b = super.read();
                    if (b <= 0) {
                        if (b == 0) {
                            remaining = INPUT_EOF;
                            try {
                                mExecutor.execute(new Runnable() {
                                    public void run() {
                                        reachedEOF();
                                    }
                                });
                            } catch (RejectedExecutionException e) {
                                disconnect();
                            }
                        } else {
                            remaining = INPUT_CLOSED;
                        }
                    } else if (b < 128) {
                        remaining = b;
                    } else {
                        remaining = ((b & 0x7f) << 8) + 128;
                        b = super.read();
                        if (b < 0) {
                            remaining = INPUT_CLOSED;
                        } else {
                            remaining += b;
                        }
                    }

                    mRemaining = remaining;
                }

                if (remaining <= 0) {
                    if (remaining == INPUT_EOF) {
                        return INPUT_EOF;
                    }
                    throw new IOException("Closed");
                }
            }

            return remaining;
        }
    }

    private class Out extends BufferedOutputStream {
        static final int MAX_SIZE = 32768 + 127;

        private static final int
            OPEN = 0,
            REMOTE_CLOSE = 1,
            CLOSING = 2,
            CLOSED = 3;

        private int mState;

        Out(OutputStream out) {
            this(out, DEFAULT_SIZE);
        }

        Out(OutputStream out, int size) {
            super(out, 2, Math.min(size, MAX_SIZE), 1);
        }

        // Copy constructor which is reset to write again.
        Out(Out out) {
            super(out);
        }

        @Override
        public synchronized void write(int b) throws IOException {
            checkOpen();
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            checkOpen();
            super.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            PacketStreamChannel.this.close();
        }

        synchronized boolean isOpen() {
            return mState == OPEN;
        }

        synchronized void remoteClose() throws IOException {
            if (mState == OPEN) {
                mState = REMOTE_CLOSE;
            }
        }

        synchronized void doClose() throws IOException {
            if (mState != CLOSED) {
                try {
                    mState = CLOSING;
                    flush(true);
                } finally {
                    mState = CLOSED;
                }
            }
        }

        @Override
        protected void doFlush(byte[] buffer, int offset, int length) throws IOException {
            if (length > 0) {
                if (length < 128) {
                    buffer[--offset] = (byte) length;
                    length++;
                } else {
                    buffer[--offset] = (byte) (length - 128);
                    buffer[--offset] = (byte) (0x80 | ((length - 128) >> 8));
                    length += 2;
                }
            }

            if (mState == CLOSING) {
                buffer[offset + length] = 0;
                length++;
            }

            super.doFlush(buffer, offset, length);
        }

        @Override
        protected void disconnect() {
            PacketStreamChannel.this.disconnect();
        }

        private void checkOpen() throws IOException {
            if (mState != OPEN) {
                String message = "Closed";
                if (mState == REMOTE_CLOSE) {
                    message = message.concat(" by remote endpoint");
                }
                throw new IOException(message);
            }
        }
    }
}
