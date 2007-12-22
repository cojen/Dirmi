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

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unbuffered replacement for {@link java.io.PipedOutputStream}. This piped
 * stream does not have the flaws found in the java.io implementation. It
 * allows multiple threads to write to it without interfering with the stream's
 * state. Also, a thread reading from the stream can't get into a one-second
 * polling mode.
 *
 * <p>PipedOutputStream supports timeouts, and if an InterruptedIOException is
 * thrown, the stream is still valid. The amount of bytes transferred before
 * timing out is always zero. That is, a write operation which times out is
 * never partially successful.
 *
 * @author Brian S O'Neill
 * @see PipedInputStream
 */
public class PipedOutputStream extends OutputStream implements WriteTimeout {
    private final Lock mLock;
    private final Condition mReadCondition;
    private final Condition mWriteCondition;

    private long mReadTimeoutNanos  = -1000000; // -1 millis
    private long mWriteTimeoutNanos = -1000000; // -1 millis

    private PipedInputStream mPin;
    private boolean mEverConnected;

    private byte[] mData;
    private int mOffset;
    private int mLength;

    private byte[] mTinyBuf;

    public PipedOutputStream() {
        mLock = new ReentrantLock();
        mReadCondition = mLock.newCondition();
        mWriteCondition = mLock.newCondition();
    }

    public PipedOutputStream(PipedInputStream pin) throws IOException {
        mLock = pin.setOutput(this);
        mReadCondition = mLock.newCondition();
        mWriteCondition = mLock.newCondition();
        setInput(pin);
    }

    public long getWriteTimeout() throws IOException {
        mLock.lock();
        try {
            checkConnected();
            return mWriteTimeoutNanos;
        } finally {
            mLock.unlock();
        }
    }

    public TimeUnit getWriteTimeoutUnit() throws IOException {
        return TimeUnit.NANOSECONDS;
    }

    public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
        mLock.lock();
        try {
            checkConnected();
            mWriteTimeoutNanos = unit.toNanos(time);
        } finally {
            mLock.unlock();
        }
    }

    public void write(int b) throws IOException {
        mLock.lock();
        try {
            checkConnected();

            byte[] bytes = mTinyBuf;
            if (bytes == null) {
                mTinyBuf = bytes = new byte[1];
            }
            bytes[0] = (byte) b;
            mData = bytes;
            mOffset = 0;
            mLength = 1;

            mReadCondition.signal();

            waitForWriteCompletion();
        } finally {
            mLock.unlock();
        }
    }

    public void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    public void write(byte[] bytes, int offset, int length) throws IOException {
        if ((offset < 0) || (offset > bytes.length) || (length < 0) ||
            ((offset + length) > bytes.length) || ((offset + length) < 0))
        {
            throw new IndexOutOfBoundsException();
        }
        if (length <= 0) {
            return;
        }

        mLock.lock();
        try {
            checkConnected();

            mData = bytes;
            mOffset = offset;
            mLength = length;

            mReadCondition.signal();

            waitForWriteCompletion();
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    private void waitForWriteCompletion() throws IOException {
        try {
            long timeoutNanos = mWriteTimeoutNanos;
            while (mData != null) {
                if (timeoutNanos < 0) {
                    mWriteCondition.await();
                } else if ((timeoutNanos = mWriteCondition.awaitNanos(timeoutNanos)) < 0) {
                    mData = null;
                    timeoutNanos = mWriteTimeoutNanos - timeoutNanos;
                    throw new InterruptedIOException
                        ("Timed out after " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms");
                }
            }
        } catch (InterruptedException e) {
            mData = null;
            throw new InterruptedIOException("Thread interrupted");
        }
    }

    public void close() throws IOException {
        mLock.lock();
        try {
            if (mPin != null) {
                PipedInputStream pin = mPin;
                mPin = null;
                pin.close();
            }
        } finally {
            mLock.unlock();
        }
    }

    public String toString() {
        String superStr = superToString();

        mLock.lock();
        try {
            if (mPin == null) {
                return superStr.concat(" (unconnected)");
            } else {
                return superStr + " connected to " + mPin.superToString();
            }
        } finally {
            mLock.unlock();
        }
    }

    String superToString() {
        return super.toString();
    }

    // Caller must hold mLock.
    long getReadTimeout() {
        return (int) mReadTimeoutNanos;
    }

    TimeUnit getReadTimeoutUnit() {
        return TimeUnit.NANOSECONDS;
    }

    // Caller must hold mLock.
    void setReadTimeout(long time, TimeUnit unit) {
        mReadTimeoutNanos = unit.toNanos(time);
    }

    // Caller must hold mLock.
    int read() throws IOException {
        waitForReadAvailable();

        int offset = mOffset;
        int b = mData[offset] & 0xff;
        if (--mLength <= 0) {
            mData = null;
            mWriteCondition.signal();
        } else {
            mOffset = offset + 1;
        }

        return b;
    }

    // Caller must hold mLock.
    int read(byte[] bytes, int offset, int length) throws IOException {
        if ((offset < 0) || (offset > bytes.length) || (length < 0) ||
            ((offset + length) > bytes.length) || ((offset + length) < 0))
        {
            throw new IndexOutOfBoundsException();
        }

        int amt = 0;
        
        waitForReadAvailable();
        
        if (length >= mLength) {
            length = mLength;
        }

        System.arraycopy(mData, mOffset, bytes, offset, length);
        amt += length;
        if ((mLength -= length) <= 0) {
            mData = null;
            mWriteCondition.signal();
        } else {
            mOffset += length;
        }

        return amt;
    }

    // Caller must hold mLock.
    long skip(long n) throws IOException {
        long amt = 0;

        while (n > 0) {
            waitForReadAvailable();

            if (n <= mLength) {
                amt += n;
                if ((mLength -= n) <= 0) {
                    mData = null;
                    mWriteCondition.signal();
                } else {
                    mOffset += n;
                }
                break;
            }

            amt += mLength;
            n -= mLength;
            mData = null;
            mWriteCondition.signal();
        }

        return amt;
    }

    // Caller must hold mLock.
    private void waitForReadAvailable() throws IOException {
        try {
            long timeoutNanos = mReadTimeoutNanos;
            while (mData == null) {
                if (timeoutNanos < 0) {
                    mReadCondition.await();
                } else if ((timeoutNanos = mReadCondition.awaitNanos(timeoutNanos)) < 0) {
                    timeoutNanos = mReadTimeoutNanos - timeoutNanos;
                    throw new InterruptedIOException
                        ("Timed out after " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms");
                }
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Thread interrupted");
        }
    }

    // Caller must hold mLock.
    int available() throws IOException {
        return (mData == null) ? 0 : mLength;
    }

    Lock setInput(PipedInputStream pin) throws IOException {
        mLock.lock();
        try {
            if (mPin != null) {
                throw new IOException("Already connected");
            }
            if (mEverConnected) {
                throw new IOException("Closed");
            }
            mPin = pin;
            mEverConnected = true;
        } finally {
            mLock.unlock();
        }
        return mLock;
    }

    // Caller must hold mLock.
    private void checkConnected() throws IOException {
        if (mPin == null) {
            if (mEverConnected) {
                throw new IOException("Closed");
            }
            throw new IOException("Not connected");
        }
    }
}
