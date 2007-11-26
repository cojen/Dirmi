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

/**
 * 
 *
 * @author Brian S O'Neill
 * @see ClientSocketBroker
 */
public class ServerSocketBroker implements Broker {
    // FIXME: support buffering

    static final byte FOR_CONNECT = 1;
    static final byte FOR_ACCEPT = 2;

    private final ServerSocket mServerSocket;
    private final BlockingQueue<Connection> mReadyToConnect;

    private int mAcceptTimeout = -1;

    public ServerSocketBroker(ServerSocket ss) {
        mServerSocket = ss;
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
            synchronized (mServerSocket) {
                if (mAcceptTimeout != 0) {
                    mServerSocket.setSoTimeout(mAcceptTimeout = 0);
                }
                s = mServerSocket.accept();
            }

            Connection con = new SocketConnection(s);
            int purpose = con.getInputStream().read();

            if (purpose == FOR_CONNECT) {
                return con;
            } else if (purpose == FOR_ACCEPT) {
                if (!mReadyToConnect.offer(con)) {
                    s.close();
                }
            } else {
                s.close();
            }
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        long timeMillis = unit.toMillis(time);
        if (timeMillis <= 0) {
            // Socket timeout of zero is interpreted as infinite.
            timeMillis = 1;
        } else if (timeMillis > Integer.MAX_VALUE) {
            // Go infinite.
            timeMillis = 0;
        }

        Socket s;
        synchronized (mServerSocket) {
            if (mAcceptTimeout != timeMillis) {
                mServerSocket.setSoTimeout(mAcceptTimeout = ((int) timeMillis));
            }
            try {
                s = mServerSocket.accept();
            } catch (SocketTimeoutException e) {
                return null;
            }
        }

        Connection con = new SocketConnection(s);
        int purpose = con.getInputStream().read();

        if (purpose == FOR_CONNECT) {
            return con;
        }

        // FIXME
        return null;
    }
}
