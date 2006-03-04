/*
 * Copyright (c) 2006 Brian S O'Neill. All Rights Reserved.
 */

package bidirmi;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Collection;

import java.security.SecureRandom;

import com.amazon.carbonado.util.IntHashMap;

/**
 * Multiplexer allows new connections to be established over a single master
 * connection.
 *
 * @author Brian S O'Neill
 */
public class Multiplexer implements Connector {
    private static final int MAGIC_NUMBER = 0x17524959;

    private static final int DEFAULT_BUFFER_SIZE = 4000;

    private static final int CLOSE   = 1 << 30;
    private static final int SEND    = 2 << 30;
    private static final int RECEIVE = 3 << 30;

    static final int HEADER_SIZE = 6;

    private volatile Connection mMaster;

    private final IntHashMap<MultiplexConnection> mConnections;
    private int mNextId;

    final int mInputBufferSize;
    final int mOutputBufferSize;
    final int mReceiveWindow;

    // Buffer for sending close command.
    private final byte[] mCloseBuf = new byte[4];

    public Multiplexer(Connection master)
        throws IOException
    {
        this(master, DEFAULT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
    }

    public Multiplexer(Connection master, int inputBufferSize, int outputBufferSize)
        throws IOException
    {
        mMaster = master;
        mConnections = new IntHashMap<MultiplexConnection>();

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

        dout.writeInt(MAGIC_NUMBER);
        dout.writeInt(inputBufferSize);
        SecureRandom secureRandom = new SecureRandom();
        int ourRnd = secureRandom.nextInt();
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
            mConnections.put(id, con);
            return con;
        }
    }

    public Connection connect(int timeoutMillis) throws IOException {
        return connect();
    }

    public Connection accept() throws IOException {
        // FIXME
        return null;
    }

    public Connection accept(int timeoutMillis) throws IOException {
        // FIXME
        return null;
    }

    /**
     * As a side effect, destroys contents of byte array, as it used to build
     * the block header: id, chunk size.
     *
     * @param bytes must always have enough header bytes before the offset
     * @param offset must always be at least HEADER_SIZE
     */
    void send(int id, byte[] bytes, int offset, int size)
        throws IOException
    {
        Connection master = checkClosed();
        id |= SEND;
        try {
            OutputStream out = master.getOutputStream();
            synchronized (out) {
                while (size > 0) {
                    int chunk = size <= 65536 ? size : 65536;
                    
                    // Place header in byte array to ensure it is not transmitted
                    // in a separate network packet.
                    bytes[offset - 6] = (byte)(id >> 24);
                    bytes[offset - 5] = (byte)(id >> 16);
                    bytes[offset - 4] = (byte)(id >> 8);
                    bytes[offset - 3] = (byte)id;
                    bytes[offset - 2] = (byte)((chunk - 1) >> 8);
                    bytes[offset - 1] = (byte)(chunk - 1);
                    
                    out.write(bytes, offset - HEADER_SIZE, chunk + HEADER_SIZE);
                    
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
            byte[] buf = mCloseBuf;
            synchronized (out) {
                buf[0] = (byte)(id >> 24);
                buf[1] = (byte)(id >> 16);
                buf[2] = (byte)(id >> 8);
                buf[3] = (byte)id;
                out.write(buf);
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
        Collection<MultiplexConnection> connections;
        synchronized (mConnections) {
            connections = new ArrayList<MultiplexConnection>(mConnections.values());
            mConnections.clear();
        }
        for (MultiplexConnection con : connections) {
            try {
                con.disconnect();
            } catch (IOException e) {
                // Don't care, but not expected to happen.
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
