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

package dirmi.core;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.security.SecureRandom;

import java.util.Arrays;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dirmi.io.BufferedConnection;
import dirmi.io.Connection;
import dirmi.io.QueuedBroker;
import dirmi.io.SocketConnection;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ClientSocketBroker extends QueuedBroker {
    private static final int SESSION_LENGTH = 16;

    private static final SecureRandom cRandom = new SecureRandom();

    static byte[] readSessionId(Connection con) throws IOException {
        int off = 0;
        int len = SESSION_LENGTH;
        byte[] sessionId = new byte[len];
        int amt;
        while ((amt = con.getInputStream().read(sessionId, off, len)) > 0) {
            off += amt;
            len -= amt;
        }
        if (len > 0) {
            throw new EOFException("Unable to fully read session identifier");
        }
	return sessionId;
    }

    private final SocketAddress mAddress;
    private final byte[] mConnectSessionId;
    private final byte[] mAcceptSessionId;

    public ClientSocketBroker(String host, int port) {
        this(new InetSocketAddress(host, port));
    }

    public ClientSocketBroker(SocketAddress address) {
        super(null, new LinkedBlockingQueue<Connection>());
        mAddress = address;

        byte[] connectSessionId = new byte[SESSION_LENGTH];
        cRandom.nextBytes(connectSessionId);

        // MSB of session id defines connect or accept mode.
        connectSessionId[0] &= 0x7f;

        byte[] acceptSessionId = connectSessionId.clone();
        acceptSessionId[0] |= 0x80;

        mConnectSessionId = connectSessionId;
        mAcceptSessionId = acceptSessionId;
    }

    @Override
    public Connection connect() throws IOException {
        Connection con = doConnect();
        forConnect(con);
        return con;
    }

    @Override
    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Connection con = doTryConnect(time, unit);
        if (con != null) {
            forConnect(con);
        }
        return con;
    }

    @Override
    public Connection accept() throws IOException {
        Connection con = super.tryAccept(0, TimeUnit.NANOSECONDS);
        if (con == null) {
            con = doConnect();
            forAccept(con);
        }

        waitForAccept(con);
        return con;
    }

    @Override
    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        if (time <= 0) {
            return time < 0 ? accept() : super.tryAccept(time, unit);
        }

        Connection con = super.tryAccept(0, unit);
        if (con == null) {
            time = unit.toNanos(time);
            unit = TimeUnit.NANOSECONDS;
            long start = System.nanoTime();

            con = doTryConnect(time, unit);
            if (con == null) {
                return null;
            }
            forAccept(con);

            if ((time -= (System.nanoTime() - start)) <= 0) {
                // Save for later.
                con.setReadTimeout(-1, unit);
                accepted(con);
                return null;
            }
        }

        con.setReadTimeout(time, unit);
        try {
            waitForAccept(con);
        } catch (SocketTimeoutException e) {
            // Save for later.
            con.setReadTimeout(-1, unit);
            accepted(con);
            return null;
        }

        // Default to infinite timeout.
        con.setReadTimeout(-1, unit);
        return con;
    }

    private Connection doConnect() throws IOException {
	checkClosed();
        Socket s = new Socket();
        s.connect(mAddress);
        return buffer(new SocketConnection(s));
    }

    private Connection doTryConnect(long time, TimeUnit unit) throws IOException {
	checkClosed();
        long timeMillis = unit.toMillis(time);
        if (timeMillis <= 0) {
            // Socket timeout of zero is interpreted as infinite.
            timeMillis = 1;
        } else if (timeMillis > Integer.MAX_VALUE) {
            // Go infinite.
            timeMillis = 0;
        }

        Socket s = new Socket();
        try {
            s.connect(mAddress, (int) timeMillis);
        } catch (SocketTimeoutException e) {
            return null;
        }

        return buffer(new SocketConnection(s));
    }

    private void forConnect(Connection con) throws IOException {
        con.getOutputStream().write(mConnectSessionId);
        con.getOutputStream().flush();
    }

    private void forAccept(Connection con) throws IOException {
        con.getOutputStream().write(mAcceptSessionId);
        con.getOutputStream().flush();
    }

    private void waitForAccept(Connection con) throws IOException {
        byte[] sessionId = readSessionId(con);
        if (!Arrays.equals(mAcceptSessionId, sessionId)) {
            throw new IOException("Session identifier does not match: " +
				  Arrays.toString(mAcceptSessionId) + " != " +
				  Arrays.toString(sessionId));
        }
    }

    protected Connection buffer(Connection con) throws IOException {
        return new BufferedConnection(con);
    }
}
