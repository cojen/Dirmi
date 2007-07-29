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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Collection;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import java.security.SecureRandom;

import org.cojen.util.IntHashMap;

/**
 * Multiplexer allows new connections to be established over a single master
 * connection. Opening a new connection never blocks. At least one thread must
 * be calling the accept method at all times in order for the Multiplexer to
 * work.
 *
 * <p>Establishing new connections is relatively cheap, as is closing
 * connections. The buffers used by connections start small and grow as needed,
 * making them efficient for sending small commands over short-lived
 * connections.
 *
 * <p>A simple request reply operation can be implemented as opening a
 * connection and writing a message. The server responds by writing a response
 * and immediately closing the connection. For small messages, the whole
 * operation requires only one network round trip. If the response has a
 * variable length, then the length should be encoded so that the client need
 * not catch the IOException when the connection is closed.
 *
 * <p>Connections produced by the Multiplexer support timeouts, and if an
 * InterruptedIOException is thrown by it, the connection is still valid. For
 * write operations, the amount of bytes transferred before timing out can be
 * more than zero. That is, a write operation which times out can be partially
 * successful. Access the InterruptedIOException.bytesTransferred field to see
 * how many bytes were transferred before the interruption.
 *
 * @author Brian S O'Neill
 */
public class Multiplexer implements Broker, Closeable {
    private static final int MAGIC_NUMBER = 0x17524959;

    private static final int DEFAULT_MIN_BUFFER_SIZE = 64;
    private static final int DEFAULT_MAX_BUFFER_SIZE = 4096;

    static final int CLOSE   = 0 << 30;
    static final int RECEIVE = 1 << 30;
    static final int OPEN    = 2 << 30;
    static final int SEND    = 3 << 30;

    // CLOSE command
    // size: 4 bytes
    // format: 4 byte header with connection id
    // byte 0: 00xxxxxx

    // RECEIVE command
    // size: 6 bytes
    // format: 4 byte header with connection id, 2 byte receive window size (delta)
    // byte 0: 01xxxxxx

    // OPEN/SEND command
    // size: 7..65542 bytes
    // format: 4 byte header with connection id, 2 byte size, 1..65536 bytes of data
    // byte 0: 10xxxxxx or 11xxxxxx

    static final int SEND_HEADER_SIZE = 6;

    private static final Executor cThreadPool;

    static {
        cThreadPool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            }
        });
    }

    private volatile Connection mMaster;
    final String mLocalAddress;
    final String mRemoteAddress;

    private final IntHashMap<MultiplexConnection> mConnections;
    private int mNextId;

    final int mMinBufferSize;
    final int mMaxBufferSize;
    final int mReceiveWindow;

    private final byte[] mReadBuffer;
    private int mReadStart;
    private int mReadAvail;

    // Buffer for sending close and receive commands.
    private final byte[] mWriteBuffer = new byte[6];

    /**
     * @param master single connection which the multiplexer operates on
     */
    public Multiplexer(Connection master)
        throws IOException
    {
        this(master, DEFAULT_MIN_BUFFER_SIZE, DEFAULT_MAX_BUFFER_SIZE);
    }

    /**
     * @param master single connection which the multiplexer operates on
     * @param minBufferSize minumum size (in bytes) for connection buffers
     * @param maxBufferSize maximum size (in bytes) for connection buffers
     */
    public Multiplexer(final Connection master, int minBufferSize, int maxBufferSize)
        throws IOException
    {
        if (master == null) {
            throw new IllegalArgumentException("Master connection is null");
        }

        mMaster = master;
        mLocalAddress = master.getLocalAddressString();
        mRemoteAddress = master.getRemoteAddressString();

        mConnections = new IntHashMap<MultiplexConnection>();

        if (minBufferSize <= 0) {
            throw new IllegalArgumentException
                ("Minimum buffer size must be greater than zero: " + minBufferSize);
        }

        if (maxBufferSize <= 0) {
            throw new IllegalArgumentException
                ("Maximum buffer size must be greater than zero: " + maxBufferSize);
        }

        if (minBufferSize > maxBufferSize) {
            throw new IllegalArgumentException
                ("Minimum buffer size must be smaller than maximum: " +
                 minBufferSize + " > " + maxBufferSize);
        }

        mMinBufferSize = minBufferSize;
        mMaxBufferSize = maxBufferSize;

        long ourRnd = new SecureRandom().nextLong();

        // Write magic number, followed by initial receive window and random
        // number.

        ByteArrayOutputStream bout = new ByteArrayOutputStream(16);
        DataOutputStream dout = new DataOutputStream(bout);

        dout.writeInt(MAGIC_NUMBER);
        dout.writeInt(maxBufferSize);
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
                    master.getOutputStream().write(bytesToSend);
                    master.getOutputStream().flush();
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
        cThreadPool.execute(writer);

        try {
            DataInputStream din = new DataInputStream(master.getInputStream());
            int magic = din.readInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Unknown magic number: " + magic);
            }
            mReceiveWindow = din.readInt();
            long theirRnd = din.readLong();
            
            // The random numbers determine whether our connection ids are even or odd.
            
            if (ourRnd == theirRnd) {
                // What are the odds?
                throw new IOException("Negotiation failure");
            }
            
            mNextId = ourRnd < theirRnd ? 0 : 1;
            
            mReadBuffer = new byte[Math.max(DEFAULT_MAX_BUFFER_SIZE, maxBufferSize * 2)];
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
    }

    public void close() throws IOException {
        Connection master = mMaster;
        if (master != null) {
            mMaster = null;
            disconnectAll();
            master.close();
        }
    }

    public Connection connect() throws IOException {
        checkClosed();
        synchronized (mConnections) {
            int id;
            do {
                id = mNextId;
                if ((mNextId += 2) >= 0x40000000) {
                    mNextId -= 0x40000000;
                }
            } while (mConnections.containsKey(id));
            
            MultiplexConnection con = new MultiplexConnection
                (this, id, false, mLocalAddress, mRemoteAddress);
            mConnections.put(id, con);
            return con;
        }
    }

    public Connection tryConnect(int timeoutMillis) throws IOException {
        return connect();
    }

    public Connection accept() throws IOException {
        Connection con;
        while ((con = pump(-1)) == null) {}
        return con;
    }

    public Connection tryAccept(int timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) {
            return pump(timeoutMillis);
        }
        long start = System.nanoTime();
        Connection con;
        while ((con = pump(timeoutMillis)) == null) {
            long now = System.nanoTime();
            timeoutMillis -= (now - start) / 1000000L;
            if (timeoutMillis <= 0) {
                break;
            }
        }
        return con;
    }

    /**
     * @param timeoutMillis maximum time to block reading command
     * @return newly accepted connection, or null if none
     */
    private Connection pump(int timeoutMillis) throws IOException {
        Connection master = checkClosed();
        try {
            InputStream in = master.getInputStream();

            synchronized (in) {
                byte[] buffer = mReadBuffer;

                final int originalTimeout = master.getReadTimeout();
                master.setReadTimeout(timeoutMillis);

                // Read command and id.
                int id, command;
                try {
                    try {
                        id = readInt(in);
                        command = id & (3 << 30);
                        id &= ~(3 << 30);
                    } finally {
                        try {
                            master.setReadTimeout(originalTimeout);
                        } catch (IOException e) {
                            // Connection is just plain broken.
                        }
                    }
                } catch (InterruptedIOException e) {
                    if (timeoutMillis < 0) {
                        throw e;
                    }
                    return null;
                }

                if (command == RECEIVE) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        con = mConnections.get(id);
                    }
                    int receiveWindow = readUnsignedShort(in) + 1;
                    if (con != null) {
                        con.mOut.updateReceiveWindow(receiveWindow);
                    }
                } else if (command == SEND || command == OPEN) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        con = mConnections.get(id);
                        if (con == null && command == OPEN) {
                            con = new MultiplexConnection
                                (this, id, true, mLocalAddress, mRemoteAddress);
                            mConnections.put(id, con);
                        }
                    }
                    int length = readUnsignedShort(in) + 1;
                    if (mReadAvail >= length) {
                        if (con != null) {
                            con.mIn.supply(buffer, mReadStart, length);
                        }
                        mReadStart += length;
                        mReadAvail -= length;
                    } else {
                        if (con == null) {
                            length -= mReadAvail;
                            while (length > 0) {
                                length -= in.skip(length);
                            }
                            mReadStart = 0;
                            mReadAvail = 0;
                        } else {
                            MultiplexConnection.Input mci = con.mIn;
                            // Drain read buffer.
                            mci.supply(buffer, mReadStart, mReadAvail);
                            length -= mReadAvail;
                            while (true) {
                                int amt = in.read(buffer, 0, buffer.length);
                                if (amt < 0) {
                                    throw new IOException("Master connection closed");
                                }
                                if (amt >= length) {
                                    mci.supply(buffer, 0, length);
                                    mReadStart = length;
                                    mReadAvail = amt - length;
                                    break;
                                }
                                mci.supply(buffer, 0, amt);
                                length -= amt;
                            }
                        }
                    }
                    if (command == OPEN) {
                        return con;
                    }
                } else if (command == CLOSE) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        con = mConnections.remove(id);
                    }
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            try {
                close();
            } catch (IOException e2) {
                // Don't care
            }
            throw e;
        }

        return null;
    }

    // Caller should synchronize on InputStream.
    private int readInt(InputStream in) throws IOException {
        fill(in, 4);
        byte[] buffer = mReadBuffer;
        int start = mReadStart;
        int value = (buffer[start] << 24) | ((buffer[start + 1] & 0xff) << 16)
            | ((buffer[start + 2] & 0xff) << 8) | (buffer[start + 3] & 0xff);
        mReadStart = start + 4;
        mReadAvail -= 4;
        return value;
    }

    // Caller should synchronize on InputStream.
    private int readUnsignedShort(InputStream in) throws IOException {
        fill(in, 2);
        byte[] buffer = mReadBuffer;
        int start = mReadStart;
        int value = ((buffer[start] & 0xff) << 8) | (buffer[start + 1] & 0xff);
        mReadStart = start + 2;
        mReadAvail -= 2;
        return value;
    }

    // Caller should synchronize on InputStream.
    private void fill(InputStream in, int minAmount) throws IOException {
        if (mReadAvail >= minAmount) {
            return;
        }

        byte[] buffer = mReadBuffer;

        if (mReadAvail == 0) {
            mReadStart = 0;
        } else if ((buffer.length - mReadStart) < minAmount) {
            System.arraycopy(buffer, mReadStart, buffer, 0, mReadAvail);
            mReadStart = 0;
        }

        while (true) {
            int offset = mReadStart + mReadAvail;
            int amt = in.read(buffer, offset, buffer.length - offset);
            if (amt < 0) {
                throw new IOException("Master connection closed");
            }
            if ((mReadAvail += amt) >= minAmount) {
                break;
            }
            offset += amt;
        }
    }

    /**
     * As a side effect, destroys contents of byte array, as it used to build
     * the block header: id, chunk size.
     *
     * @param bytes must always have enough header bytes before the offset
     * @param offset must always be at least SEND_HEADER_SIZE
     * @param size amount of bytes to send after offset
     * @param close when true, close connection after sending
     */
    void send(int id, int op, byte[] bytes, int offset, int size, boolean close)
        throws IOException
    {
        Connection master = checkClosed();
        id |= op;
        try {
            OutputStream out = master.getOutputStream();
            synchronized (out) {
                while (size > 0) {
                    int chunk = size <= 65536 ? size : 65536;
                    
                    // Place header in byte array to avoid being transmitted in
                    // a separate network packet.
                    bytes[offset - 6] = (byte)(id >> 24);
                    bytes[offset - 5] = (byte)(id >> 16);
                    bytes[offset - 4] = (byte)(id >> 8);
                    bytes[offset - 3] = (byte)id;
                    bytes[offset - 2] = (byte)((chunk - 1) >> 8);
                    bytes[offset - 1] = (byte)(chunk - 1);

                    if ((size -= chunk) <= 0 && close) {
                        // Try to piggyback close command.
                        if (offset + chunk + 4 <= bytes.length) {
                            id &= ~(3 << 30);
                            synchronized (mConnections) {
                                mConnections.remove(id);
                            }
                            id |= CLOSE;
                            bytes[offset + chunk]     = (byte)(id >> 24);
                            bytes[offset + chunk + 1] = (byte)(id >> 16);
                            bytes[offset + chunk + 2] = (byte)(id >> 8);
                            bytes[offset + chunk + 3] = (byte)id;
                            chunk += 4;
                            close = false;
                        }
                    }

                    out.write(bytes, offset - SEND_HEADER_SIZE, chunk + SEND_HEADER_SIZE);

                    offset += chunk;
                }

                if (close) {
                    id &= ~(3 << 30);
                    synchronized (mConnections) {
                        mConnections.remove(id);
                    }
                    id |= CLOSE;
                    byte[] buffer = mWriteBuffer;
                    buffer[0] = (byte)(id >> 24);
                    buffer[1] = (byte)(id >> 16);
                    buffer[2] = (byte)(id >> 8);
                    buffer[3] = (byte)id;
                    out.write(buffer, 0, 4);
                }

                out.flush();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            try {
                close();
            } catch (IOException e2) {
                // Don't care
            }
            throw e;
        }
    }


    void receive(int id, int size) throws IOException {
        Connection master = checkClosed();
        id |= RECEIVE;
        try {
            OutputStream out = master.getOutputStream();
            synchronized (out) {
                byte[] buffer = mWriteBuffer;
                while (size > 0) {
                    int chunk = size <= 65536 ? size : 65536;

                    buffer[0] = (byte)(id >> 24);
                    buffer[1] = (byte)(id >> 16);
                    buffer[2] = (byte)(id >> 8);
                    buffer[3] = (byte)id;
                    buffer[4] = (byte)((chunk - 1) >> 8);
                    buffer[5] = (byte)(chunk - 1);

                    out.write(buffer, 0, 6);
                    size -= chunk;
                }
                out.flush();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            try {
                close();
            } catch (IOException e2) {
                // Don't care
            }
            throw e;
        }
    }

    /**
     * Called by MultiplexConnection when it disconnects.
     */
    void unregister(MultiplexConnection con) {
        synchronized (mConnections) {
            mConnections.remove(con.mId);
        }
    }

    private void disconnectAll() {
        // Copy list of connections since disconnect calls back to unregister,
        // which attempts to modify the list.
        Collection<MultiplexConnection> connections;
        synchronized (mConnections) {
            connections = new ArrayList<MultiplexConnection>(mConnections.values());
            mConnections.clear();
        }
        for (MultiplexConnection con : connections) {
            con.disconnect();
        }
    }

    private Connection checkClosed() throws IOException {
        Connection master = mMaster;
        if (master == null) {
            throw new IOException("Master connection closed");
        }
        return master;
    }
}
