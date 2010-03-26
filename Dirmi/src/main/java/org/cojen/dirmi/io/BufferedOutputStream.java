/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;

/**
 * Replacement for {@link java.io.BufferedOutputStream} which does a better job
 * of buffer packing. The intent is to reduce the amount of packets sent over a
 * network. Any exception thrown by the underlying stream causes it to be
 * automatically closed. When the stream is closed, all write operations throw
 * an IOException.
 *
 * @author Brian S O'Neill
 */
public class BufferedOutputStream extends ChannelOutputStream {
    static final int DEFAULT_SIZE = 8192;

    private final OutputStream mOut;
    private byte[] mBuffer;

    private int mPos;

    private volatile boolean mWriting;

    public BufferedOutputStream(OutputStream out) {
        this(out, DEFAULT_SIZE);
    }

    public BufferedOutputStream(OutputStream out, int size) {
        mOut = out;
        synchronized (this) {
            mBuffer = new byte[size];
        }
    }

    @Override
    public void write(int b) throws IOException {
        try {
            synchronized (this) {
                byte[] buffer = buffer();
                int pos = mPos;
                buffer[pos++] = (byte) b;
                if (pos >= buffer.length) {
                    doWrite(buffer, 0, buffer.length);
                    mPos = 0;
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
                byte[] buffer = buffer();
                int pos = mPos;
                int avail = buffer.length - pos;
                if (avail >= len) {
                    if (pos == 0 && avail == len) {
                        doWrite(b, off, len);
                    } else {
                        System.arraycopy(b, off, buffer, pos, len);
                        if (avail == len) {
                            doWrite(buffer, 0, buffer.length);
                            mPos = 0;
                        } else {
                            mPos = pos + len;
                        }
                    }
                } else {
                    // Fill remainder of buffer and flush it.
                    System.arraycopy(b, off, buffer, pos, avail);
                    off += avail;
                    len -= avail;
                    doWrite(buffer, 0, avail = buffer.length);
                    if (len >= avail) {
                        doWrite(b, off, len);
                        mPos = 0;
                    } else {
                        System.arraycopy(b, off, buffer, 0, len);
                        mPos = len;
                    }
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
        return (buffer().length - mPos - 1) > 0;
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

        if (size < buffer.length) {
            size = Math.max(size, mPos);
        }
        if (size != buffer.length) {
            byte[] newBuffer = new byte[size];
            System.arraycopy(buffer, 0, newBuffer, 0, mPos);
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
                byte[] buffer = buffer();
                int pos = mPos;
                int avail = buffer.length - pos - 1;
                if (avail == 0) {
                    doWrite(buffer, 0, pos);
                    mPos = 0;
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
                byte[] buffer = mBuffer;
                if (buffer == null) {
                    return;
                }
                int pos = mPos;
                if (pos <= buffer.length) {
                    if (pos > 0) {
                        doWrite(buffer, 0, pos);
                        mPos = 0;
                    }
                    mWriting = true;
                    try {
                        mOut.flush();
                    } finally {
                        mWriting = false;
                    }
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
    public boolean outputSuspend() throws IOException {
        flush();
        return false;
    }

    @Override
    final void outputClose() throws IOException {
        // If closing via another thread, don't block on flush.
        close(!mWriting);
    }

    @Override
    final void outputDisconnect() {
        try {
            close(false);
        } catch (IOException e2) {
            // Ignore.
        }
    }

    private void close(boolean flush) throws IOException {
        try {
            if (flush) {
                synchronized (this) {
                    if (mBuffer == null) {
                        return;
                    }
                    flush();
                }
            }

            try {
                mOut.close();
            } catch (IOException e) {
                synchronized (this) {
                    if (mBuffer != null) {
                        throw e;
                    }
                }
            }
        } finally {
            synchronized (this) {
                mBuffer = null;
                mWriting = false;
            }
        }
    }

    private void doWrite(byte[] buffer, int offset, int length) throws IOException {
        mWriting = true;
        try {
            mOut.write(buffer, offset, length);
        } finally {
            mWriting = false;
        }
    }

    private byte[] buffer() throws ClosedException {
        byte[] buffer = mBuffer;
        if (buffer == null) {
            throw new ClosedException();
        }
        return buffer;
    }
}
