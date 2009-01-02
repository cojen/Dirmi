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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Buffers I/O for a channel and supports channel recycling. Protocol
 * implemented by this channel is also understood by SocketMessageProcessor.
 *
 * @author Brian S O'Neill
 */
class PacketStreamChannel implements StreamChannel {
    private static final AtomicIntegerFieldUpdater<PacketStreamChannel> cClosedUpdater =
        AtomicIntegerFieldUpdater.newUpdater(PacketStreamChannel.class, "mClosed");

    final Executor mExecutor;
    private final Recycler<StreamChannel> mRecycler;

    private final StreamChannel mChannel;
    private final In mIn;
    private final Out mOut;

    private volatile int mClosed;

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
        if (cClosedUpdater.compareAndSet(this, 0, 1)) {
            mIn.doClose(true);
            try {
                mOut.doClose();
            } catch (IOException e) {
                disconnect();
                throw e;
            }
        }
    }

    public void disconnect() {
        if (cClosedUpdater.compareAndSet(this, 0, 1)) {
            mChannel.disconnect();
            mIn.doClose(false);
        }
    }

    // Should only be called by executed task, allowing recycler operation to
    // safely block.
    void reachedEOF() {
        try {
            if (cClosedUpdater.compareAndSet(this, 0, 1)) {
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

    private class In extends BufferedInputStream {
        // -2: Input closed; throw IOException
        // -1: No more packets; return EOF
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
                return -1;
            }
            int b = super.read();
            if (b < 0) {
                mRemaining = -2;
                throw new IOException("Closed");
            }
            mRemaining = remaining - 1;
            return b;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            int remaining = mRemaining;
            if (remaining <= 0 && (remaining = remaining()) <= 0) {
                return -1;
            }
            if (len > remaining) {
                len = remaining;
            }
            int amt = super.read(b, off, len);
            if (amt <= 0) {
                if (len <= 0) {
                    return amt;
                }
                mRemaining = -2;
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
                    if (remaining == -1) {
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

        void doClose(boolean drain) {
            final int remaining;
            synchronized (this) {
                remaining = mRemaining;
                mRemaining = -2;
            }
            if (drain && remaining >= 0) {
                mExecutor.execute(new Runnable() {
                    public void run() {
                        drain(remaining);
                    }
                });
            }
        }

        synchronized void drain(int remaining) {
            try {
                while (true) {
                    skipAll(remaining);

                    // Read next packet size.
                    int b = super.read();
                    if (b <= 0) {
                        if (b == 0) {
                            reachedEOF();
                        } else {
                            disconnect();
                        }
                        return;
                    } else if (b < 128) {
                        remaining = b;
                    } else {
                        remaining = ((b & 0x7f) << 8) + 128;
                        b = super.read();
                        if (b < 0) {
                            disconnect();
                            return;
                        }
                        remaining += b;
                    }
                }
            } catch (IOException e) {
                disconnect();
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
                            remaining = -1;
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
                            remaining = -2;
                        }
                    } else if (b < 128) {
                        remaining = b;
                    } else {
                        remaining = ((b & 0x7f) << 8) + 128;
                        b = super.read();
                        if (b < 0) {
                            remaining = -2;
                        } else {
                            remaining += b;
                        }
                    }

                    mRemaining = remaining;
                }

                if (remaining <= 0) {
                    if (remaining == -1) {
                        return -1;
                    }
                    throw new IOException("Closed");
                }
            }

            return remaining;
        }
    }

    private class Out extends BufferedOutputStream {
        static final int MAX_SIZE = 32768 + 127;

        // 0: open
        // 1: closing
        // 2: closed
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
            if (mState != 0) {
                throw new IOException("Closed");
            }
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (mState != 0) {
                throw new IOException("Closed: " + this);
            }
            super.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            PacketStreamChannel.this.close();
        }

        synchronized void doClose() throws IOException {
            try {
                mState = 1;
                flush(true);
            } finally {
                mState = 2;
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

            if (mState == 1) {
                buffer[offset + length] = 0;
                length++;
            }

            super.doFlush(buffer, offset, length);
        }

        @Override
        protected void disconnect() {
            PacketStreamChannel.this.disconnect();
        }
    }
}
