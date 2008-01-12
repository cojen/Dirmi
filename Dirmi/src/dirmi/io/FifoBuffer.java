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

import java.io.Closeable;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe buffer for reading and writing to.
 *
 * @author Brian S O'Neill
 */
public class FifoBuffer implements Closeable {
    private final Lock mLock;
    private final Condition mReadCondition;
    private final Condition mWriteCondition;

    private final byte[] mBuffer;
    private int mStart;
    private int mSize;

    // 0: not closed
    // 1: closed stage one -- read returns 0
    // 2: closed stage two -- read throws IOException
    private int mClosedState;

    public FifoBuffer() {
        this(8192);
    }

    public FifoBuffer(int size) {
        mLock = new ReentrantLock();
        mReadCondition = mLock.newCondition();
        mWriteCondition = mLock.newCondition();
        mBuffer = new byte[size];
    }

    /**
     * Non-blocking read.
     *
     * @return actual amount read; is less than requested read length only if
     * buffer is empty
     */
    public int read(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            if (length <= 0) {
                if (length < 0) {
                    throw new IndexOutOfBoundsException();
                }
                return 0;
            }

            int size = mSize;
            if (size <= 0) {
                return 0;
            }

            int amt = length = Math.min(size, length);

            byte[] buffer = mBuffer;
            int start = mStart;
            if (start + amt > buffer.length) {
                int chunk = buffer.length - start;
                System.arraycopy(buffer, start, bytes, offset, chunk);
                mStart = start = 0;
                mSize = size -= chunk;
                offset += chunk;
                amt -= chunk;
            }

            System.arraycopy(buffer, start, bytes, offset, amt);

            if ((start += amt) >= buffer.length) {
                start -= buffer.length;
            }
            mStart = start;

            mSize = size - amt;
            mWriteCondition.signal();

            return length;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Blocking read which reads at least one byte.
     *
     * @return actual amount read; is less than requested read length only if
     * buffer is empty
     */
    public int readAny(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            int amount = read(bytes, offset, length);
            if (amount > 0 || length <= 0) {
                return amount;
            }
            if (waitForRead(false) <= 0) {
                return 0;
            }
            return read(bytes, offset, length);
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Blocking read. This method does not completely prevent other threads
     * from reading from the buffer.
     */
    public void readFully(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            while (true) {
                int amount = read(bytes, offset, length);
                if (amount > 0) {
                    if ((length -= amount) <= 0) {
                        return;
                    }
                    offset += amount;
                } else if (length <= 0) {
                    return;
                }
                waitForRead(true);
            }
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Read and transfer bytes to an OutputStream. This method is guaranteed
     * to block only on the OutputStream. The actual amount transferred might
     * be less than requested amount because the buffer is empty.
     *
     * @param length amount to transfer
     * @return actual amount transferred
     */
    public int readTransfer(OutputStream out, int length) throws IOException {
        mLock.lock();
        try {
            if (length <= 0) {
                if (length < 0) {
                    throw new IndexOutOfBoundsException();
                }
                return 0;
            }

            int size = mSize;
            if (size <= 0) {
                return 0;
            }

            int amt = length = Math.min(size, length);

            byte[] buffer = mBuffer;
            int start = mStart;
            if (start + amt > buffer.length) {
                int chunk = buffer.length - start;
                out.write(buffer, start, chunk);
                mStart = start = 0;
                mSize = size -= chunk;
                amt -= chunk;
            }

            out.write(buffer, start, amt);

            if ((start += amt) >= buffer.length) {
                start -= buffer.length;
            }
            mStart = start;

            mSize = size - amt;
            mWriteCondition.signal();

            return length;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Non-blocking read skip.
     *
     * @return actual amount skipped; is less than requested skip amount only
     * if buffer is empty
     */
    public int readSkip(int n) throws IOException {
        mLock.lock();
        try {
            if (n <= 0) {
                return 0;
            }

            int size = mSize;
            if (size <= 0) {
                return 0;
            }

            if (n >= size) {
                mSize = 0;
                return size;
            }

            int start = mStart + (int) n;
            if (start >= mBuffer.length) {
                start -= mBuffer.length;
            }

            mStart = start;

            mSize = size - n;
            mWriteCondition.signal();

            return size;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Blocking read skip which skips at least one byte.
     *
     * @return actual amount skipped; is less than requested skip amount only
     * if buffer is empty
     */
    public int readSkipAny(int n) throws IOException {
        mLock.lock();
        try {
            int amount = readSkip(n);
            if (amount > 0 || n <= 0) {
                return amount;
            }
            if (waitForRead(false) <= 0) {
                return 0;
            }
            return readSkip(n);
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Returns amount that can be read without blocking.
     */
    public int readAvailable() {
        mLock.lock();
        try {
            return mSize;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Wait until at least one byte can be read.
     */
    public int waitForReadAvailable() throws IOException {
        mLock.lock();
        try {
            return waitForRead(true);
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    private int waitForRead(boolean strict) throws IOException {
        try {
            while (true) {
                int avail;
                if ((avail = mSize) > 0) {
                    return avail;
                }
                if (checkClosed(strict)) {
                    return 0;
                }
                mWriteCondition.signal();
                mReadCondition.await();
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    /**
     * Signal writer that reader is requesting data. Signal is automatic when
     * buffer is empty.
     */
    public void readFill() throws IOException {
        mLock.lock();
        try {
            mWriteCondition.signal();
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Non-blocking write.
     *
     * @return actual amount written; is less than requested write length only
     * if buffer is full
     */
    public int write(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            if (length <= 0) {
                if (length < 0) {
                    throw new IndexOutOfBoundsException();
                }
                return 0;
            }

            byte[] buffer = mBuffer;

            int size = mSize;
            if (size >= buffer.length) {
                return 0;
            }

            int amt = length = Math.min(buffer.length - size, length);
            int end = mStart + size;

            if (end >= buffer.length) {
                end -= buffer.length;
            } else if (end + amt > buffer.length) {
                int chunk = buffer.length - end;
                System.arraycopy(bytes, offset, buffer, end, chunk);
                mSize = size += chunk;
                offset += chunk;
                end = 0;
                amt -= chunk;
            }

            System.arraycopy(bytes, offset, buffer, end, amt);

            mSize = size + amt;
            mReadCondition.signal();

            return length;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Blocking write which writes at least one byte.
     *
     * @return actual amount written; is less than requested write length only
     * if buffer is full
     */
    public int writeAny(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            int amount = write(bytes, offset, length);
            if (amount > 0 || length <= 0) {
                return amount;
            }
            waitForWrite();
            return write(bytes, offset, length);
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Blocking write. This method does not completely prevent other threads
     * from writing into the buffer.
     */
    public void writeFully(byte[] bytes, int offset, int length) throws IOException {
        mLock.lock();
        try {
            while (true) {
                int amount = write(bytes, offset, length);
                if (amount > 0) {
                    if ((length -= amount) <= 0) {
                        return;
                    }
                    offset += amount;
                } else if (length <= 0) {
                    return;
                }
                waitForWrite();
            }
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Transfer and write bytes from an InputStream. This method is guaranteed
     * to block only on the InputStream. The actual amount transferred might be
     * less than requested amount because the buffer is full or if the
     * InputStream provided less. If EOF is reached, the returned value is
     * negative. Compute the ones' compliment to determine the amount
     * transferred.
     *
     * @param length amount to transfer
     * @return actual amount transferred; is negative if EOF reached.
     */
    public int writeTransfer(InputStream in, int length) throws IOException {
        mLock.lock();
        try {
            if (length <= 0) {
                if (length < 0) {
                    throw new IndexOutOfBoundsException();
                }
                return 0;
            }

            byte[] buffer = mBuffer;

            int size = mSize;
            if (size >= buffer.length) {
                return 0;
            }

            int total = 0;
            int amt = Math.min(buffer.length - size, length);
            int end = mStart + size;

            if (end >= buffer.length) {
                end -= buffer.length;
            } else if (end + amt > buffer.length) {
                int chunk = buffer.length - end;
                int chunkAmt = in.read(buffer, end, chunk);
                if (chunkAmt <= 0) {
                    return -1;
                }
                if (chunkAmt < chunk) {
                    return chunkAmt;
                }
                total += chunk;
                mSize = size += chunk;
                end = 0;
                amt -= chunk;
            }

            amt = in.read(buffer, end, amt);
            if (amt <= 0) {
                return ~total;
            }
            total += amt;

            mSize = size + amt;
            mReadCondition.signal();

            return total;
        } finally {
            mLock.unlock();
        }
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (len > 0) {
            int amt = in.read(b, off, len);
            if (amt <= 0) {
                return ~total;
            } else {
                off += amt;
                len -= amt;
                total += amt;
            }
        }
        return total;
    }

    /**
     * Returns amount that can be written without blocking.
     */
    public int writeAvailable() {
        mLock.lock();
        try {
            return mBuffer.length - mSize;
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Wait until at least one byte can be written.
     */
    public int waitForWriteAvailable() throws IOException {
        mLock.lock();
        try {
            return waitForWrite();
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    private int waitForWrite() throws IOException {
        try {
            while (true) {
                checkClosed(true);
                int avail;
                if ((avail = (mBuffer.length - mSize)) > 0) {
                    return avail;
                }
                mReadCondition.signal();
                mWriteCondition.await();
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    /**
     * Signal reader that writer has supplied data. Signal is automatic when
     * buffer is full.
     */
    public void writeFlush() throws IOException {
        mLock.lock();
        try {
            mReadCondition.signal();
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Closing the buffer only affects blocking calls. The first blocking read
     * call made on a closed buffer returns zero, but subsequent calls throw an
     * IOException.
     */
    public void close() {
        mLock.lock();
        try {
            mReadCondition.signalAll();
            mWriteCondition.signalAll();
            switch (mClosedState) {
            case 0:
                mClosedState = 1;
            case 1: default:
                mClosedState = 2;
            }
        } finally {
            mLock.unlock();
        }
    }

    // Caller must hold mLock.
    private boolean checkClosed(boolean strict) throws IOException {
        if (mClosedState > 0) {
            mClosedState = 2;
            if (strict) {
                throw new IOException("Closed");
            }
            return true;
        }
        return false;
    }
}
