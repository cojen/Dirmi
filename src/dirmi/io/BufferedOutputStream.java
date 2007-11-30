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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Replacement for {@link java.io.BufferedOutputStream} which does a better job
 * of buffer packing. The intent is to reduce the amount of packets sent over a
 * network. This implementation is also unsynchronized.
 *
 * @author Brian S O'Neill
 */
public class BufferedOutputStream extends FilterOutputStream {
    private final byte[] mBuffer;

    private int mPos;
    
    public BufferedOutputStream(OutputStream out) {
        this(out, 8192);
    }

    public BufferedOutputStream(OutputStream out, int size) {
        super(out);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        mBuffer = new byte[size];
    }

    public void write(int b) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        buffer[pos++] = (byte) b;
        if (pos >= buffer.length) {
            out.write(buffer);
            mPos = 0;
        } else {
            mPos = pos;
        }
    }

    public void write(byte[] b, int off, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        int avail = buffer.length - pos;
        if (avail >= len) {
            System.arraycopy(b, off, buffer, pos, len);
            if (avail == len) {
                out.write(buffer);
                mPos = 0;
            } else {
                mPos = pos + len;
            }
        } else {
            // Fill remainder of buffer and flush it.
            System.arraycopy(b, off, buffer, pos, avail);
            out.write(buffer);
            off += avail;
            len -= avail;
            if (len < buffer.length) {
                System.arraycopy(b, off, buffer, 0, len);
                mPos = len;
            } else {
                mPos = 0;
                out.write(b, off, len);
            }
        }
    }

    public void flush() throws IOException {
        out.write(mBuffer, 0, mPos);
        mPos = 0;
        out.flush();
    }
}
