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

import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;

/**
 * Buffered OutputStream which writes in packets up to 32895 bytes in
 * length. Each packet is prefixed with a one or two byte length header. When
 * recycled, a zero length packet is written, indicating the end of the logical
 * stream.
 *
 * @author Brian S O'Neill
 */
abstract class PacketOutputStream<P extends PacketOutputStream<P>> extends ChannelOutputStream {
    private static final AtomicReferenceFieldUpdater<PacketOutputStream, OutputStream> outUpdater =
        AtomicReferenceFieldUpdater
        .newUpdater(PacketOutputStream.class, OutputStream.class, "mOut");

    static final int DEFAULT_SIZE = 8192;

    volatile OutputStream mOut;
    byte[] mBuffer;

    int mPos;

    public PacketOutputStream(OutputStream out) {
        this(out, DEFAULT_SIZE);
    }

    public PacketOutputStream(OutputStream out, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer too small: " + size);
        }
        size = Math.min(size, 0x7fff + 0x80);
        mOut = out;
        synchronized (this) {
            mBuffer = new byte[2 + size];
            mPos = 2;
        }
    }

    protected PacketOutputStream() {
    }

    @Override
    public void write(int b) throws IOException {
        try {
            synchronized (this) {
                OutputStream out = out();
                byte[] buffer = mBuffer;
                int pos = mPos;
                buffer[pos++] = (byte) b;
                if (pos >= buffer.length) {
                    doWrite(out, buffer, 2, buffer.length - 2);
                    mPos = 2;
                } else {
                    mPos = pos;
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            synchronized (this) {
                OutputStream out = out();
                byte[] buffer = mBuffer;
                int pos = mPos;
                int avail = buffer.length - pos;
                if (avail >= len) {
                    if (pos == 2 && avail == len && off >= 2) {
                        doWriteAndUndoPrefix(out, b, off, len);
                    } else {
                        System.arraycopy(b, off, buffer, pos, len);
                        if (avail == len) {
                            doWrite(out, buffer, 2, buffer.length - 2);
                            mPos = 2;
                        } else {
                            mPos = pos + len;
                        }
                    }
                } else {
                    // Fill remainder of buffer and flush it.
                    System.arraycopy(b, off, buffer, pos, avail);
                    off += avail;
                    len -= avail;
                    doWrite(out, buffer, 2, avail = buffer.length - 2);
                    if (len >= avail) {
                        if (off < 2) {
                            System.arraycopy(b, off, buffer, 2, avail);
                            off += avail;
                            len -= avail;
                            doWrite(out, buffer, 2, avail);
                        }
                        if (len >= avail) {
                            doWriteAndUndoPrefix(out, b, off, len);
                            mPos = 2;
                            return;
                        }
                    }
                    System.arraycopy(b, off, buffer, 2, len);
                    mPos = 2 + len;
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public synchronized boolean isReady() throws IOException {
        // Always report one less, because final byte written into buffer
        // forces a (potentially) blocking flush.
        out(); // check if closed
        return (mBuffer.length - mPos - 1) > 0;
    }

    /**
     * Sets the size of the buffer, returning the actual size applied.
     */
    @Override
    public synchronized int setBufferSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer too small: " + size);
        }

        byte[] buffer;
        if (mOut == null || (buffer = mBuffer) == null) {
            return 0;
        }

        size = Math.min(size, 0x7fff + 0x80);

        if (size < buffer.length - 2) {
            size = Math.max(size, mPos - 2);
        }
        if (size != buffer.length - 2) {
            byte[] newBuffer = new byte[2 + size];
            System.arraycopy(buffer, 2, newBuffer, 2, mPos - 2);
            mBuffer = newBuffer;
        }

        return size;
    }

    /**
     * Ensures at least one byte can be written, blocking if necessary.
     */
    public void drain() throws IOException {
        try {
            synchronized (this) {
                OutputStream out = out();
                byte[] buffer = mBuffer;
                int pos = mPos;
                int avail = buffer.length - pos - 1;
                if (avail == 0) {
                    doWrite(out, buffer, 2, pos - 2);
                    mPos = 2;
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    void outputNotify(IOExecutor executor, final Channel.Listener listener) {
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        drain();
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
    public void flush() throws IOException {
        try {
            synchronized (this) {
                OutputStream out = mOut;
                if (out == null) {
                    return;
                }
                byte[] buffer = mBuffer;
                int pos = mPos;
                if (pos <= buffer.length) {
                    if (pos > 2) {
                        doWrite(out, buffer, 2, pos - 2);
                        mPos = 2;
                    }
                    out.flush();
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        outputClose();
    }

    @Override
    public void disconnect() {
        outputDisconnect();
    }

    @Override
    public synchronized boolean outputSuspend() throws IOException {
        OutputStream out = mOut;
        if (out == null) {
            return false;
        }
        writeSuspendMarker(out, mBuffer, false);
        return true;
    }

    @Override
    final void outputClose() throws IOException {
        OutputStream out = outUpdater.getAndSet(this, null);
        if (out == null) {
            return;
        }

        byte[] buffer;
        synchronized (this) {
            buffer = mBuffer;
            mBuffer = null;
            try {
                writeSuspendMarker(out, buffer, true);
            } catch (IOException e) {
                outputDisconnect(out);
                throw e;
            }
        }

        P recycled = newInstance();
        synchronized (recycled) {
            recycled.mOut = out;
            recycled.mBuffer = buffer;
            recycled.mPos = 2;
        }
        recycled(recycled);
    }

    @Override
    final void outputDisconnect() {
        outputDisconnect(outUpdater.getAndSet(this, null));
    }

    private void outputDisconnect(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            try {
                out.close();
            } catch (IOException e) {
                // Ignore.
            }
        } finally {
            // Lazily release buffer (no synchronized access)
            mBuffer = null;
        }
    }

    /**
     * Return a new instance, which will be used for recycling.
     */
    protected abstract P newInstance();

    /**
     * Called by close method when stream can be recycled.
     */
    protected abstract void recycled(P newInstance);

    private void writeSuspendMarker(OutputStream out, byte[] buffer, boolean forClose)
        throws IOException
    {
        int pos = mPos;
        if (pos >= buffer.length) {
            // No room in buffer for empty packet header.
            doWrite(out, buffer, 2, pos - 2);
            out.write(0);
            out.flush();
            mPos = 2;
        } else {
            int length = pos - 2;
            if (length <= 0) {
                // Write empty packet header all by itself.
                try {
                    out.write(0);
                    out.flush();
                } catch (IOException e) {
                    if (!forClose) {
                        throw e;
                    }
                    // No need to throw to caller since there was no user data
                    // to flush. Stream cannot be recycled, so return.
                    outputDisconnect(out);
                    return;
                }
            } else {
                // Append the empty packet header.
                buffer[pos] = 0;

                if (length < 0x80) {
                    buffer[1] = (byte) length;
                    pos = 1;
                    length += 2;
                } else {
                    buffer[1] = (byte) (length - 0x80);
                    buffer[0] = (byte) (((length - 0x80) >> 8) | 0x80);
                    pos = 0;
                    length += 3;
                }

                out.write(buffer, pos, length);
                out.flush();
                mPos = 2;
            }
        }
    }

    /**
     * @param offset must be at least 2
     */
    private void doWrite(OutputStream out,
                         byte[] buffer, int offset, int length)
        throws IOException
    {
        if (length < 0x80) {
            buffer[--offset] = (byte) length;
            length++;
        } else {
            buffer[--offset] = (byte) (length - 0x80);
            buffer[--offset] = (byte) (((length - 0x80) >> 8) | 0x80);
            length += 2;
        }

        out.write(buffer, offset, length);
    }

    /**
     * @param offset must be at least 2
     */
    private void doWriteAndUndoPrefix(OutputStream out,
                                      byte[] buffer, int offset, int length)
        throws IOException
    {
        if (length < 0x80) {
            byte original = buffer[--offset];
            buffer[offset] = (byte) length;
            try {
                out.write(buffer, offset, length + 1);
            } finally {
                buffer[offset] = original;
            }
        } else {
            byte original_1 = buffer[--offset];
            byte original_0 = buffer[--offset];
            buffer[offset + 1] = (byte) (length - 0x80);
            buffer[offset] = (byte) (((length - 0x80) >> 8) | 0x80);
            try {
                out.write(buffer, offset, length + 2);
            } finally {
                buffer[offset + 1] = original_1;
                buffer[offset] = original_0;
            }
        }
    }

    private OutputStream out() throws ClosedException {
        OutputStream out = mOut;
        if (out == null) {
            throw new ClosedException();
        }
        return out;
    }
}
