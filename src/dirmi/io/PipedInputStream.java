/*
 *  Copyright 2006 Brian S O'Neill
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

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unbuffered replacement for {@link java.io.PipedInputStream}. This piped
 * stream does not have the flaws found in the java.io implementation. It
 * allows multiple threads to read from it without interfering with the
 * stream's state. Also it can't get into a one-second polling mode.
 *
 * @author Brian S O'Neill
 * @see PipedOutputStream
 */
public class PipedInputStream extends InputStream {
    private final Lock mLock;

    private PipedOutputStream mPout;

    public PipedInputStream() {
        mLock = new ReentrantLock();
    }

    public PipedInputStream(PipedOutputStream pout) throws IOException {
        mLock = pout.setInput(this);
        mPout = pout;
    }

    public int read() throws IOException {
        mLock.lock();
        try {
            return mPout.read();
        } catch (NullPointerException e) {
            if (mPout == null) {
                throw new IOException("Not connected");
            }
            throw e;
        } finally {
            mLock.unlock();
        }
    }

    public int read(byte[] bytes) throws IOException {
        return read(bytes, 0, bytes.length);
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            return mPout.read(bytes, offset, length);
        } catch (NullPointerException e) {
            if (mPout == null) {
                throw new IOException("Not connected");
            }
            throw e;
        } finally {
            mLock.unlock();
        }
    }

    public long skip(long n) throws IOException {
        mLock.lock();
        try {
            return mPout.skip(n);
        } catch (NullPointerException e) {
            if (mPout == null) {
                throw new IOException("Not connected");
            }
            throw e;
        } finally {
            mLock.unlock();
        }
    }

    public int available() throws IOException {
        mLock.lock();
        try {
            return mPout.available();
        } catch (NullPointerException e) {
            if (mPout == null) {
                throw new IOException("Not connected");
            }
            throw e;
        } finally {
            mLock.unlock();
        }
    }

    public void close() throws IOException {
        mLock.lock();
        try {
            if (mPout != null) {
                PipedOutputStream pout = mPout;
                mPout = null;
                pout.close();
            }
        } finally {
            mLock.unlock();
        }
    }

    public String toString() {
        String superStr = superToString();

        mLock.lock();
        try {
            if (mPout == null) {
                return superStr.concat(" (unconnected)");
            } else {
                return superStr + " connected to " + mPout.superToString();
            }
        } finally {
            mLock.unlock();
        }
    }

    String superToString() {
        return super.toString();
    }

    Lock setOutput(PipedOutputStream pout) throws IOException {
        mLock.lock();
        try {
            if (mPout != null) {
                throw new IOException("Already connected");
            }
            mPout = pout;
        } finally {
            mLock.unlock();
        }
        return mLock;
    }
}
