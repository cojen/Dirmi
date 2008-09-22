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
import java.io.OutputStream;

/**
 * Abstract replacement for {@link java.io.BufferedOutputStream} which does a
 * better job of buffer packing. The intent is to reduce the amount of packets
 * sent over a network.
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
     * Subclass should override this implementation, which just drains the
     * buffer.
     */
    public synchronized void flush() throws IOException {
        int pos = mPos;
        if (pos != 0) {
            doWrite(mBuffer, 0, pos);
            mPos = 0;
        }
    }

    /**
     * Subclass should override this implementation, which just calls flush.
     */
    public void close() throws IOException {
        flush();
    }

    protected abstract void doWrite(byte[] buffer, int offset, int length)
        throws IOException;
}
