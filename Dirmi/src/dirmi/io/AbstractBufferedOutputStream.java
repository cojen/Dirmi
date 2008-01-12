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

package dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractBufferedOutputStream extends OutputStream {
    private final byte[] mBuffer;

    private int mPos;
    
    public AbstractBufferedOutputStream() {
        this(8192);
    }

    public AbstractBufferedOutputStream(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        mBuffer = new byte[size];
    }

    public synchronized void write(int b) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        buffer[pos++] = (byte) b;
        if (pos >= buffer.length) {
            doWrite(buffer, 0, buffer.length);
            mPos = 0;
        } else {
            mPos = pos;
        }
    }

    public synchronized void write(byte[] b, int off, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        int avail = buffer.length - pos;
        if (avail >= len) {
            System.arraycopy(b, off, buffer, pos, len);
            if (avail == len) {
                doWrite(buffer, 0, buffer.length);
                mPos = 0;
            } else {
                mPos = pos + len;
            }
        } else {
            // Fill remainder of buffer and flush it.
            System.arraycopy(b, off, buffer, pos, avail);
            doWrite(buffer, 0, buffer.length);
            off += avail;
            len -= avail;
            if (len < buffer.length) {
                System.arraycopy(b, off, buffer, 0, len);
                mPos = len;
            } else {
                mPos = 0;
                doWrite(b, off, len);
            }
        }
    }

    /**
     * Transfer bytes from a byte array into this BufferedOutputStream. This
     * method is guaranteed to never block. The actual amount transferred might
     * be less than requested amount because the buffer is full. If zero is
     * returned, the buffer is full and drain should be called before another
     * transfer is attempted.
     *
     * @param len amount to transfer
     * @return actual amount transferred
     */
    public synchronized int transfer(byte[] b, int off, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        len = Math.min(buffer.length - pos, len);
        System.arraycopy(b, off, buffer, pos, len);
        return len;
    }

    /**
     * Transfer bytes from an InputStream into this BufferedOutputStream. This
     * method is guaranteed to block only on the InputStream. The actual amount
     * transferred might be less than requested amount because the buffer is
     * full or if the InputStream provided less. If zero is returned, the
     * buffer is full and drain should be called before another transfer is
     * attempted. If EOF is reached, the returned value is negative. Compute
     * the ones' compliment to determine the amount transferred.
     *
     * @param len amount to transfer
     * @return actual amount transferred; is negative if EOF reached.
     */
    public synchronized int transfer(InputStream in, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        len = Math.min(buffer.length - pos, len);
        int total = 0;
        while (len > 0) {
            int amt = in.read(buffer, pos, len);
            if (amt <= 0) {
                return ~total;
            }
            mPos = pos + amt;
            len -= amt;
            total += amt;
        }
        return total;
    }

    /**
     * Fully transfer bytes from an InputStream into this BufferedOutputStream.
     * The actual amount transferred is less than requested amount only if the
     * InputStream EOF is reached. In this case, the returned value is
     * negative. Compute the ones' compliment to determine the amount
     * transferred.
     *
     * @param len amount to transfer
     * @return actual amount transferred; is negative if EOF reached.
     */
    public synchronized int transferFully(InputStream in, int len) throws IOException {
        int total = 0;
        while (len > 0) {
            int amt = transfer(in, len);
            if (amt <= 0) {
                if (amt < 0) {
                    return ~total;
                }
                drain();
            } else {
                len -= amt;
                total += amt;
            }
        }
        return total;
    }

    public synchronized void drain() throws IOException {
        int pos = mPos;
        if (pos != 0) {
            doWrite(mBuffer, 0, pos);
            mPos = 0;
        }
    }

    /**
     * Just calls drain.
     */
    public void flush() throws IOException {
        drain();
    }

    /**
     * Just calls drain.
     */
    public void close() throws IOException {
        drain();
    }

    protected abstract void doWrite(byte[] buffer, int offset, int length) throws IOException;
}
