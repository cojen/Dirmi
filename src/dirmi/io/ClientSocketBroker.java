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

import java.io.IOException;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see ServerSocketBroker
 */
public class ClientSocketBroker implements Broker {
    // FIXME: support buffering

    private final SocketAddress mAddress;

    public ClientSocketBroker(SocketAddress address) {
        mAddress = address;
    }

    public Connection connect() throws IOException {
        Connection con = doConnect();
        con.getOutputStream().write(ServerSocketBroker.FOR_CONNECT);
        return con;
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Connection con = doTryConnect(time, unit);
        con.getOutputStream().write(ServerSocketBroker.FOR_CONNECT);
        return con;
    }

    public Connection accept() throws IOException {
        Connection con = doConnect();
        con.getOutputStream().write(ServerSocketBroker.FOR_ACCEPT);
        // FIXME: wait for one byte

        // FIXME
        return null;
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        Connection con = doTryConnect(time, unit);
        con.getOutputStream().write(ServerSocketBroker.FOR_ACCEPT);
        // FIXME: wait for one byte

        // FIXME
        return null;
    }

    private Connection doConnect() throws IOException {
        Socket s = new Socket();
        s.connect(mAddress);
        return new SocketConnection(s);
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

        return new SocketConnection(s);
    }
}
