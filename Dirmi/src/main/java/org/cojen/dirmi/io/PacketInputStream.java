/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;

/**
 * Buffered InputStream which is paired with {@link PacketOutputStream}.
 *
 * @author Brian S O'Neill
 */
abstract class PacketInputStream<P extends PacketInputStream<P>> extends ChannelInputStream {
    private static final AtomicReferenceFieldUpdater<PacketInputStream, InputStream> inUpdater =
        AtomicReferenceFieldUpdater
        .newUpdater(PacketInputStream.class, InputStream.class, "mIn");

    static final int DEFAULT_SIZE = 8192;

    private volatile InputStream mIn;
    private byte[] mBuffer;

    private int mStart;
    private int mEnd;

    /* Special values:
        0: need to read next packet header
       -1: ready to be recycled
       -2: cannot be recycled
    */
    private int mPacketSize;

    public PacketInputStream(InputStream in) {
        this(in, DEFAULT_SIZE);
    }

    public PacketInputStream(InputStream in, int size) {
        mIn = in;
        synchronized (this) {
            mBuffer = new byte[size];
        }
    }

    protected PacketInputStream() {
    }

    @Override
    public int read() throws IOException {
        try {
            synchronized (this) {
                InputStream in = in();
                int packetSize = mPacketSize;
                if (packetSize < 0) {
                    return -1;
                }
                byte[] buffer = mBuffer;
                int b = readFrom(in, buffer);
                if (b < 0) {
                    mPacketSize = -2;
                    return b;
                }
                if (--packetSize >= 0) {
                    mPacketSize = packetSize;
                    return b;
                }
                if (b == 0) {
                    mPacketSize = -1;
                    return -1;
                }
                int b2 = readFrom(in, buffer);
                if (b2 < 0) {
                    mPacketSize = -2;
                    return -1;
                }
                if (b < 128) {
                    mPacketSize = b - 1;
                    return b2;
                }
                int b3 = readFrom(in, buffer);
                if (b3 < 0) {
                    mPacketSize = -2;
                    return -1;
                }
                mPacketSize = (((b & 0x7f) << 8) | b2) + (0x80 - 1);
                return b3;
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Low level read that ignores packet info. Caller must be synchronized.
     */
    private int readFrom(InputStream in, byte[] buffer) throws IOException {
        if (buffer == null) {
            // Buffer can be null if disconnected.
            throw new ClosedException();
        }
        int start = mStart;
        if (mEnd - start > 0) {
            mStart = start + 1;
            return buffer[start] & 0xff;
        } else {
            int amt = in.read(buffer, 0, buffer.length);
            if (amt <= 0) {
                return -1;
            } else {
                mStart = 1;
                mEnd = amt;
                return buffer[0] & 0xff;
            }
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            synchronized (this) {
                InputStream in = in();
                byte[] buffer = mBuffer;
                int packetSize = mPacketSize;
                if (packetSize <= 0) {
                    if (packetSize < 0) {
                        return -1;
                    }
                    int b1 = readFrom(in, buffer);
                    if (b1 <= 0) {
                        mPacketSize = b1 == 0 ? -1 : -2;
                        return -1;
                    }
                    if (b1 < 128) {
                        packetSize = b1;
                    } else {
                        int b2 = readFrom(in, buffer);
                        if (b2 < 0) {
                            mPacketSize = -2;
                            return -1;
                        }
                        packetSize = (((b1 & 0x7f) << 8) | b2) + 0x80;
                    }
                }
                int amt = readFrom(in, buffer, b, off, len > packetSize ? packetSize : len);
                if (amt < 0) {
                    mPacketSize = -2;
                    return -1;
                }
                mPacketSize = packetSize - amt;
                return amt;
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Low level read that ignores packet info. Caller must be synchronized.
     */
    private int readFrom(InputStream in, byte[] buffer, byte[] b, int off, int len)
        throws IOException
    {
        int start = mStart;
        int avail = mEnd - start;
        if (avail >= len) {
            System.arraycopy(buffer, start, b, off, len);
            mStart = start + len;
            return len;
        } else {
            System.arraycopy(buffer, start, b, off, avail);
            mStart = 0;
            mEnd = 0;
            off += avail;
            len -= avail;
            try {
                // Avoid a blocking read.
                if (avail > 0 && (len = Math.min(len, in.available())) <= 0) {
                    return avail;
                }
                if (len >= buffer.length) {
                    int amt = in.read(b, off, len);
                    return amt <= 0 ? (avail <= 0 ? -1 : avail) : (avail + amt);
                } else {
                    int amt = in.read(buffer, 0, buffer.length);
                    if (amt <= 0) {
                        return avail <= 0 ? -1 : avail;
                    } else {
                        int fill = Math.min(amt, len);
                        System.arraycopy(buffer, 0, b, off, fill);
                        mStart = fill;
                        mEnd = amt;
                        return avail + fill;
                    }
                }
            } catch (IOException e) {
                if (avail > 0) {
                    // Return what we have and postpone exception for later.
                    return avail;
                }
                throw e;
            }
        }
    }

    @Override
    public long skip(long n) throws IOException {
        try {
            synchronized (this) {
                InputStream in = in();
                if (n <= 0) {
                    return 0;
                }
                if (n > Integer.MAX_VALUE) {
                    n = Integer.MAX_VALUE;
                }
                return skip(in, (int) n);
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    private int skip(InputStream in, int n) throws IOException {
        int packetSize = mPacketSize;

        if (packetSize <= 0) {
            if (packetSize < 0) {
                return 0;
            }
            int b1 = readFrom(in, mBuffer);
            if (b1 <= 0) {
                mPacketSize = b1 == 0 ? -1 : -2;
                return 0;
            }
            if (b1 < 128) {
                packetSize = b1;
            } else {
                int b2 = readFrom(in, mBuffer);
                if (b2 < 0) {
                    mPacketSize = -2;
                    return 0;
                }
                packetSize = (((b1 & 0x7f) << 8) | b2) + 0x80;
            }
        }

        if (n > packetSize) {
            n = packetSize;
        }

        int amt = mEnd - mStart;
        if (amt <= 0) {
            if ((n = (int) in.skip(n)) <= 0) {
                mPacketSize = -2;
                return 0;
            }
        } else {
            if (n > amt) {
                n = amt;
            }
            mStart += n;
        }

        mPacketSize = packetSize - n;
        return n;
    }

    /**
     * @return false if more to drain; true does not imply that stream was recycled
     */
    boolean drain(InputStream in, int amount) {
        byte[] buffer;
        int start, end;

        try {
            synchronized (this) {
                while (skip(in, amount) > 0);
                if (mPacketSize != -1) {
                    return mPacketSize < -1;
                }
                if ((buffer = mBuffer) == null) {
                    return true;
                }
                start = mStart;
                end = mEnd;
            }
        } catch (IOException e) {
            try {
                in.close();
            } catch (IOException e2) {
                // Ignore.
            }
            return true;
        }

        P recycled = newInstance();
        synchronized (recycled) {
            recycled.mIn = in;
            recycled.mBuffer = buffer;
            recycled.mStart = start;
            recycled.mEnd = end;
        }
        recycled(recycled);

        return true;
    }

    @Override
    public int available() throws IOException {
        // Without accounting for encoded packet sizes, the available amount
        // would be reported as too high. So don't bother figuring it out.
        in(); // check if closed
        return 0;
    }

    @Override
    public boolean isReady() throws IOException {
        try {
            synchronized (this) {
                InputStream in = in();
                return mPacketSize >= 0 && ((mEnd - mStart) > 0 || in.available() > 0);
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Sets the size of the buffer, returning the actual size applied.
     */
    @Override
    public synchronized int setBufferSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer too small: " + size);
        }

        byte[] buffer = mBuffer;
        if (buffer == null) {
            return 0;
        }

        int avail = mEnd - mStart;

        if (size < buffer.length) {
            size = Math.max(size, avail);
        }
        if (size != buffer.length) {
            byte[] newBuffer = new byte[size];
            System.arraycopy(buffer, mStart, newBuffer, 0, avail);
            mBuffer = newBuffer;
            mStart = 0;
            mEnd = avail;
        }

        return size;
    }

    /**
     * Ensures at least one byte can be read, blocking if necessary.
     *
     * @throws EOFException if nothing left to read 
     */
    void fill() throws IOException {
        try {
            synchronized (this) {
                InputStream in = in();
                byte[] buffer = mBuffer;
                if (buffer == null) {
                    throw new ClosedException();
                }
                int avail = mEnd - mStart;
                if (avail == 0) {
                    int amt = in.read(buffer, 0, buffer.length);
                    if (amt <= 0) {
                        throw new EOFException();
                    }
                    mStart = 0;
                    mEnd = amt;
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    void inputNotify(IOExecutor executor, final Channel.Listener listener) {
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        fill();
                        listener.ready();
                    } catch (IOException e) {
                        listener.closed(e);
                    }
                }
            });
        } catch (RejectedException e) {
            listener.rejected(e);
        }
    }

    @Override
    public void close() throws IOException {
        inputClose();
    }

    @Override
    public void disconnect() {
        inputDisconnect();
    }

    @Override
    public synchronized boolean inputResume() {
        if (mIn == null) {
            return false;
        }
        // Peek ahead and consume EOF if possible.
        if (mPacketSize == 0 && (mEnd - mStart) > 0 && mBuffer[mStart] == 0) {
            try {
                read();
            } catch (IOException e) {
                return false;
            }
        }
        if (mPacketSize != -1) {
            return false;
        }
        mPacketSize = 0;
        return true;
    }

    @Override
    public boolean isResumeSupported() {
        return true;
    }

    @Override
    final void inputClose() throws IOException {
        final InputStream in = inUpdater.getAndSet(this, null);
        if (in == null) {
            return;
        }

        doRecycle: {
            byte[] buffer;
            int start, end;

            synchronized (this) {
                int packetSize = mPacketSize;
                if (packetSize < -1) {
                    // Cannot recycle.
                    break doRecycle;
                }

                if (packetSize >= 0) {
                    int avail = mEnd - mStart;
                    if (avail > 0) {
                        // Quick drain to see if EOF is available.
                        if (drain(in, avail)) {
                            return;
                        }
                    }

                    avail = (mEnd - mStart) + in.available();
                    if (avail > 0) {
                        // Try drain again.
                        if (drain(in, avail)) {
                            return;
                        }
                    }

                    try {
                        executor().execute(new Runnable() {
                            public void run() {
                                drain(in, Integer.MAX_VALUE);
                            }
                        });

                        return;
                    } catch (RejectedException e) {
                        // Cannot recycle.
                        break doRecycle;
                    }
                }

                if ((buffer = mBuffer) == null) {
                    break doRecycle;
                }
                mBuffer = null;
                start = mStart;
                end = mEnd;
            }

            P recycled = newInstance();
            synchronized (recycled) {
                recycled.mIn = in;
                recycled.mBuffer = buffer;
                recycled.mStart = start;
                recycled.mEnd = end;
            }
            recycled(recycled);

            return;
        }

        try {
            in.close();
        } finally {
            // Lazily release buffer (no synchronized access)
            mBuffer = null;
        }
    }

    @Override
    final void inputDisconnect() {
        InputStream in = inUpdater.getAndSet(this, null);
        if (in == null) {
            return;
        }

        try {
            try {
                in.close();
            } catch (IOException e) {
                // Ignore.
            }
        } finally {
            // Lazily release buffer (no synchronized access)
            mBuffer = null;
        }
    }

    /**
     * Return an executor for asynchronously draining unread bytes when
     * stream is closed.
     */
    protected abstract IOExecutor executor();

    /**
     * Return a new instance, which will be used for recycling.
     */
    protected abstract P newInstance();

    /**
     * Called by close method or by a separate thread when stream can be
     * recycled.
     */
    protected abstract void recycled(P newInstance);

    private InputStream in() throws ClosedException {
        InputStream in = mIn;
        if (in == null) {
            throw new ClosedException();
        }
        return in;
    }
}
