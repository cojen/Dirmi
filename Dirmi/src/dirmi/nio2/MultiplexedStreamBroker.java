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

import java.util.ArrayList;
import java.util.List;

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

    final int mIdBit;
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

        mNextId = new AtomicInteger(mIdBit = (ourRnd < theirRnd ? 0 : 1));

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
        try {
            mMessCon.send(buffer);
        } catch (IOException e) {
            closed(e);
            throw e;
        }
    }

    void closed(IOException exception) {
        mConnectionsLock.writeLock().lock();
        try {
            // Clone to prevent concurrent modification.
            List<Con> cons = new ArrayList<Con>(mConnections.values());
            for (Con con : cons) {
                try {
                    con.close(true, exception);
                } catch (IOException e) {
                    // Ignore.
                }
            }
            mConnections.clear();

            if (exception != null) {
                StreamListener listener;
                while ((listener = mListeners.poll()) != null) {
                    listener.failed(exception);
                }
            }
        } finally {
            mConnectionsLock.writeLock().unlock();
        }
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
                        } else if (command == ACK ||
                                   (command == CLOSE && (id & 1) == mIdBit)) {
                            // Don't create connection for acknowledgment.
                            // Don't create connection if peer is closing
                            // connection created by this broker.
                            return;
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
                    con.close(true, null);
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
                    con.close(true, null);
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }

        public void closed() {
            MultiplexedStreamBroker.this.closed(null);
        }

        public void closed(IOException e) {
            MultiplexedStreamBroker.this.closed(e);
        }
    }

    private class Con implements StreamConnection {
        private static final int HEADER_SIZE = 4;

        final int mId;

        private final OutputStream mOut;
        private final ByteBuffer mOutBuffer;
        private boolean mOutBlocked;

        private final InputStream mIn;
        private ByteBuffer mInBuffer;
        private ByteBuffer mNextInBuffer;

        private boolean mClosed;
        private IOException mClosedException;

        private Object mLocalAddress;
        private Object mRemoteAddress;

        // FIXME: Use queue optimized for zero or one elements.
        private final ConcurrentLinkedQueue<StreamTask> mReadTasks;

        Con(int id) {
            mId = id;

            mOut = new Out();
            mOutBuffer = mBufferPool.get(Math.min(65536, mMessCon.getMaximumMessageSize()));
            mOutBuffer.putInt(id);

            mIn = new In();

            mReadTasks = new ConcurrentLinkedQueue<StreamTask>();
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

        public void executeWhenReadable(StreamTask task) {
            synchronized (mIn) {
                if (readAvailable() > 0) {
                    mMessCon.execute(task);
                } else {
                    mReadTasks.add(task);
                }
            }
        }

        public void execute(Runnable task) {
            mMessCon.execute(task);
        }

        public void close() throws IOException {
            close(false, null);
        }

        void close(boolean force, IOException exception) throws IOException {
            // Must lock mIn before mOut to avoid deadlock with getReadBuffer method.
            synchronized (mIn) {
                synchronized (mOut) {
                    if (!mClosed) {
                        if (!force) {
                            ByteBuffer buffer = mOutBuffer;
                            buffer.put(0, (byte) ((CLOSE << 6) | buffer.get(0)));
                            flush(buffer);
                        }
                        mClosed = true;
                        mClosedException = exception;
                        mConnections.remove(mId);
                        mBufferPool.yield(mOutBuffer);
                    }
                    mOut.notifyAll();
                }
                mIn.notifyAll();
            }

            if (force) {
                StreamTask task;
                while ((task = mReadTasks.poll()) != null) {
                    if (exception == null) {
                        task.closed();
                    } else {
                        task.closed(exception);
                    }
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
                        ByteBuffer buffer = mOutBuffer;
                        if (!buffer.hasRemaining()) {
                            try {
                                flush(buffer);
                            } catch (IOException e) {
                                // Ignore and assume all connections are now closed.
                            }
                        }
                    }
                    mOut.notifyAll();
                }
            }
        }

        void received(ByteBuffer buffer) {
            if (buffer == null) {
                return;
            }

            buffer.flip();
            boolean doSendAck;
            synchronized (mIn) {
                if (mInBuffer == null) {
                    doSendAck = true;
                    if (mNextInBuffer == null) {
                        mInBuffer = buffer;
                    } else {
                        mInBuffer = mNextInBuffer;
                        mNextInBuffer = buffer;
                    }
                } else {
                    doSendAck = false;
                    assert mNextInBuffer == null;
                    mNextInBuffer = buffer;
                }
                mIn.notify();
            }

            if (doSendAck) {
                sendAck();
            }

            StreamTask task = mReadTasks.poll();
            if (task != null) {
                task.run();
            }
        }

        private void sendAck() {
            synchronized (mOut) {
                if (!mClosed) {
                    ByteBuffer buffer = mOutBuffer;
                    int header = buffer.get(0) & 0x3f;
                    buffer.put(0, (byte) ((ACK << 6) | header));
                    int originalPos = buffer.position();
                    buffer.position(0).limit(4);
                    try {
                        // FIXME: if !mOutBlocked, piggyback with flush
                        sendMessage(buffer);
                    } catch (IOException e) {
                        // Ignore and assume all connections are now closed.
                    } finally {
                        buffer.limit(buffer.capacity()).position(originalPos);
                    }
                }
            }
        }

        // Caller must synchronize on mIn.
        int read() throws IOException {
            ByteBuffer buffer = getReadBuffer();
            byte b = buffer.get();
            if (!buffer.hasRemaining()) {
                mInBuffer = null;
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
                mInBuffer = null;
                mBufferPool.yield(buffer);
            }
            return len;
        }

        // Caller must synchronize on mIn.
        int readAvailable() {
            ByteBuffer buffer = mInBuffer;
            int avail = buffer == null ? 0 : buffer.remaining();
            if ((buffer = mNextInBuffer) != null) {
                avail += mNextInBuffer.remaining();
            }
            return avail;
        }

        // Caller must synchronize on mIn.
        private ByteBuffer getReadBuffer() throws IOException {
            ByteBuffer buffer = mInBuffer;
            if (buffer == null) {
                if ((buffer = mNextInBuffer) != null) {
                    mInBuffer = buffer;
                    mNextInBuffer = null;
                    // This locks mOut while mIn lock is held. For this reason,
                    // double lock in close method must lock mIn and then mOut.

                    // TODO: More investigation required here. Can send buffer
                    // be full? If so, reads are blocked.
                    sendAck();
                } else {
                    do {
                        checkClosed();
                        try {
                            mIn.wait();
                        } catch (InterruptedException e) {
                            throw new InterruptedIOException();
                        }
                    } while ((buffer = mInBuffer) == null);
                }
            }
            return buffer;
        }

        // Caller must synchronize on mIn or mOut.
        private void checkClosed() throws IOException {
            if (mClosed) {
                String message = "Connection closed";
                if (mClosedException != null) {
                    if (mClosedException.getMessage() != null) {
                        message = mClosedException.getMessage();
                    }
                    throw new IOException(message, mClosedException);
                }
                throw new IOException(message);
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
                // Note: This method is not synchronized. The Con method does
                // the synchronization.
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
            public synchronized int available() throws IOException {
                return Con.this.readAvailable();
            }

            @Override
            public void close() throws IOException {
                // Note: This method is not synchronized. The Con method does
                // the synchronization.
                Con.this.close();
            }

            // FIXME: override skip
        }
    }
}
