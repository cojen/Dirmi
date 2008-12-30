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
 * Buffered output stream used with {@link ChannelInputStream}.
 *
 * @author Brian S O'Neill
 */
class ChannelOutputStream extends OutputStream {
    // Maximum of 3 bytes required to hold optional reset command plus 1 or 2
    // bytes to encode packet length.
    static final int START_POS = 3;
    static final int MAX_SIZE = 32768 + 127;

    private static final int STATE_OPEN = 0;
    private static final int STATE_RESET = 1;
    private static final int STATE_CLOSED = 2;

    private final OutputStream mOut;
    private final byte[] mBuffer;

    private int mPos;
    private int mState;

    public ChannelOutputStream(OutputStream out) {
        this(out, 8192);
    }

    public ChannelOutputStream(OutputStream out, int size) {
        if (out == null || size <= 0) {
            throw new IllegalArgumentException();
        }
        if (size <= START_POS) {
            size += START_POS;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
        mOut = out;
        mBuffer = new byte[size];
        synchronized (this) {
            mPos = START_POS;
        }
    }

    @Override
    public synchronized void write(int b) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        try {
            buffer[pos++] = (byte) b;
        } catch (ArrayIndexOutOfBoundsException e) {
            if (mState == STATE_CLOSED) {
                throw new IOException("Closed");
            }
            throw e;
        }
        if (pos >= buffer.length) {
            doFlush(buffer, buffer.length - START_POS);
        } else {
            mPos = pos;
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mPos;
        int avail = buffer.length - pos;
        if (avail >= len) {
            System.arraycopy(b, off, buffer, pos, len);
            if (avail == len) {
                doFlush(buffer, buffer.length - START_POS);
            } else {
                mPos = pos + len;
            }
        } else {
            // Fill remainder of buffer and flush it.
            System.arraycopy(b, off, buffer, pos, avail);
            off += avail;
            len -= avail;
            doFlush(buffer, avail = buffer.length - START_POS);
            while (len >= avail) {
                System.arraycopy(b, off, buffer, START_POS, avail);
                off += avail;
                len -= avail;
                doFlush(buffer, avail);
            }
            System.arraycopy(b, off, buffer, START_POS, len);
            mPos = START_POS + len;
        }
    }

    /**
     * Clears buffered data and allows stream to be reused for another
     * command. This stream must be flushed before recycle command is actually
     * delivered. Remote {@link ChannelInputStream} returns EOF until recycle
     * is explicitly called on it too.
     */
    public synchronized void recycle() throws IOException {
        if (mState == STATE_CLOSED) {
            throw new IOException("Closed");
        }
        mPos = START_POS;
        mState = STATE_RESET;
    }

    @Override
    public synchronized void flush() throws IOException {
        try {
            int pos = mPos;
            if (pos > START_POS) {
                doFlush(mBuffer, pos - START_POS);
            } else if (mState == STATE_RESET) {
                mOut.write(0);
                mState = STATE_OPEN;
            }
            mOut.flush();
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        close(true);
    }

    // Caller must be synchronized.
    private void close(boolean flush) throws IOException {
        if (flush && mState != STATE_CLOSED) {
            try {
                flush();
            } catch (IOException e) {
                disconnect();
                throw e;
            }
        }
        // Move pos to end to force next write to flush, which in turn causes
        // an exception to be thrown.
        mPos = mBuffer.length;
        mState = STATE_CLOSED;
        mOut.close();
    }

    // Caller must be synchronized.
    private void disconnect() {
        try {
            close(false);
        } catch (IOException e) {
            // Ignore.
        }
    }

    /**
     * @param length must be at least 1 and less than or equal to MAX_SIZE
     */
    // Caller must be synchronized.
    private void doFlush(byte[] buffer, int length) throws IOException {
        int state = mState;
        if (state == STATE_CLOSED) {
            throw new IOException("Closed");
        }
        try {
            int offset;
            if (length < 128) {
                buffer[offset = START_POS - 1] = (byte) length;
                length++;
            } else {
                buffer[offset = START_POS - 2] = (byte) (0x80 | ((length - 128) >> 8));
                buffer[START_POS - 1] = (byte) (length - 128);
                length += 2;
            }
            if (state == STATE_RESET) {
                buffer[--offset] = 0;
                length++;
            }
            mOut.write(buffer, offset, length);
            mPos = START_POS;
            mState = STATE_OPEN;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }
}
