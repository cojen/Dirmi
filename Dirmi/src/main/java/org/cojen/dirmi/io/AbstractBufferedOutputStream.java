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

import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstract replacement for {@link java.io.BufferedOutputStream} which does a
 * better job of buffer packing. The intent is to reduce the amount of packets
 * sent over a network.
 *
 * @author Brian S O'Neill
 */
abstract class AbstractBufferedOutputStream extends OutputStream {
    static final int DEFAULT_SIZE = 8192;

    private final byte[] mBuffer;
    private final int mStart;
    private final int mEnd;

    private int mPos;
    
    public AbstractBufferedOutputStream() {
        this(0, DEFAULT_SIZE, 0);
    }

    public AbstractBufferedOutputStream(int prefix, int size, int suffix) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        if (prefix < 0) {
            prefix = 0;
        }
        if (suffix < 0) {
            suffix = 0;
        }
        mBuffer = new byte[prefix + size + suffix];
        mStart = prefix;
        mEnd = prefix + size;
        synchronized (this) {
            mPos = prefix;
        }
    }

    // Copy constructor.
    AbstractBufferedOutputStream(AbstractBufferedOutputStream out) {
        synchronized (out) {
            mBuffer = out.mBuffer;
            mStart = out.mStart;
            mEnd = out.mEnd;
            synchronized (this) {
                mPos = out.mPos;
            }
        }
    }

    public synchronized void write(int b) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        buffer[pos++] = (byte) b;
        if (pos >= mEnd) {
            doFlush(buffer, mStart, mEnd - mStart);
            mPos = mStart;
        } else {
            mPos = pos;
        }
    }

    public synchronized void write(byte[] b, int off, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        int avail = mEnd - pos;
        if (avail >= len) {
            System.arraycopy(b, off, buffer, pos, len);
            if (avail == len) {
                doFlush(buffer, mStart, mEnd - mStart);
                mPos = mStart;
            } else {
                mPos = pos + len;
            }
        } else {
            // Fill remainder of buffer and flush it.
            System.arraycopy(b, off, buffer, pos, avail);
            off += avail;
            len -= avail;
            doFlush(buffer, mStart, avail = mEnd - mStart);
            while (len >= avail) {
                System.arraycopy(b, off, buffer, mStart, avail);
                off += avail;
                len -= avail;
                doFlush(buffer, mStart, avail);
            }
            System.arraycopy(b, off, buffer, mStart, len);
            mPos = mStart + len;
        }
    }

    public final void flush() throws IOException {
        flush(false);
    }

    /**
     * Subclass should override this implementation, which just drains the
     * buffer.
     */
    protected synchronized boolean flush(boolean force) throws IOException {
        int pos = mPos;
        if (force || pos > mStart) {
            doFlush(mBuffer, mStart, pos - mStart);
            mPos = mStart;
            return true;
        }
        return false;
    }

    /**
     * Subclass should override this implementation, which just calls flush.
     */
    public void close() throws IOException {
        flush();
    }

    /**
     * Called to actually write the contents of the buffer.
     *
     * @param buffer mutable buffer, guaranteed to have room for prefix and suffix
     * @param offset offset into buffer
     * @param length always more than zero if called from public flush method
     */
    protected abstract void doFlush(byte[] buffer, int offset, int length) throws IOException;
}
