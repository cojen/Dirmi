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

package dirmi.nio2;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.security.SecureRandom;

import org.cojen.util.IntHashMap;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MultiplexedStreamBroker implements StreamBroker {
    private static final long MAGIC_NUMBER = 0x1dca09fe04baafbeL;

    // Each command is encoded with 4 byte header. Upper 2 bits encode the
    // opcode, and remaining 30 bits encode the connection id.

    static final int SEND  = 0; // all bits must be clear
    static final int ACK   = 1;
    static final int CLOSE = 3; // all bits must be set

    final MessageConnection mMessCon;

    final IntHashMap<Con> mConnections;
    final ReadWriteLock mConnectionsLock;

    final BufferPool mBufferPool;

    final BlockingQueue<StreamListener> mListeners;

    final AtomicInteger mNextId;

    public MultiplexedStreamBroker(MessageConnection con) throws IOException {
        if (con == null) {
            throw new IllegalArgumentException();
        }
        mMessCon = con;

        mConnections = new IntHashMap<Con>();
        mConnectionsLock = new ReentrantReadWriteLock();

        mBufferPool = new BufferPool();

        mListeners = new LinkedBlockingQueue<StreamListener>();

        // Receives magic number and random number.
        class Bootstrap implements MessageReceiver {
            private volatile byte[] mMessage;

            private boolean mReceived;
            private boolean mClosed;
            private IOException mClosedException;

            Bootstrap() {
            }

            public MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
                byte[] message;
                if (offset == 0) {
                    mMessage = message = new byte[totalSize];
                } else {
                    message = mMessage;
                }
                buffer.get(message, offset, buffer.remaining());
                return null;
            }

            public synchronized void process() {
                mReceived = true;
                notifyAll();
            }

            public synchronized void closed() {
                mClosed = true;
                process();
            }

            public synchronized void closed(IOException e) {
                mClosed = true;
                mClosedException = e;
                process();
            }

            public synchronized long getNumber() throws IOException {
                while (!mReceived) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }

                if (mClosed) {
                    if (mClosedException == null) {
                        throw new IOException("Connection closed");
                    }
                    throw mClosedException;
                }

                ByteBuffer buffer = ByteBuffer.wrap(mMessage);

                long magic = buffer.getLong();
                if (magic != MAGIC_NUMBER) {
                    throw new IOException("Unknown magic number: " + magic);
                }

                return buffer.getLong();
            }
        };

        Bootstrap bootstrap = new Bootstrap();
        con.receive(bootstrap);

        // Write magic number, followed by random number.
        long ourRnd = new SecureRandom().nextLong();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(MAGIC_NUMBER);
        buffer.putLong(ourRnd);
        buffer.flip();
        con.send(buffer);

        long theirRnd = bootstrap.getNumber();

        // The random numbers determine whether our connection ids are even or odd.
            
        if (ourRnd == theirRnd) {
            // What are the odds?
            throw new IOException("Negotiation failure");
        }

        mNextId = new AtomicInteger(ourRnd < theirRnd ? 0 : 1);

        con.receive(new Receiver());
    }

    public void accept(StreamListener listener) {
        mListeners.add(listener);
    }

    /**
     * Returned connection is not established until first written to.
     */
    public StreamConnection connect() {
        while (true) {
            int id = mNextId.getAndAdd(2) & 0x3fffffff;
            Con con = new Con(id);
            Lock consWriteLock = mConnectionsLock.writeLock();
            consWriteLock.lock();
            try {
                if (!mConnections.containsKey(id)) {
                    mConnections.put(id, con);
                    return con;
                }
            } finally {
                consWriteLock.unlock();
            }
        }
    }

    void sendMessage(ByteBuffer buffer) throws IOException {
        // FIXME: If IOException, close all connections.
        mMessCon.send(buffer);
    }

    private class Receiver implements MessageReceiver {
        private int mHeader;
        private ByteBuffer mReceived;

        Receiver() {
        }

        public synchronized MessageReceiver receive(int totalSize, int offset, ByteBuffer buffer) {
            MessageReceiver newReceiver;
            if (offset >= 4) {
                newReceiver = null;
            } else {
                readHeader: {
                    if (offset == 0) {
                        if (totalSize > 4) {
                            mReceived = mBufferPool.get(totalSize - 4);
                        }
                        newReceiver = new Receiver();
                        if (buffer.remaining() >= 4) {
                            mHeader = buffer.getInt();
                            break readHeader;
                        }
                    } else {
                        newReceiver = null;
                    }

                    int header = mHeader;
                    do {
                        header = (header << 8) | (buffer.get() & 0xff);
                        offset++;
                    } while (offset < 4 && buffer.remaining() > 0);

                    mHeader = header;
                }
            }

            if (mReceived != null) {
                mReceived.put(buffer);
            }

            return newReceiver;
        }

        public synchronized void process() {
            Con con;
            boolean newCon;
            int command;

            {
                int id = mHeader & 0x3fffffff;
                command = mHeader >>> 30;
                Lock consReadLock = mConnectionsLock.readLock();
                consReadLock.lock();
                try {
                    con = mConnections.get(id);
                } finally {
                    consReadLock.unlock();
                }
                if (con != null) {
                    newCon = false;
                } else {
                    Lock consWriteLock = mConnectionsLock.writeLock();
                    consWriteLock.lock();
                    try {
                        if ((con = mConnections.get(id)) != null) {
                            newCon = false;
                        } else {
                            con = new Con(id);
                            mConnections.put(con.getId(), con);
                            newCon = true;
                        }
                    } finally {
                        consWriteLock.unlock();
                    }
                }
            }

            con.received(mReceived);

            if (command == ACK) {
                con.acknowledged();
            } else if (command == CLOSE) {
                try {
                    con.close(true);
                } catch (IOException e) {
                    // Ignore.
                }
            }

            if (newCon) {
                try {
                    // FIXME: configurable timeout
                    StreamListener listener = mListeners.poll(10, TimeUnit.SECONDS);
                    if (listener != null) {
                        listener.established(con);
                        return;
                    }
                } catch (InterruptedException e) {
                }

                // Not accepted in time, so close it.
                try {
                    con.close(true);
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }

        public void closed() {
            // FIXME: Close all connections; drain all StreamListeners.
            System.out.println("Closed in Receiver");
        }

        public void closed(IOException e) {
            // FIXME: Close all connections; drain and notify all StreamListeners.
            System.out.println("Closed in Receiver");
            e.printStackTrace(System.out);
        }
    }

    private class Con implements StreamConnection {
        private static final int HEADER_SIZE = 4;

        final int mId;

        private final OutputStream mOut;
        private final ByteBuffer mOutBuffer;
        private boolean mOutBlocked;

        private final InputStream mIn;
        private final ConcurrentLinkedQueue<ByteBuffer> mInBuffers;

        private boolean mClosed;

        private Object mLocalAddress;
        private Object mRemoteAddress;

        Con(int id) {
            mId = id;

            mOut = new Out();
            mOutBuffer = mBufferPool.get(Math.min(8192, mMessCon.getMaximumMessageSize()));
            mOutBuffer.putInt(id);

            mIn = new In();
            mInBuffers = new ConcurrentLinkedQueue<ByteBuffer>();
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public synchronized Object getLocalAddress() {
            if (mLocalAddress == null) {
                mLocalAddress = new Object() {
                    @Override
                    public String toString() {
                        return String.valueOf(mMessCon.getLocalAddress()) + '@' + mId;
                    }
                };
            }
            return mLocalAddress;
        }

        public synchronized Object getRemoteAddress() {
            if (mRemoteAddress == null) {
                mRemoteAddress = new Object() {
                    @Override
                    public String toString() {
                        return String.valueOf(mMessCon.getRemoteAddress()) + '@' + mId;
                    }
                };
            }
            return mRemoteAddress;
        }

        public void close() throws IOException {
            close(false);
        }

        void close(boolean force) throws IOException {
            synchronized (mOut) {
                synchronized (mIn) {
                    if (!mClosed) {
                        if (!force) {
                            mOutBlocked = false;
                            ByteBuffer buffer = mOutBuffer;
                            buffer.put(0, (byte) ((CLOSE << 6) | buffer.get(0)));
                            flush(buffer);
                        }
                        mClosed = true;
                        mConnections.remove(mId);
                        mBufferPool.yield(mOutBuffer);
                    }
                    mIn.notifyAll();
                    mOut.notifyAll();
                }
            }
        }

        @Override
        public String toString() {
            return "StreamConnection {localAddress=" + getLocalAddress() +
                ", remoteAddress=" + getRemoteAddress() + '}';
        }

        int getId() {
            return mId;
        }

        // Caller must synchronize on mOut.
        void write(int b) throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (!buffer.hasRemaining()) {
                flush(buffer);
            }
            buffer.put((byte) b);
        }

        // Caller must synchronize on mOut.
        void write(byte[] b, int off, int len) throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (len > 0) {
                while (true) {
                    int remaining = buffer.remaining();
                    if (remaining <= 0) {
                        flush(buffer);
                        remaining = buffer.remaining();
                    }
                    int amt = Math.min(remaining, len);
                    buffer.put(b, off, amt);
                    if ((len -= amt) <= 0) {
                        break;
                    }
                    off += amt;
                }
            }
        }

        // Caller must synchronize on mOut.
        void flush() throws IOException {
            checkClosed();
            ByteBuffer buffer = mOutBuffer;
            if (buffer.position() <= HEADER_SIZE) {
                return;
            }
            flush(buffer);
        }

        // Caller must synchronize on mOut.
        private void flush(ByteBuffer buffer) throws IOException {
            while (mOutBlocked) {
                checkClosed();
                try {
                    mOut.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                checkClosed();
                if (buffer.position() <= HEADER_SIZE) {
                    return;
                }
            }
            buffer.flip();
            try {
                sendMessage(buffer);
                mOutBlocked = true;
            } finally {
                buffer.position(HEADER_SIZE).limit(buffer.capacity());
            }
        }

        void acknowledged() {
            synchronized (mOut) {
                if (mOutBlocked) {
                    mOutBlocked = false;
                    if (!mClosed) {
                        try {
                            flush();
                        } catch (IOException e) {
                            // Ignore and assume all connections are now closed.
                        }
                    }
                    mOut.notifyAll();
                }
            }
        }

        void received(ByteBuffer buffer) {
            if (buffer != null) {
                buffer.flip();
                mInBuffers.add(buffer);
                synchronized (mIn) {
                    mIn.notify();
                }
                synchronized (mOut) {
                    if (!mClosed) {
                        // Send acknowledgment.
                        buffer = mOutBuffer;
                        int header = buffer.get(0) & 0x3f;
                        buffer.put(0, (byte) ((ACK << 6) | header));
                        int originalPos = buffer.position();
                        buffer.position(0).limit(4);
                        try {
                            sendMessage(buffer);
                        } catch (IOException e) {
                            // Ignore and assume all connections are now closed.
                        } finally {
                            buffer.position(originalPos).limit(buffer.capacity());
                        }
                    }
                }
            }
        }

        // Caller must synchronize on mIn.
        int read() throws IOException {
            ByteBuffer buffer = getReadBuffer();
            byte b = buffer.get();
            if (!buffer.hasRemaining()) {
                mInBuffers.poll();
                mBufferPool.yield(buffer);
            }
            return b & 0xff;
        }

        // Caller must synchronize on mIn.
        int read(byte[] b, int off, int len) throws IOException {
            ByteBuffer buffer = getReadBuffer();
            len = Math.min(len, buffer.remaining());
            buffer.get(b, off, len);
            if (!buffer.hasRemaining()) {
                mInBuffers.poll();
                mBufferPool.yield(buffer);
            }
            return len;
        }

        private ByteBuffer getReadBuffer() throws IOException {
            ByteBuffer buffer;
            while ((buffer = mInBuffers.peek()) == null) {
                checkClosed();
                try {
                    mIn.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
            return buffer;
        }

        // Caller must synchronize on mOut or mIn.
        private void checkClosed() throws IOException {
            if (mClosed) {
                throw new IOException("Closed");
            }
        }

        private class Out extends OutputStream {
            Out() {
            }

            @Override
            public synchronized void write(int b) throws IOException {
                Con.this.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                Con.this.write(b, off, len);
            }

            @Override
            public synchronized void flush() throws IOException {
                Con.this.flush();
            }

            @Override
            public void close() throws IOException {
                // Note: This method is not synchronized. Con does the
                // synchronization, to avoid deadlock.
                Con.this.close();
            }
        }

        private class In extends InputStream {
            In() {
            }

            @Override
            public synchronized int read() throws IOException {
                return Con.this.read();
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) throws IOException {
                return Con.this.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                // Note: This method is not synchronized. Con does the
                // synchronization, to avoid deadlock.
                Con.this.close();
            }

            // FIXME: override skip and available
        }
    }
}
