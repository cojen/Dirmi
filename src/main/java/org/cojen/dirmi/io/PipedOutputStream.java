/*
 *  Copyright 2006-2022 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.io;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Objects;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.cojen.dirmi.ClosedException;

/**
 * Unbuffered replacement for {@link java.io.PipedOutputStream}. This piped stream does not
 * have the flaws found in the java.io implementation. It allows multiple threads to write to
 * it without interfering with the stream's state, and a thread reading from the stream can't
 * get into a one-second polling mode.
 *
 * @author Brian S O'Neill
 * @see PipedInputStream
 */
public final class PipedOutputStream extends OutputStream {
    private final Lock mLock;
    private final Condition mReadCondition;
    private final Condition mWriteCondition;

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
        Objects.checkFromIndexSize(offset, length, bytes.length);

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
            if (mData != null) while (true) {
                mWriteCondition.await();
                if (mData == null) {
                    break;
                }
                checkConnected();
            }
        } catch (InterruptedException e) {
            mData = null;
            throw new InterruptedIOException("Thread interrupted");
        }
    }

    public boolean isClosed() {
        mLock.lock();
        try {
            return mPin == null && mEverConnected;
        } finally {
            mLock.unlock();
        }
    }

    public void close() {
        mLock.lock();
        try {
            if (mPin != null) {
                PipedInputStream pin = mPin;
                mPin = null;
                pin.outputClosed();
                mReadCondition.signalAll();
                mWriteCondition.signalAll();
            }
        } finally {
            mLock.unlock();
        }
    }

    @Override
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
        Objects.checkFromIndexSize(offset, length, bytes.length);

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
            while (mData == null) {
                mReadCondition.await();
                checkConnected();
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Thread interrupted");
        }
    }

    // Caller must hold mLock.
    int inputAvailable() {
        return (mData == null) ? 0 : mLength;
    }

    Lock setInput(PipedInputStream pin) throws IOException {
        mLock.lock();
        try {
            if (mPin != null) {
                throw new IOException("Already connected");
            }
            if (mEverConnected) {
                throw new ClosedException();
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
                throw new ClosedException();
            }
            throw new IOException("Not connected");
        }
    }
}
