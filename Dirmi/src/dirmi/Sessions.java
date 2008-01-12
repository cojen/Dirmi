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
import java.net.Socket;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import dirmi.io.Connection;
import dirmi.io.Multiplexer;
import dirmi.io.SocketConnection;

import dirmi.core.StandardSession;
import dirmi.core.StandardSessionServer;
import dirmi.core.ThreadPool;

/**
 * {@link Session} and {@link SessionServer} factories.
 *
 * @author Brian S O'Neill
 */
public class Sessions {
    private static final int DEFAULT_TCP_BUFFER_SIZE = 1 << 16;

    private static volatile ScheduledExecutorService cDefaultSessionExecutor;

    private static ScheduledExecutorService defaultSessionExecutor() {
        ScheduledExecutorService executor = cDefaultSessionExecutor;
        if (executor == null) {
            synchronized (Sessions.class) {
                executor = cDefaultSessionExecutor;
                if (executor == null) {
                    cDefaultSessionExecutor = executor =
                        new ThreadPool(Integer.MAX_VALUE, true, "session");
                }
            }
        }
        return executor;
    }

    /**
     * @param host name of remote host
     * @param port remote port
     */
    public static Session createSession(String host, int port) throws IOException {
        return createSession(new Socket(host, port), null, null);
    }

    /**
     * @param con fresh remote connection
     */
    public static Session createSession(Socket con) throws IOException {
        return createSession(con, null, null);
    }

    /**
     * @param con fresh remote connection
     * @param server optional remote or serializable object to export
     * @param executor task executor; pass null for default
     */
    public static Session createSession(Socket con, Object server,
                                        ScheduledExecutorService executor)
        throws IOException
    {
        if (con.getSendBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setSendBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        if (con.getReceiveBufferSize() < DEFAULT_TCP_BUFFER_SIZE) {
            con.setReceiveBufferSize(DEFAULT_TCP_BUFFER_SIZE);
        }
        con.setTcpNoDelay(true);
        return createSession(new SocketConnection(con), server, executor);
    }

    /**
     * @param con fresh remote connection
     * @param server optional remote or serializable object to export
     * @param executor task executor; pass null for default
     */
    public static Session createSession(Connection con, Object server,
                                        ScheduledExecutorService executor)
        throws IOException
    {
        if (executor == null) {
            executor = defaultSessionExecutor();
        }
        return new StandardSession(new Multiplexer(con, executor), server, executor);
    }

    /**
     * @param port port for accepting socket connections
     * @param server remote or serializable object to export
     */
    public static SessionServer createSessionServer(int port, Object server)
        throws IOException
    {
        return createSessionServer(new ServerSocket(port), server);
    }

    /**
     * @param ss accepts sockets
     * @param server remote or serializable object to export
     */
    public static SessionServer createSessionServer(ServerSocket ss, Object server)
        throws IOException
    {
        return createSessionServer(ss, server, null);
    }

    /**
     * @param ss accepts sockets
     * @param server remote or serializable object to export
     * @param executor task executor; pass null for default
     */
    public static SessionServer createSessionServer(ServerSocket ss, Object server,
                                                    ScheduledExecutorService executor)
        throws IOException
    {
        if (executor == null) {
            executor = new ThreadPool(Integer.MAX_VALUE, false, "session-server");
        }
        return new StandardSessionServer(ss, server, executor);
    }

    private Sessions() {
    }
}
