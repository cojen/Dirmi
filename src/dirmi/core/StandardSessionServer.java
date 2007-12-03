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

    final Map<SessionKey, Session> mSessions;
    final Map<SessionKey, ServerSocketBroker> mSessionBrokers;

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

        mSessions = new WeakValuedHashMap<SessionKey, Session>();
        mSessionBrokers = new WeakValuedHashMap<SessionKey, ServerSocketBroker>();

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
            mSessionBrokers.clear();
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

                try {
                    Connection con = buffer(new SocketConnection(s));
                    byte[] sessionId = ClientSocketBroker.readSessionId(con);

                    SessionKey key = new SessionKey(sessionId);

                    ServerSocketBroker broker;
                    boolean newSession;
                    synchronized (mSessions) {
                        broker = mSessionBrokers.get(key);
                        if (broker == null) {
                            broker = new ServerSocketBroker(sessionId);
                            mSessionBrokers.put(key, broker);
                            newSession = true;
                        } else {
                            newSession = false;
                        }
                    }

                    if (sessionId[0] >= 0) {
                        // Client connect is seen as server accept.
                        broker.accepted(con);
                    } else {
                        // Client accept is seen as server connect.
                        broker.connected(con);
                    }

                    if (newSession) {
                        Session session = new StandardSession(broker, mExport, mExecutor);
                        synchronized (mSessions) {
                            // FIXME: what if another?
                            mSessions.put(key, session);
                        }
                    }
                } catch (IOException e) {
                    warn("Unable to create session on socket: " + s, e);
                    try {
                        s.close();
                    } catch (IOException e2) {
                        // Don't care.
                    }
                    return;
                }
            } while (!spawned);
        }
    }

    private static class SessionKey {
        private final byte[] mSessionId;
        private final int mHashCode;

        SessionKey(byte[] sessionId) {
            sessionId = sessionId.clone();
            sessionId[0] &= 0x7f;
            mSessionId = sessionId;
            mHashCode = Arrays.hashCode(sessionId);
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof SessionKey) {
                return Arrays.equals(mSessionId, ((SessionKey) obj).mSessionId);
            }
            return false;
        }
    }
}
