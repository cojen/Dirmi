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
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connection implementation of {@link MultiplexConnection}.
 *
 * @author Brian S O'Neill
 */
final class MultiplexConnection implements Connection {
    volatile Multiplexer mMux;
    final int mId;

    final Input mIn;
    final Output mOut;

    final String mLocalAddress;
    final String mRemoteAddress;

    MultiplexConnection(Multiplexer mux, int id, boolean opened,
                        String localAddress, String remoteAddress)
    {
        mMux = mux;
        mId = id;
        mIn = new Input(mux.mMinBufferSize);
        mOut = new Output(mux.mReceiveWindow, mux.mMinBufferSize, mux.mMaxBufferSize, opened);
        mLocalAddress = localAddress;
        mRemoteAddress = remoteAddress;
    }

    public InputStream getInputStream() {
        return mIn;
    }

    public int getReadTimeout() throws IOException {
        return mIn.getReadTimeout();
    }

    public void setReadTimeout(int timeoutMillis) throws IOException {
        mIn.setReadTimeout(timeoutMillis);
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    public int getWriteTimeout() throws IOException {
        return mOut.getWriteTimeout();
    }

    public void setWriteTimeout(int timeoutMillis) throws IOException {
        mOut.setWriteTimeout(timeoutMillis);
    }

    public String getLocalAddressString() {
        return mLocalAddress;
    }

    public String getRemoteAddressString() {
        return mRemoteAddress;
    }

    private String appendId(String address) {
        if (address == null) {
            return String.valueOf(mId);
        } else {
            return address + ':' + mId;
        }
    }

    public void close() throws IOException {
        mOut.close();
    }

    public String toString() {
        return super.toString() + " (id=" + mId + ')';
    }

    /**
     * Disconnects without notifying remote peer.
     */
    void disconnect() {
        Multiplexer mux = mMux;
        mMux = null;
        mIn.disconnectNotify();
        mOut.disconnectNotify();
        if (mux != null) {
            mux.unregister(MultiplexConnection.this);
        }
    }

    void checkClosed() throws IOException {
        if (mMux == null) {
            throw new IOException("Connection closed (id=" + mId + ')');
        }
    }

    final class Input extends InputStream implements ReadTimeout {
        private final Lock mLock;
        private final Condition mCondition;

        private long mTimeoutNanos = -1000000; // -1 millis

        private byte[] mBuffer;
        private int mStart;
        private int mAvail;

        private int mWindowConsumed;

        Input(int initialBufferSize) {
            mLock = new ReentrantLock();
            mCondition = mLock.newCondition();
            mBuffer = new byte[initialBufferSize];
        }

        public int getReadTimeout() throws IOException {
            mLock.lock();
            try {
                return (int) TimeUnit.NANOSECONDS.toMillis(mTimeoutNanos);
            } finally {
                mLock.unlock();
            }
        }

        public void setReadTimeout(int timeoutMillis) throws IOException {
            mLock.lock();
            try {
                mTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            } finally {
                mLock.unlock();
            }
        }

        public int read() throws IOException {
            int b;
            int received;
            mLock.lock();
            try {
                waitForAvail();
                byte[] buffer = mBuffer;
                b = buffer[mStart];
                if (++mStart >= buffer.length) {
                    mStart = 0;
                }
                --mAvail;
                if ((received = ++mWindowConsumed) < (buffer.length >> 1)) {
                    received = 0;
                } else {
                    mWindowConsumed = 0;
                }
            } finally {
                mLock.unlock();
            }
            // Write received outside of lock section to avoid deadlock.
            if (received > 0) {
                Multiplexer mux;
                if ((mux = mMux) != null) {
                    mux.receive(mId, received);
                }
            }
            return b & 0xff;
        }

        public int read(byte[] bytes) throws IOException {
            return read(bytes, 0, bytes.length);
        }

        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            int received;
            mLock.lock();
            try {
                waitForAvail();
                if (length > mAvail) {
                    length = mAvail;
                }
                byte[] buffer = mBuffer;
                int firstLength = buffer.length - mStart;
                if (firstLength >= length) {
                    System.arraycopy(buffer, mStart, bytes, offset, length);
                } else {
                    System.arraycopy(buffer, mStart, bytes, offset, firstLength);
                    System.arraycopy(buffer, 0, bytes, offset + firstLength, length - firstLength);
                }
                if ((mStart += length) >= buffer.length) {
                    mStart -= buffer.length;
                }
                mAvail -= length;
                if ((received = (mWindowConsumed += length)) < (buffer.length >> 1)) {
                    received = 0;
                } else {
                    mWindowConsumed = 0;
                }
            } finally {
                mLock.unlock();
            }
            // Write received outside of lock section to avoid deadlock.
            if (received > 0) {
                Multiplexer mux;
                if ((mux = mMux) != null) {
                    mux.receive(mId, received);
                }
            }
            return length;
        }

        public long skip(long n) throws IOException {
            mLock.lock();
            try {
                long total = 0;
                while (n > 0) {
                    waitForAvail();
                    if (mAvail > n) {
                        total += n;
                        mStart += n;
                        mAvail -= n;
                        if (mStart >= mBuffer.length) {
                            mStart -= mBuffer.length;
                        }
                        break;
                    }
                    total += mAvail;
                    n -= mAvail;
                    mStart = 0;
                    mAvail = 0;
                }
                return total;
            } finally {
                mLock.unlock();
            }
        }

        public int available() throws IOException {
            if (mMux == null) {
                return 0;
            }
            mLock.lock();
            try {
                return mAvail;
            } finally {
                mLock.unlock();
            }
        }

        public void close() throws IOException {
            MultiplexConnection.this.close();
        }

        void disconnectNotify() {
            mLock.lock();
            try {
                mCondition.signalAll();
            } finally {
                mLock.unlock();
            }
        }

        void supply(byte[] bytes, int offset, int length) throws IOException {
            mLock.lock();
            try {
                byte[] buffer = mBuffer;
                if (length > (buffer.length - mAvail)) {
                    // Expand capacity.
                    int newLength = buffer.length;
                    do {
                        newLength <<= 1;
                    } while (length > (newLength - mAvail));
                    byte[] newBuffer = new byte[newLength];
                    int end = mStart + mAvail;
                    if (end <= buffer.length) {
                        System.arraycopy(buffer, mStart, newBuffer, 0, mAvail);
                    } else {
                        System.arraycopy(buffer, mStart, newBuffer, 0, buffer.length - mStart);
                        System.arraycopy(buffer, 0, newBuffer, buffer.length - mStart,
                                         end - buffer.length);
                    }
                    mBuffer = buffer = newBuffer;
                    mStart = 0;
                }
                int end = mStart + mAvail;
                if (end > buffer.length) {
                    end -= buffer.length;
                }
                int firstLength = buffer.length - end;
                if (firstLength >= length) {
                    System.arraycopy(bytes, offset, buffer, end, length);
                } else {
                    System.arraycopy(bytes, offset, buffer, end, firstLength);
                    System.arraycopy(bytes, offset + firstLength, buffer, 0, length - firstLength);
                }
                mAvail += length;
                mCondition.signal();
            } finally {
                mLock.unlock();
            }
        }

        // Caller must hold mLock.
        private void waitForAvail() throws IOException {
            if (mAvail == 0) {
                checkClosed();
                try {
                    long timeoutNanos = mTimeoutNanos;
                    while (true) {
                        if (timeoutNanos < 0) {
                            mCondition.await();
                        } else if ((timeoutNanos = mCondition.awaitNanos(timeoutNanos)) < 0) {
                            timeoutNanos = mTimeoutNanos - timeoutNanos;
                            throw new InterruptedIOException
                                ("Timed out after " +
                                 TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms");
                        }
                        if (mAvail == 0) {
                            checkClosed();
                        } else {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("Thread interrupted");
                }
            }
        }
    }

    final class Output extends OutputStream implements WriteTimeout {
        private static final int SEND_NO_FLUSH = 0, SEND_AND_FLUSH = 1, SEND_AND_CLOSE = 2;

        private final Lock mReceiveWindowLock;
        private final Condition mReceiveWindowCondition;
        private int mReceiveWindow;

        private long mTimeoutNanos = -1000000; // -1 millis

        private byte[] mBuffer;
        private final int mMaxBufferSize;
        private int mEnd;
        private boolean mOpened;

        Output(int receiveWindow, int initialBufferSize, int maxBufferSize, boolean opened) {
            mReceiveWindowLock = new ReentrantLock();
            mReceiveWindowCondition = mReceiveWindowLock.newCondition();
            mReceiveWindow = receiveWindow;
            mBuffer = new byte[Multiplexer.SEND_HEADER_SIZE + initialBufferSize];
            mMaxBufferSize = Multiplexer.SEND_HEADER_SIZE + maxBufferSize;
            mEnd = Multiplexer.SEND_HEADER_SIZE;
            mOpened = opened;
        }

        public synchronized int getWriteTimeout() throws IOException {
            return (int) TimeUnit.NANOSECONDS.toMillis(mTimeoutNanos);
        }

        public synchronized void setWriteTimeout(int timeoutMillis) throws IOException {
            mTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        public synchronized void write(int b) throws IOException {
            checkClosed();
            byte[] buffer = mBuffer;
            if (mEnd >= buffer.length) {
                if (buffer.length >= mMaxBufferSize) {
                    sendBuffer(SEND_NO_FLUSH);
                } else {
                    int newLength = ((buffer.length - Multiplexer.SEND_HEADER_SIZE) << 1)
                        + Multiplexer.SEND_HEADER_SIZE;
                    if (newLength > mMaxBufferSize) {
                        newLength = mMaxBufferSize;
                    }
                    byte[] newBuffer = new byte[newLength];
                    System.arraycopy(buffer, Multiplexer.SEND_HEADER_SIZE,
                                     newBuffer, Multiplexer.SEND_HEADER_SIZE,
                                     mEnd - Multiplexer.SEND_HEADER_SIZE);
                    buffer = mBuffer = newBuffer;
                }
            }
            buffer[mEnd++] = (byte) b;
        }

        public void write(byte[] bytes) throws IOException {
            write(bytes, 0, bytes.length);
        }

        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            checkClosed();
            byte[] buffer = mBuffer;
            int avail = buffer.length - mEnd;

            if (avail < length && buffer.length < mMaxBufferSize) {
                int newLength = buffer.length;
                do {
                    newLength = ((newLength - Multiplexer.SEND_HEADER_SIZE) << 1)
                        + Multiplexer.SEND_HEADER_SIZE;
                } while ((newLength - mEnd) < length);
                if (newLength > mMaxBufferSize) {
                    newLength = mMaxBufferSize;
                }
                byte[] newBuffer = new byte[newLength];
                System.arraycopy(buffer, Multiplexer.SEND_HEADER_SIZE,
                                 newBuffer, Multiplexer.SEND_HEADER_SIZE,
                                 mEnd - Multiplexer.SEND_HEADER_SIZE);
                buffer = mBuffer = newBuffer;
                avail = newLength - mEnd;
            }

            final int originalOffset = offset;

            while (length > 0) {
                if (avail >= length) {
                    System.arraycopy(bytes, offset, buffer, mEnd, length);
                    mEnd += length;
                    return;
                }

                if (avail > 0) {
                    System.arraycopy(bytes, offset, buffer, mEnd, avail);
                    mEnd += avail;
                    offset += avail;
                    length -= avail;
                }

                try {
                    sendBuffer(SEND_NO_FLUSH);
                } catch (InterruptedIOException e) {
                    e.bytesTransferred = offset - originalOffset;
                    throw e;
                }

                avail = buffer.length - mEnd;
            }
        }

        public synchronized void flush() throws IOException {
            checkClosed();
            sendBuffer(SEND_AND_FLUSH);
        }

        public synchronized void close() throws IOException {
            if (mMux != null) {
                try {
                    sendBuffer(SEND_AND_CLOSE);
                } catch (IOException e) {
                    if (mMux != null) {
                        throw e;
                    }
                } finally {
                    disconnect();
                }
            }
        }

        void disconnectNotify() {
            mReceiveWindowLock.lock();
            try {
                mReceiveWindowCondition.signalAll();
            } finally {
                mReceiveWindowLock.unlock();
            }
        }

        void updateReceiveWindow(int receiveWindow) {
            mReceiveWindowLock.lock();
            try {
                mReceiveWindow += receiveWindow;
                mReceiveWindowCondition.signal();
            } finally {
                mReceiveWindowLock.unlock();
            }
        }

        // Caller must be synchronized on this
        private void sendBuffer(int sendMode) throws IOException {
            byte[] buffer = mBuffer;
            int end = mEnd;
            int offset = Multiplexer.SEND_HEADER_SIZE;
            while (offset < end) {
                Multiplexer mux = mMux;
                if (mux == null) {
                    throw new IOException("Connection closed (id=" + mId + ')');
                }
                int window;

                mReceiveWindowLock.lock();
                try {
                    long timeoutNanos = mTimeoutNanos;
                    while ((window = mReceiveWindow) <= 0) {
                        // Wait on mReceiveWindowLock instead of this, to
                        // prevent other threads from writing to this while
                        // flush is in progress.
                        if (timeoutNanos < 0) {
                            mReceiveWindowCondition.await();
                        } else if ((timeoutNanos =
                                    mReceiveWindowCondition.awaitNanos(timeoutNanos)) < 0) {
                            timeoutNanos = mTimeoutNanos - timeoutNanos;
                            throw new InterruptedIOException
                                ("Timed out after " +
                                 TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms");
                        }
                        checkClosed();
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("Thread interrupted");
                } finally {
                    mReceiveWindowLock.unlock();
                }

                int size = end - offset;
                if (size <= window) {
                    if (sendMode != SEND_NO_FLUSH ||
                        size >= ((buffer.length - (Multiplexer.SEND_HEADER_SIZE - 1)) >> 1))
                    {
                        mux.send(mId, sendOp(), buffer, offset, size, sendMode == SEND_AND_CLOSE);
                        mReceiveWindowLock.lock();
                        try {
                            mReceiveWindow -= size;
                        } finally {
                            mReceiveWindowLock.unlock();
                        }
                        mEnd = Multiplexer.SEND_HEADER_SIZE;
                    } else {
                        // Save for later, hoping to fill window.
                        System.arraycopy
                            (buffer, offset, buffer, Multiplexer.SEND_HEADER_SIZE, size);
                        mEnd = Multiplexer.SEND_HEADER_SIZE + size;
                    }
                    return;
                }

                mux.send(mId, sendOp(), buffer, offset, window, false);
                mReceiveWindowLock.lock();
                try {
                    mReceiveWindow -= window;
                } finally {
                    mReceiveWindowLock.unlock();
                }
                offset += window;
            }

            mEnd = Multiplexer.SEND_HEADER_SIZE;

            // This point is reached if nothing was actually sent. If
            // connection is supposed to be closed, be sure to do so.
            if (sendMode == SEND_AND_CLOSE) {
                Multiplexer mux = mMux;
                if (mux != null) {
                    mux.send(mId, sendOp(), buffer, Multiplexer.SEND_HEADER_SIZE, 0, true);
                }
            }
        }

        // Caller must be synchronized on this
        private int sendOp() {
            if (mOpened) {
                return Multiplexer.SEND;
            }
            mOpened = true;
            return Multiplexer.OPEN;
        }
    }
}
