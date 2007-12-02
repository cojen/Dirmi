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

package dirmi;

import java.io.IOException;

import java.net.ServerSocket;
import java.net.SocketAddress;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import dirmi.io.Broker;
import dirmi.io.ClientSocketBroker;
import dirmi.io.ServerSocketBroker;

import dirmi.core.StandardSession;
import dirmi.core.StandardSessionServer;
import dirmi.core.ThreadPool;

/**
 * Factories for creating {@link Session}s.
 *
 * @author Brian S O'Neill
 */
public class Sessions {
    private static final int DEFAULT_TCP_BUFFER_SIZE = 1 << 16;

    /**
     * @param host name of remote host
     * @param port remote port
     */
    public static Session createSession(String host, int port) throws IOException {
        return createSession(host, port, null);
    }

    /**
     * @param host name of remote host
     * @param port remote port
     * @param server optional server object to export
     */
    public static Session createSession(String host, int port, Object server) throws IOException {
        return createSession(new ClientSocketBroker(host, port), server);
    }

    /**
     * @param address address of remote host
     * @param server optional server object to export
     */
    public static Session createSession(SocketAddress address, Object server) throws IOException {
        /* FIXME: support this in ClientSocketBroker
        if (con.getSendBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setSendBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        if (con.getReceiveBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setReceiveBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        con.setTcpNoDelay(true);
        */
        return createSession(new ClientSocketBroker(address), server);
    }

    /**
     * @param ss socket for accepting connections
     * @param server optional server object to export
     */
    public static Session createSession(ServerSocket ss, Object server) throws IOException {
        /* FIXME: support this in ServerSocketBroker
        if (con.getSendBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setSendBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        if (con.getReceiveBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setReceiveBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        con.setTcpNoDelay(true);
        */
        return createSession(new ServerSocketBroker(ss), server);
    }

    /**
     * @param con remote connection
     * @param server optional server object to export
     */
    public static Session createSession(Broker broker, Object server) throws IOException {
        // FIXME: control max threads
        // FIXME: use globally shared pool
        Executor executor = new ThreadPool(100, true);
        return new StandardSession(con, server, executor);
    }

    /**
     * @param port port for accepting socket connections
     * @param export server object to export
     */
    public static SessionServer createSessionServer(int port, Object export)
        throws IOException
    {
        ServerSocket ss = new ServerSocket(port);
        return createSessionServer(ss, export, String.valueOf(ss.getLocalSocketAddress()));
    }

    /**
     * @param ss accepts sockets
     * @param export server object to export
     */
    public static SessionServer createSessionServer(ServerSocket ss, Object export, String name)
        throws IOException
    {
        if (name == null) {
            name = "SessionServer";
        } else {
            name = "SessionServer-" + name;
        }

        // FIXME: control threads
        Executor executor = new ThreadPool(100, false, name);

        return new StandardSessionServer(ss, export, executor);
    }
}
