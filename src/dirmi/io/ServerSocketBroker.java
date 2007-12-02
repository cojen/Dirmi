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

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see ClientSocketBroker
 */
public class ServerSocketBroker implements Broker {
    static final byte FOR_CONNECT = 1;
    static final byte FOR_ACCEPT = 2;

    private final ServerSocket mServerSocket;
    private final ReentrantLock mAcceptLock;
    private final BlockingQueue<Connection> mReadyToConnect;

    // FIXME: create queue for accepted sockets

    private int mAcceptTimeout = -1;

    public ServerSocketBroker(ServerSocket ss) {
        mServerSocket = ss;
        mAcceptLock = new ReentrantLock();
        mReadyToConnect = new LinkedBlockingQueue<Connection>();
    }

    public Connection connect() throws IOException {
        Connection con;
        try {
            con = mReadyToConnect.take();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }

        con.getOutputStream().write(FOR_CONNECT);
        con.getOutputStream().flush();

        return con;
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Connection con;
        try {
            con = mReadyToConnect.poll(time, unit);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }

        if (con == null) {
            return null;
        }

        con.getOutputStream().write(FOR_CONNECT);
        con.getOutputStream().flush();

        return con;
    }

    public Connection accept() throws IOException {
        while (true) {
            Socket s;
            mAcceptLock.lock();
            try {
                if (mAcceptTimeout != 0) {
                    mServerSocket.setSoTimeout(mAcceptTimeout = 0);
                }
                s = mServerSocket.accept();
            } finally {
                mAcceptLock.unlock();
            }

            Connection con = buffer(new SocketConnection(s));
            int purpose = con.getInputStream().read();

            if (purpose == FOR_CONNECT) {
                return con;
            } else if (purpose == FOR_ACCEPT) {
                if (!mReadyToConnect.offer(con)) {
                    con.close();
                }
            } else {
                con.close();
            }
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        if (time < 0) {
            return accept();
        }

        time = unit.toNanos(time);
        unit = TimeUnit.NANOSECONDS;
        long start = System.nanoTime();

        while (true) {
            Socket s;
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
                    break;
                }
                start = now;

                long timeMillis = unit.toMillis(time);
                if (timeMillis > Integer.MAX_VALUE) {
                    // Socket timeout of zero is interpreted as infinite.
                    timeMillis = 0;
                } else if (timeMillis <= 0) {
                    timeMillis = 1;
                }

                if (mAcceptTimeout != timeMillis) {
                    mServerSocket.setSoTimeout(mAcceptTimeout = ((int) timeMillis));
                }

                try {
                    s = mServerSocket.accept();
                } catch (SocketTimeoutException e) {
                    return null;
                }
            } finally {
                mAcceptLock.unlock();
            }

            Connection con = buffer(new SocketConnection(s));
            int purpose = con.getInputStream().read();

            if (purpose == FOR_CONNECT) {
                return con;
            } else if (purpose == FOR_ACCEPT) {
                if (!mReadyToConnect.offer(con)) {
                    con.close();
                }
            } else {
                con.close();
            }

            long now = System.nanoTime();
            if ((time -= (now - start)) <= 0) {
                break;
            }
            start = now;
        }

        return null;
    }

    protected Connection buffer(Connection con) throws IOException {
        return new BufferedConnection(con);
    }
}
