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

    MultiplexConnection(Multiplexer mux, int id) {
        mMux = mux;
        mId = id;
        mIn = new Input(mux.mInputBufferSize);
        mOut = new Output(mux.mReceiveWindow, mux.mOutputBufferSize);
    }

    public InputStream getInputStream() {
        return mIn;
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    public void close() throws IOException {
        mOut.close();
    }

    void disconnect() throws IOException {
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
            throw new IOException("Connection closed");
        }
    }

    final class Input extends InputStream {
        private final byte[] mBuffer;
        private int mStart;
        private int mAvail;

        Input(int bufferSize) {
            mBuffer = new byte[bufferSize];
        }

        public int read() throws IOException {
            checkClosed();
            byte[] buffer = mBuffer;
            synchronized (buffer) {
                waitForAvail();
                int b = buffer[mStart];
                if (++mStart >= buffer.length) {
                    mStart = 0;
                }
                if (--mAvail < (buffer.length >> 1)) {
                    mMux.receive(mId, mBuffer.length - mAvail);
                }
                return b;
            }
        }

        public int read(byte[] bytes) throws IOException {
            return read(bytes, 0, bytes.length);
        }

        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkClosed();
            if (length <= 0) {
                return 0;
            }
            byte[] buffer = mBuffer;
            synchronized (buffer) {
                waitForAvail();
                if (length > mAvail) {
                    length = mAvail;
                }
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
                if ((mAvail -= length) < (buffer.length >> 1)) {
                    mMux.receive(mId, buffer.length - mAvail);
                }
                return length;
            }
        }

        public long skip(long n) throws IOException {
            checkClosed();
            synchronized (mBuffer) {
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
            }
        }

        public int available() throws IOException {
            checkClosed();
            synchronized (mBuffer) {
                return mAvail;
            }
        }

        public void close() throws IOException {
            MultiplexConnection.this.close();
        }

        void disconnectNotify() {
            synchronized (mBuffer) {
                mBuffer.notifyAll();
            }
        }

        void supply(byte[] bytes, int offset, int length) throws IOException {
            byte[] buffer = mBuffer;
            synchronized (buffer) {
                int capacity = buffer.length - mAvail;
                if (length > capacity) {
                    throw new IOException
                        ("Protocol error: too much data received: " + length + " > " + capacity);
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
                buffer.notify();
            }
        }

        // Caller must be synchronized on mBuffer.
        private void waitForAvail() throws IOException {
            if (mAvail == 0) {
                try {
                    do {
                        mBuffer.wait();
                        checkClosed();
                    } while (mAvail == 0);
                } catch (InterruptedException e) {
                    disconnect();
                    throw new InterruptedIOException();
                }
            }
        }
    }

    final class Output extends OutputStream {
        private int mReceiveWindow;
        private final byte[] mBuffer;
        private int mEnd;

        private boolean mOpened;

        Output(int receiveWindow, int bufferSize) {
            mReceiveWindow = receiveWindow;
            mBuffer = new byte[Multiplexer.SEND_HEADER_SIZE + bufferSize];
            mEnd = Multiplexer.SEND_HEADER_SIZE;
        }

        public synchronized void write(int b) throws IOException {
            checkClosed();
            byte[] buffer = mBuffer;
            if (mEnd >= buffer.length) {
                sendBuffer(false);
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
            while (length > 0) {
                if (avail >= length) {
                    System.arraycopy(bytes, offset, buffer, mEnd, length);
                    mEnd += length;
                    return;
                }
                if (avail > 0) {
                    System.arraycopy(bytes, offset, buffer, mEnd, avail);
                    mEnd += avail;
                    length -= avail;
                }
                sendBuffer(false);
                avail = buffer.length;
            }
        }

        public synchronized void flush() throws IOException {
            checkClosed();
            sendBuffer(true);
        }

        public synchronized void close() throws IOException {
            if (mMux != null) {
                try {
                    sendBuffer(true);
                } finally {
                    disconnect();
                }
            }
        }

        void disconnectNotify() {
            synchronized (mBuffer) {
                mBuffer.notifyAll();
            }
        }

        void updateReceiveWindow(int receiveWindow) {
            synchronized (mBuffer) {
                mReceiveWindow = receiveWindow;
                mBuffer.notify();
            }
        }

        // Caller must be synchronized on this
        private void sendBuffer(boolean flush) throws IOException {
            byte[] buffer = mBuffer;
            int end = mEnd;
            int offset = Multiplexer.SEND_HEADER_SIZE;
            while (offset < end) {
                Multiplexer mux = mMux;
                if (mux == null) {
                    throw new IOException("Connection closed");
                }
                int window;
                try {
                    synchronized (buffer) {
                        while ((window = mReceiveWindow) <= 0) {
                            // Wait on buffer instead of this, to prevent other threads
                            // from writing to this while flush is in progress.
                            buffer.wait();
                            checkClosed();
                        }
                    }
                } catch (InterruptedException e) {
                    disconnect();
                    throw new InterruptedIOException();
                }

                int size = end - offset;
                if (size <= window) {
                    if (flush ||
                        size >= ((buffer.length - (Multiplexer.SEND_HEADER_SIZE - 1)) >> 1))
                    {
                        mux.send(mId, sendOp(), buffer, offset, size);
                        synchronized (buffer) {
                            if ((mReceiveWindow -= size) < 0) {
                                mReceiveWindow = 0;
                            }
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

                mux.send(mId, sendOp(), buffer, offset, window);
                synchronized (buffer) {
                    if ((mReceiveWindow -= window) < 0) {
                        mReceiveWindow = 0;
                    }
                }
                offset += window;
            }

            mEnd = Multiplexer.SEND_HEADER_SIZE;
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
