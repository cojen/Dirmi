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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Collection;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.security.SecureRandom;

import org.cojen.util.IntHashMap;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class Multiplexer implements Broker {

    private static final int MAGIC_NUMBER = 0x1d0904af;

    static final int CLOSE = 0;
    static final int ACK   = 1;
    static final int OPEN  = 2;
    static final int SEND  = 3;

    // CLOSE command
    // size: 4 bytes
    // format: 4 byte header with connection id
    // byte 0: 00xxxxxx

    // ACK command
    // size: 4 bytes
    // format: 4 byte header with connection id
    // byte 0: 01xxxxxx

    // OPEN command
    // size: 4 bytes
    // format: 4 byte header with connection id
    // byte 0: 10xxxxxx

    // SEND command
    // size: 7..65542 bytes
    // format: 4 byte header with connection id, 2 byte size, 1..65536 bytes of data
    // byte 0: 11xxxxxx

    final Connection mMaster;
    final BufferedInputStream mMasterIn;
    final OutputStream mMasterOut;
    final ReentrantLock mMasterOutLock;

    final String mLocalAddress;
    final String mRemoteAddress;

    final ConcurrentBlockingQueue<Connection> mAcceptQueue;

    final IntHashMap<Con> mConnections;
    final ReadWriteLock mConnectionsLock;

    // Queue of connections which need to write acks.
    final ConcurrentBlockingQueue<Object> mAckQueue;

    private final AtomicInteger mNextId;

    private volatile boolean mClosed;
    private volatile IOException mClosedCause;

    /**
     * @param master does not need to be buffered
     */
    public Multiplexer(final Connection master, Executor executor) throws IOException {
        if (master == null || executor == null) {
            throw new IllegalArgumentException();
        }
        mMaster = master;
        mMasterIn = new BufferedInputStream(master.getInputStream(), 8192);
        mMasterOut = new BufferedOutputStream(master.getOutputStream(), 8192);
        mMasterOutLock = new ReentrantLock();
        mLocalAddress = master.getLocalAddressString();
        mRemoteAddress = master.getRemoteAddressString();

        mAcceptQueue = new ConcurrentBlockingQueue<Connection>();

        mConnections = new IntHashMap<Con>();
        mConnectionsLock = new ReentrantReadWriteLock();

        long ourRnd = new SecureRandom().nextLong();

        // Write magic number, followed by random number.

        ByteArrayOutputStream bout = new ByteArrayOutputStream(4 + 8);
        DataOutputStream dout = new DataOutputStream(bout);

        dout.writeInt(MAGIC_NUMBER);
        dout.writeLong(ourRnd);
        dout.flush();

        final byte[] bytesToSend = bout.toByteArray();

        // Write the initial message in a separate thread, in case the master
        // connection's send buffer is too small. We'd otherwise deadlock.
        class Writer implements Runnable {
            private final Thread mReader;
            volatile IOException mEx;
            boolean mDone;

            Writer() {
                mReader = Thread.currentThread();
            }

            public void run() {
                try {
                    mMasterOut.write(bytesToSend);
                    mMasterOut.flush();
                } catch (IOException e) {
                    mEx = e;
                    mReader.interrupt();
                } finally {
                    synchronized (this) {
                        mDone = true;
                        notifyAll();
                    }
                }
            }

            public synchronized void waitUntilDone() throws InterruptedException {
                while (!mDone) {
                    wait();
                }
            }
        }

        Writer writer = new Writer();
        executor.execute(writer);

        try {
            DataInputStream din = new DataInputStream(mMasterIn);
            int magic = din.readInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Unknown magic number: " + magic);
            }
            long theirRnd = din.readLong();
            
            // The random numbers determine whether our connection ids are even or odd.
            
            if (ourRnd == theirRnd) {
                // What are the odds?
                throw new IOException("Negotiation failure");
            }

            mNextId = new AtomicInteger(ourRnd < theirRnd ? 0 : 1);
        } catch (InterruptedIOException e) {
            if (writer.mEx != null) {
                throw writer.mEx;
            }
            throw e;
        } finally {
            try {
                writer.waitUntilDone();
            } catch (InterruptedException e) {
                // Don't care.
            }
        }

        mAckQueue = new ConcurrentBlockingQueue<Object>();

        executor.execute(new AckDrainer());

        // Start first worker.
        // FIXME: design allows only one command reader
        executor.execute(new CommandReader());
    }

    public Connection connect() throws IOException {
        while (true) {
            int id = mNextId.getAndAdd(2) & 0x3fffffff;
            Con con = new Con(this, id, false);
            Lock consWriteLock = mConnectionsLock.writeLock();
            consWriteLock.lock();
            try {
                checkClosed();
                if (!mConnections.containsKey(id)) {
                    mConnections.put(id, con);
                    return con;
                }
            } finally {
                consWriteLock.unlock();
            }
        }
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        return connect();
    }

    public Connection accept() throws IOException {
        checkClosed();
        try {
            Connection con = mAcceptQueue.take();
            if (con == Unconnection.THE) {
                mAcceptQueue.offer(con);
                checkClosed();
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        checkClosed();
        try {
            Connection con = mAcceptQueue.poll(time, unit);
            if (con == Unconnection.THE) {
                mAcceptQueue.offer(con);
                checkClosed();
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public boolean isClosed() {
        return mClosed;
    }

    public void close() throws IOException {
        close(null);
    }

    OutputStream tryLockMasterOut() throws IOException {
        if (mMasterOutLock.tryLock()) {
            try {
                return drainAckQueue(false);
            } catch (IOException e) {
                mMasterOutLock.unlock();
                throw e;
            }
        }
        return null;
    }

    OutputStream lockMasterOut() throws IOException {
        mMasterOutLock.lock();
        try {
            return drainAckQueue(false);
        } catch (IOException e) {
            mMasterOutLock.unlock();
            throw e;
        }
    }

    void unlockMasterOut(Boolean flush) throws IOException {
        try {
            OutputStream out = drainAckQueue(flush);
        } finally {
            mMasterOutLock.unlock();
        }
    }

    // Caller must hold mMasterOutLock.
    private OutputStream drainAckQueue(Boolean flush) throws IOException {
        OutputStream out = mMasterOut;
        ConcurrentBlockingQueue<Object> ackQueue = mAckQueue;
        Object obj;
        boolean any = false;
        while ((obj = ackQueue.poll()) != null) {
            if (obj == CLOSE_MESSAGE) {
                ackQueue.offer(obj);
                break;
            } else {
                ((Con) obj).writeAck(out);
                any = true;
            }
        }
        if ((flush != null && flush) || (flush == null && any)) {
            out.flush();
        }
        return out;
    }

    void close(IOException cause) throws IOException {
        boolean closed;

        // Close all connections.
        mConnectionsLock.writeLock().lock();
        try {
            if (mClosedCause == null) {
                mClosedCause = cause;
            }

            if (!mClosed) {
                mClosed = true;
                // Wake up blocked threads.
                mAckQueue.offer(CLOSE_MESSAGE);
                mAcceptQueue.offer(Unconnection.THE);
            }

            // Clone to prevent concurrent modification exception.
            Collection<Con> connections = new ArrayList<Con>(mConnections.values());
            for (Con con : connections) {
                con.disconnect();
            }
            mConnections.clear();
        } finally {
            mConnectionsLock.writeLock().unlock();
        }
        
        mMaster.close();
    }

    private void checkClosed() throws IOException {
        if (mClosed) {
            IOException cause = mClosedCause;
            if (cause == null) {
                throw new IOException("Closed");
            } else {
                throw cause;
            }
        }
    }

    void removeConnection(int id) {
        mConnectionsLock.writeLock().lock();
        try {
            mConnections.remove(id);
        } finally {
            mConnectionsLock.writeLock().unlock();
        }
    }

    int readInt(BufferedInputStream in, byte[] inBuf) throws IOException {
        if (in.readFully(inBuf, 0, 4) <= 0) {
            throw new IOException("Master connection closed");
        }
        return (inBuf[0] << 24) | ((inBuf[1] & 0xff) << 16) |
            ((inBuf[2] & 0xff) << 8) | (inBuf[3] & 0xff);
    }

    int readUnsignedShort(BufferedInputStream in, byte[] inBuf) throws IOException {
        if (in.readFully(inBuf, 0, 2) <= 0) {
            throw new IOException("Master connection closed");
        }
        return ((inBuf[0] & 0xff) << 8) | (inBuf[1] & 0xff);
    }

    private class AckDrainer implements Runnable {
        AckDrainer() {
        }

        public void run() {
            try {
                while (!mClosed) {
                    try {
                        mAckQueue.await();
                        // Simply locking and unlocking causes queue to drain.
                        mMasterOutLock.lock();
                        try {
                            drainAckQueue(null);
                        } finally {
                            mMasterOutLock.unlock();
                        }
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
            } catch (IOException e) {
                try {
                    close(e);
                } catch (IOException e2) {
                    // Don't care.
                }
            }
        }
    }

    private class CommandReader implements Runnable {
        CommandReader() {
        }

        public void run() {
            try {
                final BufferedInputStream in = mMasterIn;
                final byte[] inBuf = new byte[4];
                final Lock consReadLock = mConnectionsLock.readLock();
                final Lock consWriteLock = mConnectionsLock.writeLock();

                while (true) {
                    // Read command and id.
                    int id = readInt(in, inBuf);
                    int command = id >>> 30;
                    id &= ~(3 << 30);

                    Con con;

                    switch (command) {
                    case CLOSE:
                        consWriteLock.lock();
                        try {
                            con = mConnections.remove(id);
                        } finally {
                            consWriteLock.unlock();
                        }
                        if (con != null) {
                            con.disconnect();
                        }
                        break;

                    case ACK:
                        consReadLock.lock();
                        try {
                            con = mConnections.get(id);
                        } finally {
                            consReadLock.unlock();
                        }
                        if (con != null) {
                            con.ackReceived();
                        }
                        break;

                    case OPEN:
                        con = new Con(Multiplexer.this, id, true);
                        Con oldCon;
                        consWriteLock.lock();
                        try {
                            oldCon = mConnections.put(id, con);
                        } finally {
                            consWriteLock.unlock();
                        }
                        mAcceptQueue.offer(con);
                        if (oldCon != null) {
                            oldCon.disconnect();
                        }
                        break;

                    case SEND:
                        consReadLock.lock();
                        try {
                            con = mConnections.get(id);
                        } finally {
                            consReadLock.unlock();
                        }
                        int len = readUnsignedShort(in, inBuf) + 1;
                        if (con != null) {
                            con.sendReceived(in, len);
                        } else {
                            while (len > 0) {
                                long amt = in.skip(len);
                                if (amt <= 0) {
                                    break;
                                }
                                len -= amt;
                            }
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                try {
                    close(e);
                } catch (IOException e2) {
                    // Don't care.
                }
            }
        }
    }

    static final Object ACK_MESSAGE = new Object();
    static final Object TRANSFER_MESSAGE = new Object();
    static final Object CLOSE_MESSAGE = new Object();

    private static class Con implements Connection {
        private static final int
            NOT_OPENED = 0,
            OPENED = 1,
            CLOSE_IN_PROGRESS = 2,
            CLOSED = 3,
            REMOVED = 4;

        private final Multiplexer mMux;
        private final int mId;

        // Can only access when mMasterOutLock is held.
        private final byte[] mCommandBuffer;

        private final ReentrantLock mReadLock;
        private final ConcurrentBlockingQueue<Object> mReadQueue;

        private byte[] mReadBuffer;
        private int mReadStart;
        private int mReadSize;

        private final ConcurrentBlockingQueue<Object> mWriteQueue;

        private final InputStream mIn;
        private final OutputStream mOut;

        private static final AtomicIntegerFieldUpdater<Con> cStateUpdater =
            AtomicIntegerFieldUpdater.newUpdater(Con.class, "mState");

        // State transitions can only increase.
        private volatile int mState;

        Con(Multiplexer mux, int id, boolean opened) throws IOException {
            mMux = mux;
            mId = id;

            // Need 4 bytes for id, 2 for send length.
            byte[] commandBuffer = new byte[6];
            commandBuffer[0] = (byte) (id >> 24);
            commandBuffer[1] = (byte) (id >> 16);
            commandBuffer[2] = (byte) (id >> 8);
            commandBuffer[3] = (byte) id;
            mCommandBuffer = commandBuffer;

            mReadLock = new ReentrantLock();
            mReadQueue = new ConcurrentBlockingQueue<Object>(mReadLock);

            mReadLock.lock();
            try {
                mReadBuffer = new byte[8192];
            } finally {
                mReadLock.unlock();
            }

            mWriteQueue = new ConcurrentBlockingQueue<Object>();
            mWriteQueue.offer(ACK_MESSAGE);

            mIn = new In(this);
            mOut = new Out(this);

            mState = opened ? OPENED : NOT_OPENED;
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public String getLocalAddressString() {
            return mMux.mLocalAddress;
        }

        public String getRemoteAddressString() {
            return mMux.mRemoteAddress;
        }

        public void close() throws IOException {
            try {
                synchronized (mOut) {
                    if (cStateUpdater.compareAndSet(this, NOT_OPENED, OPENED)) {
                        // Never opened, so explicitly open now.
                        byte[] buf = mCommandBuffer;
                        OutputStream out = mMux.lockMasterOut();
                        try {
                            buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                            out.write(buf, 0, 4);
                        } finally {
                            mMux.unlockMasterOut(Boolean.FALSE);
                        }
                    }

                    if (cStateUpdater.compareAndSet(this, OPENED, CLOSE_IN_PROGRESS)) {
                        mOut.flush();

                        if (cStateUpdater.compareAndSet(this, CLOSE_IN_PROGRESS, CLOSED)) {
                            // If not closed after flushing, close explicitly here.
                            byte[] buf = mCommandBuffer;
                            OutputStream out = mMux.lockMasterOut();
                            try {
                                buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (CLOSE << 6));
                                out.write(buf, 0, 4);
                            } finally {
                                mMux.unlockMasterOut(Boolean.TRUE);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                try {
                    mMux.close();
                } catch (IOException e2) {
                    // Don't care.
                }
                throw e;
            } finally {
                disconnect();
            }
        }

        void sendReceived(BufferedInputStream in, int len) throws IOException {
            ReentrantLock lock = mReadLock;
            if (lock.tryLock()) {
                try {
                    byte[] buf = mReadBuffer;
                    int size = mReadSize;
                    int space = buf.length - size;
                    if (space > 0 && mReadQueue.isEmpty()) {
                        writeOrEnqueueAck();
                        int transfer = Math.min(space, len);
                        int pos = mReadStart + size;
                        if (pos >= buf.length) {
                            pos -= buf.length;
                        }
                        if (pos + transfer > buf.length) {
                            int first = buf.length - pos;
                            if (in.readFully(buf, pos, first) < 0) {
                                throw new IOException("Master connection closed");
                            }
                            mReadSize = (size += first);
                            len -= first;
                            transfer -= first;
                            pos = 0;
                        }
                        if (in.readFully(buf, pos, transfer) < 0) {
                            throw new IOException("Master connection closed");
                        }
                        mReadSize = size + transfer;
                        mReadQueue.offer(TRANSFER_MESSAGE);
                        if (transfer >= len) {
                            return;
                        }
                        len -= transfer;
                    }
                } finally {
                    lock.unlock();
                }
            }

            byte[] packet = new byte[len];
            if (in.readFully(packet) < 0) {
                throw new IOException("Master connection closed");
            }
            mReadQueue.offer(packet);
        }

        void writeOrEnqueueAck() throws IOException {
            OutputStream out;
            if ((out = mMux.tryLockMasterOut()) == null) {
                mMux.mAckQueue.offer(this);
            } else {
                try {
                    writeAck(out);
                } finally {
                    mMux.unlockMasterOut(Boolean.TRUE);
                }
            }
        }

        // Caller must hold mMasterOutLock.
        void writeAck(OutputStream out) throws IOException {
            try {
                byte[] buf = mCommandBuffer;
                buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (ACK << 6));
                out.write(buf, 0, 4);
            } catch (IOException e) {
                try {
                    mMux.close();
                } catch (IOException e2) {
                    // Don't care.
                }
                throw e;
            }
        }

        void ackReceived() {
            mWriteQueue.offer(ACK_MESSAGE);
        }

        void disconnect() {
            if (cStateUpdater.getAndSet(this, REMOVED) != REMOVED) {
                mMux.removeConnection(mId);
            }
            mReadQueue.offer(CLOSE_MESSAGE);
            mWriteQueue.offer(CLOSE_MESSAGE);
        }

        int doRead() throws IOException {
            ReentrantLock lock = mReadLock;
            lock.lock();
            try {
                int size = mReadSize;
                if (size <= 0) {
                    size = fill();
                }
                byte[] buf = mReadBuffer;
                int start = mReadStart;
                int next = start + 1;
                if (next >= buf.length) {
                    next = 0;
                }
                mReadStart = next;
                mReadSize = size - 1;
                return buf[start] & 0xff;
            } finally {
                lock.unlock();
            }
        }

        int doRead(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            ReentrantLock lock = mReadLock;
            lock.lock();
            try {
                int size = mReadSize;
                if (size <= 0) {
                    size = fill();
                }
                len = Math.min(size, len);
                byte[] buf = mReadBuffer;
                int start = mReadStart;
                int next = start + len;
                if (next >= buf.length) {
                    next -= buf.length;
                }
                if (next > start || next == 0) {
                    System.arraycopy(buf, start, b, off, len);
                } else {
                    if (off + len > b.length) {
                        throw new IndexOutOfBoundsException();
                    }
                    int first = buf.length - start;
                    System.arraycopy(buf, start, b, off, first);
                    System.arraycopy(buf, 0, b, off + first, next);
                }
                mReadStart = next;
                mReadSize = size - len;
                return len;
            } finally {
                lock.unlock();
            }
        }

        int readAvailable() {
            ReentrantLock lock = mReadLock;
            lock.lock();
            try {
                return mReadSize;
            } finally {
                lock.unlock();
            }
        }

        // Assumes mReadLock is held and mReadBuffer is empty.
        private int fill() throws IOException {
            Object obj;
            while (true) {
                try {
                    obj = mReadQueue.take();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                if (obj == CLOSE_MESSAGE) {
                    mReadQueue.offer(obj);
                    throw new IOException("Closed");
                } else if (obj == TRANSFER_MESSAGE) {
                    int size = mReadSize;
                    if (size > 0) {
                        return size;
                    }
                } else {
                    break;
                }
            }

            writeOrEnqueueAck();

            byte[] packet = (byte[]) obj;
            int length = packet.length;

            if (length >= mReadBuffer.length) {
                mReadBuffer = packet;
            } else {
                System.arraycopy(packet, 0, mReadBuffer, 0, length);
            }

            mReadStart = 0;
            mReadSize = length;

            return length;
        }

        // Is called via BufferedOutputStream, with synchronization lock held.
        void ensureOpen() throws IOException {
            if (cStateUpdater.compareAndSet(this, NOT_OPENED, OPENED)) {
                try {
                    OutputStream out = mMux.lockMasterOut();
                    byte[] buf = mCommandBuffer;
                    try {
                        buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                        out.write(buf, 0, 4);
                    } finally {
                        mMux.unlockMasterOut(Boolean.TRUE);
                    }
                } catch (IOException e) {
                    try {
                        mMux.close();
                    } catch (IOException e2) {
                        // Don't care.
                    }
                    throw e;
                }
            }
        }

        // Is called via BufferedOutputStream, with synchronization lock held.
        void doWrite(byte[] b, int offset, int length, boolean flush) throws IOException {
            while (length > 0) {
                try {
                    Object obj = mWriteQueue.take();
                    if (obj == CLOSE_MESSAGE) {
                        mWriteQueue.offer(obj);
                        throw new IOException("Closed");
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }

                byte[] buf = mCommandBuffer;

                // Limit to max packet size.
                int amount = Math.min(0x10000, length);

                try {
                    OutputStream out = mMux.lockMasterOut();
                    try {
                        if (cStateUpdater.compareAndSet(this, NOT_OPENED, OPENED)) {
                            buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                            out.write(buf, 0, 4);
                        }

                        buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (SEND << 6));
                        buf[4] = (byte) ((amount - 1) >> 8);
                        buf[5] = (byte) (amount - 1);
                        out.write(buf, 0, 6);
                        out.write(b, offset, amount);

                        if (amount >= length) {
                            if (cStateUpdater.compareAndSet(this, CLOSE_IN_PROGRESS, CLOSED)) {
                                // Piggyback close command.
                                buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (CLOSE << 6));
                                out.write(buf, 0, 4);
                                flush = true;
                            }
                        }
                    } finally {
                        mMux.unlockMasterOut(flush);
                    }
                } catch (IOException e) {
                    try {
                        mMux.close();
                    } catch (IOException e2) {
                        // Don't care.
                    }
                    throw e;
                }

                offset += amount;
                length -= amount;
            }
        }

        private static class In extends InputStream {
            private final Con mCon;

            In(Con con) {
                super();
                mCon = con;
            }

            @Override
            public int read() throws IOException {
                return mCon.doRead();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return mCon.doRead(b, off, len);
            }

            /*
            @Override
            public long skip(long n) throws IOException {
                // FIXME
                return -1;
            }
            */

            @Override
            public int available() throws IOException {
                return mCon.readAvailable();
            }

            @Override
            public void close() throws IOException {
                mCon.close();
            }
        }

        private static class Out extends AbstractBufferedOutputStream {
            private final Con mCon;

            Out(Con con) {
                super();
                mCon = con;
            }

            @Override
            public synchronized void flush() throws IOException {
                super.flush();
                mCon.ensureOpen();
            }

            @Override
            public synchronized void close() throws IOException {
                super.flush();
                mCon.close();
            }

            @Override
            protected void doWrite(byte[] b, int offset, int length, boolean flush)
                throws IOException
            {
                mCon.doWrite(b, offset, length, flush);
            }
        }
    }
}
