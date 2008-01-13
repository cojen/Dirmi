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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import java.util.concurrent.locks.Condition;
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
    final Lock mMasterInLock;
    final byte[] mInBuf = new byte[4];
    final BufferedOutputStream mMasterOut;
    final Executor mExecutor;
    final String mLocalAddress;
    final String mRemoteAddress;

    final BlockingQueue<Connection> mAcceptQueue;

    final IntHashMap<Con> mConnections;
    final ReadWriteLock mConnectionsLock;

    private int mNextId;

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
        mMasterIn = new BufferedInputStream(master.getInputStream(), 1024);
        mMasterInLock = new ReentrantLock();
        mMasterOut = new BufferedOutputStream(master.getOutputStream(), 8192);
        mExecutor = executor;
        mLocalAddress = master.getLocalAddressString();
        mRemoteAddress = master.getRemoteAddressString();

        mAcceptQueue = new LinkedBlockingQueue<Connection>();

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
            
            mNextId = ourRnd < theirRnd ? 0 : 1;
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

        // Start first worker.
        executor.execute(new Worker());
    }

    public Connection connect() throws IOException {
        mConnectionsLock.writeLock().lock();
        try {
            checkClosed();

            int id;
            do {
                id = mNextId;
                if ((mNextId += 2) >= 0x40000000) {
                    mNextId -= 0x40000000;
                }
            } while (mConnections.containsKey(id));
            
            Con con = new Con(this, id, false);
            mConnections.put(id, con);
            return con;
        } finally {
            mConnectionsLock.writeLock().unlock();
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
                mAcceptQueue.add(con);
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
                mAcceptQueue.add(con);
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
                // Wake up accepter.
                mAcceptQueue.add(Unconnection.THE);
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

    // Caller must hold mMasterInLock.
    int readInt(BufferedInputStream in) throws IOException {
        byte[] b = mInBuf;
        if (in.readFully(b, 0, 4) <= 0) {
            throw new IOException("Master connection closed");
        }
        return (b[0] << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    // Caller must hold mMasterInLock.
    int readUnsignedShort(BufferedInputStream in) throws IOException {
        byte[] b = mInBuf;
        if (in.readFully(b, 0, 2) <= 0) {
            throw new IOException("Master connection closed");
        }
        return ((b[0] & 0xff) << 8) | (b[1] & 0xff);
    }

    private class Worker implements Runnable {
        Worker() {
        }

        public void run() {
            try {
                BufferedInputStream in = mMasterIn;
                boolean replaced = false;
                while (!replaced) {
                    mMasterInLock.lock();
                    boolean releasedLock = false;
                    try {
                        // Read command and id.
                        int id = readInt(in);
                        int command = id >>> 30;
                        id &= ~(3 << 30);

                        Con con;

                        switch (command) {
                        case CLOSE:
                            mConnectionsLock.writeLock().lock();
                            try {
                                con = mConnections.remove(id);
                            } finally {
                                mConnectionsLock.writeLock().unlock();
                            }

                            if (con != null) {
                                if (!con.getInputLock().tryLock()) {
                                    // If lock is not immediately available, then
                                    // another thread is likely trying to feed
                                    // buffer. Create a replacement worker to
                                    // handle incoming commands while this thread
                                    // potentially blocks.
                                    mExecutor.execute(new Worker());
                                    replaced = true;
                                    con.getInputLock().lock();
                                }
                                try {
                                    con.disconnect();
                                } finally {
                                    con.getInputLock().unlock();
                                }
                            }
                            break;

                        case ACK:
                            mConnectionsLock.readLock().lock();
                            try {
                                con = mConnections.get(id);
                            } finally {
                                mConnectionsLock.readLock().unlock();
                            }
                            if (con != null) {
                                con.ackReceived();
                            }
                            break;

                        case OPEN:
                            Con oldCon;
                            mConnectionsLock.writeLock().lock();
                            try {
                                con = new Con(Multiplexer.this, id, true);
                                oldCon = mConnections.put(id, con);
                            } finally {
                                mConnectionsLock.writeLock().unlock();
                            }
                            mAcceptQueue.add(con);
                            if (oldCon != null) {
                                oldCon.disconnect();
                            }
                            break;

                        case SEND:
                            mConnectionsLock.readLock().lock();
                            try {
                                con = mConnections.get(id);
                            } finally {
                                mConnectionsLock.readLock().unlock();
                            }

                            int len = readUnsignedShort(in) + 1;

                            if (con == null) {
                                while (len > 0) {
                                    long amt = in.skip(len);
                                    if (amt <= 0) {
                                        break;
                                    }
                                    len -= amt;
                                }
                                break;
                            }

                            FifoBuffer buffer = con.getInputBuffer();

                            if (!con.getInputLock().tryLock()) {
                                // If lock is not immediately available, then
                                // another thread is likely trying to feed
                                // buffer. Create a replacement worker to
                                // handle incoming commands while this thread
                                // potentially blocks.
                                mExecutor.execute(new Worker());
                                replaced = true;

                                con.getInputLock().lock();
                            }
                            try {
                                int amt = buffer.writeTransfer(in, len);

                                // Send ack eagerly to improve throughput.
                                con.sendAck();

                                if (amt < len) {
                                    if (amt < 0) {
                                        throw new IOException("Master connection closed");
                                    }

                                    // Buffer was unable to consume entire
                                    // packet. Copy remaining into a temp
                                    // buffer to free master input and then
                                    // block while feeding the buffer.

                                    len -= amt;
                                    byte[] temp = new byte[len];
                                    amt = in.readFully(temp);

                                    // Don't need master input anymore.
                                    mMasterInLock.unlock();
                                    releasedLock = true;

                                    if (amt < 0) {
                                        throw new IOException("Master connection closed");
                                    }

                                    if (!replaced) {
                                        // Create a replacement worker to handle incoming
                                        // commands while this thread blocks.
                                        mExecutor.execute(new Worker());
                                        replaced = true;
                                    }

                                    buffer.writeFully(temp, 0, temp.length);
                                }
                            } finally {
                                con.getInputLock().unlock();
                            }

                            break;
                        }
                    } finally {
                        if (!releasedLock) {
                            mMasterInLock.unlock();
                        }
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

    private static class Con implements Connection {
        private static final int
            NOT_OPENED = 0,
            OPENED = 1,
            CLOSE_IN_PROGRESS = 2,
            CLOSED = 3,
            REMOVED = 4;

        private final Multiplexer mMux;
        private final int mId;

        private final Lock mInputLock;
        private final FifoBuffer mInputBuffer;
        private final InputStream mIn;

        private final OutputStream mOut;
        private final Semaphore mFlowControl;

        // Can only access while synchronizing on master output.
        private final byte[] mCommandBuffer;

        private static final AtomicIntegerFieldUpdater<Con> cStateUpdater =
            AtomicIntegerFieldUpdater.newUpdater(Con.class, "mState");

        // State transitions can only increase.
        private volatile int mState;

        Con(Multiplexer mux, int id, boolean opened) throws IOException {
            mMux = mux;
            mId = id;

            mInputLock = new ReentrantLock();
            mIn = new In(this, mInputBuffer = new FifoBuffer(8192));

            mOut = new Out(this);
            mFlowControl = new Semaphore(1);

            // Need 4 bytes for id, 2 for send length.
            byte[] commandBuffer = new byte[6];
            commandBuffer[0] = (byte) (id >> 24);
            commandBuffer[1] = (byte) (id >> 16);
            commandBuffer[2] = (byte) (id >> 8);
            commandBuffer[3] = (byte) id;
            mCommandBuffer = commandBuffer;

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
                        OutputStream out = mMux.mMasterOut;
                        byte[] buf = mCommandBuffer;
                        synchronized (out) {
                            buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                            out.write(buf, 0, 4);
                        }
                    }

                    if (cStateUpdater.compareAndSet(this, OPENED, CLOSE_IN_PROGRESS)) {
                        mOut.flush();

                        if (cStateUpdater.compareAndSet(this, CLOSE_IN_PROGRESS, CLOSED)) {
                            // If not closed after flushing, close explicitly here.
                            OutputStream out = mMux.mMasterOut;
                            byte[] buf = mCommandBuffer;
                            synchronized (out) {
                                buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (CLOSE << 6));
                                out.write(buf, 0, 4);
                                out.flush();
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

        FifoBuffer getInputBuffer() {
            return mInputBuffer;
        }

        Lock getInputLock() {
            return mInputLock;
        }

        void disconnect() {
            if (cStateUpdater.getAndSet(this, REMOVED) != REMOVED) {
                mMux.removeConnection(mId);
            }

            // Unblock any thread waiting to write.
            mFlowControl.release();

            // Unblock any thread waiting to read.
            mInputBuffer.close();
        }

        void sendAck() throws IOException {
            OutputStream out = mMux.mMasterOut;
            byte[] buf = mCommandBuffer;
            try {
                synchronized (out) {
                    buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (ACK << 6));
                    out.write(buf, 0, 4);
                    out.flush();
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

        void ackReceived() throws IOException {
            mFlowControl.release();
        }

        void ensureOpen() throws IOException {
            if (cStateUpdater.compareAndSet(this, NOT_OPENED, OPENED)) {
                OutputStream out = mMux.mMasterOut;
                byte[] buf = mCommandBuffer;
                try {
                    synchronized (out) {
                        buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                        out.write(buf, 0, 4);
                        out.flush();
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
        void doWrite(byte[] b, int offset, int length) throws IOException {
            while (length > 0) {
                try {
                    mFlowControl.acquire();
                } catch (InterruptedException e) {
                    if (mState == CLOSED) {
                        throw new IOException("Closed");
                    }
                    throw new InterruptedIOException();
                }
                if (mState == CLOSED) {
                    mFlowControl.release();
                    throw new IOException("Closed");
                }

                OutputStream out = mMux.mMasterOut;
                byte[] buf = mCommandBuffer;

                // Limit to max packet size.
                int amount = Math.min(0x10000, length);

                try {
                    synchronized (out) {
                        if (cStateUpdater.compareAndSet(this, NOT_OPENED, OPENED)) {
                            buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (OPEN << 6));
                            out.write(buf, 0, 4);
                        }

                        buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (SEND << 6));
                        buf[4] = (byte) ((amount - 1) >> 8);
                        buf[5] = (byte) (amount - 1);
                        out.write(buf, 0, 6);
                        out.write(b, offset, amount);

                        if (amount >= length &&
                            cStateUpdater.compareAndSet(this, CLOSE_IN_PROGRESS, CLOSED))
                        {
                            // Piggyback close command.
                            buf[0] = (byte) ((buf[0] & ~(3 << 6)) | (CLOSE << 6));
                            out.write(buf, 0, 4);
                        }

                        out.flush();
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

        private static class In extends AbstractBufferedInputStream {
            private final Con mCon;
            private final FifoBuffer mBuffer;

            In(Con con, FifoBuffer buffer) {
                super(8192);
                mCon = con;
                mBuffer = buffer;
            }

            @Override
            public int available() throws IOException {
                return super.available() + mBuffer.readAvailable();
            }

            @Override
            public void close() throws IOException {
                mCon.close();
            }

            @Override
            protected int doRead(byte[] bytes, int offset, int length) throws IOException {
                mCon.ensureOpen();
                return mBuffer.readAny(bytes, offset, length);
            }

            @Override
            protected long doSkip(long n) throws IOException {
                if (n > Integer.MAX_VALUE) {
                    n = Integer.MAX_VALUE;
                }
                return mBuffer.readSkipAny((int) n);
            }
        }

        private static class Out extends AbstractBufferedOutputStream {
            private final Con mCon;

            private int mSendCount = 1;

            Out(Con con) {
                super(8192);
                mCon = con;
            }

            @Override
            public void flush() throws IOException {
                drain();
                mCon.ensureOpen();
            }

            @Override
            public void close() throws IOException {
                mCon.close();
            }

            @Override
            protected void doWrite(byte[] b, int offset, int length) throws IOException {
                mCon.doWrite(b, offset, length);
            }
        }
    }
}
