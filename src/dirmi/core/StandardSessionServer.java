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

import java.io.IOException;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.rmi.RemoteException;

import java.util.Arrays;
import java.util.Map;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.cojen.util.WeakValuedHashMap;

import dirmi.Session;
import dirmi.SessionServer;

import dirmi.io.BufferedConnection;
import dirmi.io.Connection;
import dirmi.io.SocketConnection;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionServer implements SessionServer {
    final ServerSocket mServerSocket;
    final Object mExport;
    final Executor mExecutor;
    final Log mLog;

    final Map<Identifier, Session> mSessions;

    volatile boolean mClosing;

    public StandardSessionServer(ServerSocket ss, Object export, Executor executor)
        throws IOException
    {
        this(ss, export, executor, null);
    }

    public StandardSessionServer(ServerSocket ss, Object export, Executor executor, Log log)
        throws IOException
    {
        if (ss == null) {
            throw new IllegalArgumentException("ServerSocket is null");
        }
        if (executor == null) {
            executor = new ThreadPool(Integer.MAX_VALUE, false);
        }
        if (log == null) {
            log = LogFactory.getLog(SessionServer.class);
        }

        mServerSocket = ss;
        mExport = export;
        mExecutor = executor;
        mLog = log;

        mSessions = new WeakValuedHashMap<Identifier, Session>();

        try {
            // Start first accept thread.
            mExecutor.execute(new Accepter());
        } catch (RejectedExecutionException e) {
            String message = "Unable to start accept thread";
            IOException io = new IOException(message);
            io.initCause(e);
            throw io;
        }
    }

    public void close() throws RemoteException {
        mClosing = true;

        try {
            mServerSocket.close();
        } catch (IOException e) {
            throw new RemoteException(e.getMessage(), e);
        }

        synchronized (mSessions) {
            for (Session session : mSessions.values()) {
                try {
                    session.close();
                } catch (RemoteException e) {
                    warn("Failed to close session: " + session, e);
                }
            }
            mSessions.clear();
        }
    }

    protected Connection buffer(Connection con) throws IOException {
        return new BufferedConnection(con);
    }

    void warn(String message) {
        mLog.warn(message);
    }

    void warn(String message, Throwable e) {
        mLog.warn(message, e);
    }

    void error(String message) {
        mLog.error(message);
    }

    void error(String message, Throwable e) {
        mLog.error(message, e);
    }

    private class Accepter implements Runnable {
        public void run() {
            boolean spawned;
            do {
                Socket s;
                try {
                    s = mServerSocket.accept();
                    if (mClosing) {
                        s.close();
                        return;
                    }
                } catch (IOException e) {
                    if (!mClosing && !mServerSocket.isClosed()) {
                        error("Unable to accept socket; exiting thread: " + mServerSocket, e);
                    }
                    return;
                }

                // Spawn a replacement accepter.
                try {
                    mExecutor.execute(new Accepter());
                    spawned = true;
                } catch (RejectedExecutionException e) {
                    spawned = false;
                }

                InetAddress remoteAddress = s.getInetAddress();

                try {
                    Connection con = buffer(new SocketConnection(s));
                    Identifier id = Identifier.read(con.getInputStream());

                    ServerSocketBroker broker = (ServerSocketBroker) id.tryRetrieve();

                    boolean makeNewSession = false;
                    if (broker == null) {
                        ServerSocketBroker newBroker = new ServerSocketBroker(remoteAddress, id);
                        broker = id.register(newBroker);
                        if (broker == newBroker) {
                            makeNewSession = true;
                        }
                    }

                    if (!broker.getRemoteAddress().equals(remoteAddress)) {
                        warn("Mismatched remote address: " + broker.getRemoteAddress() +
                             " != " + remoteAddress);
                        forceClose(s);
                        return;
                    }

                    byte forWhat = (byte) con.getInputStream().read();
                    if (forWhat == ClientSocketBroker.FOR_CONNECT) {
                        // Client connect is seen as server accept.
                        broker.accepted(con);
                    } else if (forWhat == ClientSocketBroker.FOR_ACCEPT) {
                        // Client accept is seen as server connect.
                        broker.connected(con);
                    } else {
                        warn("Unknown connection option: " + forWhat);
                        forceClose(s);
                        return;
                    }

                    if (makeNewSession) {
                        Session session = new StandardSession(broker, mExport, mExecutor);
                        mSessions.put(id, session);
                    }
                } catch (IOException e) {
                    warn("Unable to create session on socket: " + s, e);
                    forceClose(s);
                    return;
                }
            } while (!spawned);
        }

        private void forceClose(Socket s) {
            try {
                s.close();
            } catch (IOException e) {
                // Don't care.
            }
        }
    }
}
