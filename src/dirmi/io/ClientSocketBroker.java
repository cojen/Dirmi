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

import java.io.InterruptedIOException;
import java.io.IOException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see ServerSocketBroker
 */
public class ClientSocketBroker implements Broker {
    private final SocketAddress mAddress;

    private final ReentrantLock mAcceptLock;
    private Connection mReadyToAccept;

    public ClientSocketBroker(String host, int port) {
        this(new InetSocketAddress(host, port));
    }

    public ClientSocketBroker(SocketAddress address) {
        mAddress = address;
        mAcceptLock = new ReentrantLock();
    }

    public Connection connect() throws IOException {
        Connection con = doConnect();
        con.getOutputStream().write(ServerSocketBroker.FOR_CONNECT);
        con.getOutputStream().flush();
        return con;
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Connection con = doTryConnect(time, unit);
        con.getOutputStream().write(ServerSocketBroker.FOR_CONNECT);
        con.getOutputStream().flush();
        // Default to infinite timeout.
        con.setReadTimeout(-1, unit);
        return con;
    }

    public Connection accept() throws IOException {
        mAcceptLock.lock();
        try {
            Connection con;
            if ((con = mReadyToAccept) != null) {
                mReadyToAccept = null;
            } else {
                con = doConnect();
                con.getOutputStream().write(ServerSocketBroker.FOR_ACCEPT);
                con.getOutputStream().flush();
            }

            // Wait for acceptance.
            con.getInputStream().read();

            return con;
        } finally {
            mAcceptLock.unlock();
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        if (time < 0) {
            return accept();
        }

        time = unit.toNanos(time);
        unit = TimeUnit.NANOSECONDS;
        long start = System.nanoTime();

        try {
            if (!mAcceptLock.tryLock(time, unit)) {
                return null;
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
        try {
            long now = System.nanoTime();
            if ((time -= (now - start)) <= 0) {
                return null;
            }
            start = now;

            Connection con;
            if ((con = mReadyToAccept) != null) {
                mReadyToAccept = null;
            } else {
                con = doTryConnect(time, unit);
                if (con == null) {
                    return null;
                }
                con.getOutputStream().write(ServerSocketBroker.FOR_ACCEPT);
                con.getOutputStream().flush();

                now = System.nanoTime();
                if ((time -= (now - start)) <= 0) {
                    // Save for later.
                    con.setReadTimeout(-1, unit);
                    mReadyToAccept = con;
                    return null;
                }
                start = now;
            }

            con.setReadTimeout(time, unit);

            // Wait for acceptance.
            try {
                con.getInputStream().read();
            } catch (SocketTimeoutException e) {
                // Save for later.
                con.setReadTimeout(-1, unit);
                mReadyToAccept = con;
                return null;
            }

            // Default to infinite timeout.
            con.setReadTimeout(-1, unit);
            return con;
        } finally {
            mAcceptLock.unlock();
        }
    }

    private Connection doConnect() throws IOException {
        Socket s = new Socket();
        s.connect(mAddress);
        return buffer(new SocketConnection(s));
    }

    private Connection doTryConnect(long time, TimeUnit unit) throws IOException {
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

    protected Connection buffer(Connection con) throws IOException {
        return new BufferedConnection(con);
    }
}
