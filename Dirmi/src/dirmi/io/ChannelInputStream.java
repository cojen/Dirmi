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
import java.io.InterruptedIOException;
import java.io.IOException;

/**
 * Buffered input stream used with {@link ChannelOutputStream}. If stream has
 * been reset by remote endpoint, EOF is returned until this local stream is
 * also reset. If wrapped stream returns EOF, IOExceptions are thrown.
 *
 * @author Brian S O'Neill
 */
class ChannelInputStream extends BufferedInputStream {
    private int mAvail;
    private int mResetCount;

    public ChannelInputStream(InputStream in) {
        super(in);
    }

    public ChannelInputStream(InputStream in, int size) {
        super(in, size);
    }

    @Override
    public synchronized int read() throws IOException {
        int avail = mAvail;
        if (avail <= 0 && (avail = readNext()) <= 0) {
            return -1;
        }
        int b = super.read();
        if (b < 0) {
            throw new IOException("Closed");
        }
        mAvail = avail - 1;
        return b;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        int avail = mAvail;
        if (avail <= 0 && (avail = readNext()) <= 0) {
            return -1;
        }
        if (len >= avail) {
            len = avail;
        }
        int amt = super.read(b, off, len);
        if (amt <= 0) {
            if (len <= 0) {
                return amt;
            } else {
                throw new IOException("Closed");
            }
        }
        mAvail = avail - amt;
        return amt;
    }

    @Override
    public synchronized long skip(long n) throws IOException {
        int avail = mAvail;
        if (n > avail) {
            n = avail;
        }
        n = super.skip(n);
        mAvail = avail - (int) n;
        return n;
    }

    @Override
    public synchronized int available() throws IOException {
        int avail = mAvail;
        if (avail <= 0) {
            avail = super.available();
            if (avail < ChannelOutputStream.START_POS) {
                // Not enough available to safely read next packet.
                return 0;
            }
            return readNext();
        }
        return avail;
    }

    /**
     * Clears buffered data and allows stream to be reused for another
     * command. Recycle must also be called on remote {@link
     * ChannelOutputStream} before this method returns.
     */
    public synchronized void recycle() throws IOException {
        int resetCount = ++mResetCount;
        if (resetCount > 0) {
            int avail = mAvail;
            do {
                while (avail > 0) {
                    avail -= super.skip(avail);
                }
                if ((avail = readNext()) <= 0) {
                    throw new IOException("Closed");
                }
            } while (mResetCount > 0);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        mAvail = 0;
        mResetCount = 0;
        super.close();
    }

    /**
     * @return packet size; zero if reset remotely
     */
    // Caller must be synchronized.
    // Precondition: mAvail must be <= 0.
    private int readNext() throws IOException {
        if (mResetCount < 0) {
            // Don't read any more until local stream has been reset.
            return 0;
        }

        int avail;
        while (true) {
            int b = super.read();
            if (b <= 0) {
                if (b == 0) {
                    mResetCount--;
                    // Loop back and read more.
                } else {
                    throw new IOException("Closed");
                }
            } else if (b < 128) {
                avail = b;
                break;
            } else {
                avail = ((b & 0x7f) << 8) + 128;
                b = super.read();
                if (b < 0) {
                    throw new IOException("Closed");
                }
                avail += b;
                break;
            }
        }

        mAvail = avail;

        if (mResetCount < 0) {
            // Don't read any more until local stream has been reset.
            return 0;
        }

        return avail;
    }
}
