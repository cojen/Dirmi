/*
 *  Copyright 2007 Brian S O'Neill
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Replacement for {@link java.io.BufferedInputStream} which does not have the
 * block forever on read bug. Marking is not supported, and this implementation
 * is also unsynchronized.
 *
 * @author Brian S O'Neill
 */
public class BufferedInputStream extends FilterInputStream {
    private final byte[] mBuffer;

    private int mStart;
    private int mEnd;

    public BufferedInputStream(InputStream in) {
        this(in, 8192);
    }

    public BufferedInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        mBuffer = new byte[size];
    }

    public int read() throws IOException {
        int start = mStart;
        if (mEnd - start > 0) {
            mStart = start + 1;
            return mBuffer[start] & 0xff;
        } else {
            int amt = in.read(mBuffer);
            if (amt <= 0) {
                return -1;
            }
            mStart = 1;
            mEnd = amt;
            return mBuffer[0] & 0xff;
        }
    }

    public int read(byte[] b, int off, int len) throws IOException {
        int start = mStart;
        int avail = mEnd - start;
        if (avail >= len) {
            System.arraycopy(mBuffer, start, b, off, len);
            mStart = start + len;
            return len;
        } else {
            System.arraycopy(mBuffer, start, b, off, avail);
            mStart = 0;
            mEnd = 0;
            off += avail;
            len -= avail;
            if (len >= mBuffer.length) {
                int amt = in.read(b, off, len);
                amt = (amt <= 0 ? 0 : amt) + avail;
                return amt <= 0 ? -1 : amt;
            } else {
                int amt = in.read(mBuffer);
                if (amt <= 0) {
                    return avail <= 0 ? -1 : avail;
                } else {
                    int fill = Math.min(amt, len);
                    System.arraycopy(mBuffer, 0, b, off, fill);
                    mStart = fill;
                    mEnd = amt;
                    return fill + avail;
                }
            }
        }
    }

    public long skip(long n) throws IOException {
        if (n <= 0) {
            return in.skip(n); // checks if closed
        } else {
            int avail = mEnd - mStart;
            if (avail <= 0) {
                return skip(n);
            } else {
                long skipped = (avail < n) ? avail : n;
                mStart += skipped;
                return skipped;
            }
        }
    }

    public int available() throws IOException {
        return mEnd - mStart;
    }

    public void close() throws IOException {
        mStart = 0;
        mEnd = 0;
        in.close();
    }

    public void mark(int readlimit) {
    }

    public void reset() throws IOException {
        throw new IOException("unsupported");
    }

    public boolean markSupported() {
        return false;
    }
}
