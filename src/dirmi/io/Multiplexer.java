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

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Collection;

import java.security.SecureRandom;

import com.amazon.carbonado.util.IntHashMap;

/**
 * Multiplexer allows new connections to be established over a single master
 * connection. Opening a new connection never blocks. At least one thread must be
 * calling accept at all times in order for the Multiplexer to work.
 *
 * @author Brian S O'Neill
 */
public class Multiplexer implements Connector {
    private static final int MAGIC_NUMBER = 0x17524959;

    private static final int DEFAULT_BUFFER_SIZE = 4000;

    static final int CLOSE   = 0 << 30;
    static final int OPEN    = 1 << 30;
    static final int SEND    = 2 << 30;
    static final int RECEIVE = 3 << 30;

    // CLOSE command
    // size: 4 bytes
    // format: 4 byte header with connection id
    // byte 0: 01xxxxxx

    // OPEN/SEND command
    // size: 7..65542 bytes
    // format: 4 byte header with connection id, 2 byte size, 1..65536 bytes of data
    // byte 0: 10xxxxxx

    // RECEIVE command
    // size: 8 bytes
    // format: 4 byte header with connection id, 4 byte receive window size
    // byte 0: 11xxxxxx

    static final int SEND_HEADER_SIZE = 6;

    private volatile Connection mMaster;

    private final IntHashMap<Reference<MultiplexConnection>> mConnections;
    private int mNextId;

    final int mInputBufferSize;
    final int mOutputBufferSize;
    final int mReceiveWindow;

    private final byte[] mReadBuffer;
    private int mReadStart;
    private int mReadAvail;

    // Buffer for sending close and receive commands.
    private final byte[] mWriteBuffer = new byte[8];

    public Multiplexer(Connection master)
        throws IOException
    {
        this(master, DEFAULT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
    }

    public Multiplexer(Connection master, int inputBufferSize, int outputBufferSize)
        throws IOException
    {
        mMaster = master;
        mConnections = new IntHashMap<Reference<MultiplexConnection>>();

        if (inputBufferSize <= 0) {
            throw new IllegalArgumentException
                ("Input buffer size must be greater than zero: " + inputBufferSize);
        }

        if (outputBufferSize <= 0) {
            throw new IllegalArgumentException
                ("Output buffer size must be greater than zero: " + outputBufferSize);
        }

        mInputBufferSize = inputBufferSize;
        mOutputBufferSize = outputBufferSize;

        // Write magic number, followed by initial receive window and random
        // number.

        DataOutputStream dout = new DataOutputStream
            (new BufferedOutputStream(master.getOutputStream(), 12));

        SecureRandom secureRandom = new SecureRandom();
        int ourRnd = secureRandom.nextInt();

        dout.writeInt(MAGIC_NUMBER);
        dout.writeInt(inputBufferSize);
        dout.writeInt(ourRnd);
        dout.flush();

        DataInputStream din = new DataInputStream(master.getInputStream());
        int magic = din.readInt();
        if (magic != MAGIC_NUMBER) {
            throw new IOException("Unknown magic number: " + magic);
        }
        mReceiveWindow = din.readInt();
        int theirRnd = din.readInt();

        // The random numbers determine whether our connection ids are even or
        // odd. If both numbers are the same, try again.

        if (ourRnd == theirRnd) {
            ourRnd = secureRandom.nextInt();
            dout.writeInt(ourRnd);
            dout.flush();
            theirRnd = din.readInt();
            if (ourRnd == theirRnd) {
                // What??? Again? What are the odds?
                throw new IOException("Negotiation failure");
            }
        }

        mNextId = ourRnd < theirRnd ? 0 : 1;

        mReadBuffer = new byte[Math.max(DEFAULT_BUFFER_SIZE, inputBufferSize * 2)];
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
            
            MultiplexConnection con = new MultiplexConnection(this, id);
            mConnections.put(id, new WeakReference<MultiplexConnection>(con));
            return con;
        }
    }

    public Connection connect(int timeoutMillis) throws IOException {
        return connect();
    }

    public Connection accept() throws IOException {
        Connection con;
        while ((con = pump()) == null) {}
        return con;
    }

    public Connection accept(int timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) {
            return pump();
        }
        long start = System.nanoTime();
        long end = start + 1000000L * timeoutMillis;
        Connection con = pump();
        while ((con = pump()) == null) {
            if (System.nanoTime() >= end) {
                break;
            }
        }
        return con;
    }

    /**
     * @return newly accepted connection, or null if none
     */
    private Connection pump() throws IOException {
        Connection master = checkClosed();
        try {
            InputStream in = checkClosed().getInputStream();

            synchronized (in) {
                byte[] buffer = mReadBuffer;
                
                // Read command and id.
                int id = readInt(in);
                int command = id & (3 << 30);
                id &= ~(3 << 30);

                if (command == RECEIVE) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        Reference<MultiplexConnection> conRef = mConnections.get(id);
                        if (conRef == null) {
                            con = null;
                        } else {
                            con = conRef.get();
                        }
                    }
                    int receiveWindow = readInt(in);
                    if (con != null) {
                        con.mOut.updateReceiveWindow(receiveWindow);
                    }
                } else if (command == SEND || command == OPEN) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        Reference<MultiplexConnection> conRef = mConnections.get(id);
                        if (conRef == null || (con = conRef.get()) == null) {
                            if (command == OPEN) {
                                con = new MultiplexConnection(this, id);
                                mConnections.put(id, new WeakReference<MultiplexConnection>(con));
                            } else {
                                con = null;
                            }
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
                        } else {
                            MultiplexConnection.Input mci = con.mIn;
                            // Drain read buffer.
                            mci.supply(buffer, mReadStart, mReadAvail);
                            length -= mReadAvail;
                            while (length > 0) {
                                int amt = in.read(buffer, 0, buffer.length);
                                if (amt < 0) {
                                    throw new EOFException();
                                }
                                mci.supply(buffer, 0, amt);
                                length -= amt;
                            }
                        }
                        mReadStart = 0;
                        mReadAvail = 0;
                    }
                    if (command == OPEN) {
                        return con;
                    }
                } else if (command == CLOSE) {
                    MultiplexConnection con;
                    synchronized (mConnections) {
                        Reference<MultiplexConnection> conRef = mConnections.remove(id);
                        if (conRef == null) {
                            con = null;
                        } else {
                            con = conRef.get();
                        }
                    }
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        } catch (IOException e) {
            mMaster = null;
            disconnectAll();
            try {
                master.close();
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
            int amt = in.read(buffer, mReadStart, buffer.length - mReadStart);
            if (amt < 0) {
                throw new EOFException();
            }
            if ((mReadAvail += amt) >= minAmount) {
                break;
            }
        }
    }

    /**
     * As a side effect, destroys contents of byte array, as it used to build
     * the block header: id, chunk size.
     *
     * @param bytes must always have enough header bytes before the offset
     * @param offset must always be at least SEND_HEADER_SIZE
     */
    void send(int id, int op, byte[] bytes, int offset, int size) throws IOException {
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
                    
                    out.write(bytes, offset - SEND_HEADER_SIZE, chunk + SEND_HEADER_SIZE);
                    
                    offset += chunk;
                    size -= chunk;
                }
                out.flush();
            }
        } catch (IOException e) {
            mMaster = null;
            disconnectAll();
            try {
                master.close();
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
                buffer[0] = (byte)(id >> 24);
                buffer[1] = (byte)(id >> 16);
                buffer[2] = (byte)(id >> 8);
                buffer[3] = (byte)id;
                buffer[4] = (byte)(size >> 24);
                buffer[5] = (byte)(size >> 16);
                buffer[6] = (byte)(size >> 8);
                buffer[7] = (byte)id;
                out.write(buffer, 0, 8);
                out.flush();
            }
        } catch (IOException e) {
            mMaster = null;
            disconnectAll();
            try {
                master.close();
            } catch (IOException e2) {
                // Don't care
            }
            throw e;
        }
    }


    void unregister(MultiplexConnection con) throws IOException {
        int id = con.mId;
        synchronized (mConnections) {
            if (mConnections.remove(id) == null) {
                return;
            }
        }

        Connection master = mMaster;
        if (master == null) {
            return;
        }

        id |= CLOSE;
        try {
            OutputStream out = master.getOutputStream();
            synchronized (out) {
                byte[] buffer = mWriteBuffer;
                buffer[0] = (byte)(id >> 24);
                buffer[1] = (byte)(id >> 16);
                buffer[2] = (byte)(id >> 8);
                buffer[3] = (byte)id;
                out.write(buffer, 0, 4);
                out.flush();
            }
        } catch (IOException e) {
            mMaster = null;
            disconnectAll();
            try {
                master.close();
            } catch (IOException e2) {
                // Don't care
            }
            throw e;
        }
    }

    private void disconnectAll() {
        Collection<Reference<MultiplexConnection>> connections;
        synchronized (mConnections) {
            connections = new ArrayList<Reference<MultiplexConnection>>(mConnections.values());
            mConnections.clear();
        }
        for (Reference<MultiplexConnection> conRef : connections) {
            MultiplexConnection con = conRef.get();
            if (con != null) {
                try {
                    con.disconnect();
                } catch (IOException e) {
                    // Don't care, but not expected to happen.
                }
            }
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
