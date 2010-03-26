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

import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RejectedException;

/**
 * Replacement for {@link java.io.BufferedInputStream}. Marking is not
 * supported and any exception thrown by the underlying stream causes it to be
 * automatically closed. When the stream is closed, all read operations throw
 * an IOException.
 *
 * @author Brian S O'Neill
 */
public class BufferedInputStream extends ChannelInputStream {
    static final int DEFAULT_SIZE = 8192;

    private final InputStream mIn;
    private byte[] mBuffer;

    private int mStart;
    private int mEnd;

    public BufferedInputStream(InputStream in) {
        this(in, DEFAULT_SIZE);
    }

    public BufferedInputStream(InputStream in, int size) {
        mIn = in;
        synchronized (this) {
            mBuffer = new byte[size];
        }
    }

    @Override
    public int read() throws IOException {
        try {
            synchronized (this) {
                byte[] buffer = buffer();
                int start = mStart;
                if (mEnd - start > 0) {
                    mStart = start + 1;
                    return buffer[start] & 0xff;
                } else {
                    int amt = mIn.read(buffer, 0, buffer.length);
                    if (amt <= 0) {
                        return -1;
                    } else {
                        mStart = 1;
                        mEnd = amt;
                        return buffer[0] & 0xff;
                    }
                }
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            synchronized (this) {
                byte[] buffer = buffer();
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
                        if (avail > 0 && (len = Math.min(len, mIn.available())) <= 0) {
                            return avail;
                        }
                        if (len >= buffer.length) {
                            int amt = mIn.read(b, off, len);
                            return amt <= 0 ? (avail <= 0 ? -1 : avail) : (avail + amt);
                        } else {
                            int amt = mIn.read(buffer, 0, buffer.length);
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
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        try {
            synchronized (this) {
                // Check if closed.
                buffer();

                if (n > 0) {
                    int avail = mEnd - mStart;
                    if (avail > 0) {
                        long skipped = (avail < n) ? avail : n;
                        mStart += skipped;
                        return skipped;
                    }
                }

                return mIn.skip(n);
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public int available() throws IOException {
        try {
            synchronized (this) {
                // Check if closed.
                buffer();
                return (mEnd - mStart) + mIn.available();
            }
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    @Override
    public boolean isReady() throws IOException {
        try {
            synchronized (this) {
                // Check if closed.
                buffer();
                return (mEnd - mStart) > 0 || mIn.available() > 0;
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
    public void fill() throws IOException {
        try {
            synchronized (this) {
                byte[] buffer = buffer();
                int avail = mEnd - mStart;
                if (avail == 0) {
                    int amt = mIn.read(buffer, 0, buffer.length);
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
    public boolean inputResume() {
        return false;
    }

    @Override
    public boolean isResumeSupported() {
        return false;
    }

    @Override
    final void inputClose() throws IOException {
        try {
            try {
                mIn.close();
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
            }
        }
    }

    @Override
    final void inputDisconnect() {
        try {
            inputClose();
        } catch (IOException e) {
            // Ignore.
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
