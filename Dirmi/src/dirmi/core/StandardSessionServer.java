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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.cojen.util.WeakCanonicalSet;

import dirmi.Session;
import dirmi.Sessions;
import dirmi.SessionServer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionServer implements SessionServer {
    final ServerSocket mServerSocket;
    final Object mServer;
    final ScheduledExecutorService mExecutor;
    final Log mLog;

    final WeakCanonicalSet<Session> mSessions;

    volatile boolean mClosing;

    /**
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     */
    public StandardSessionServer(ServerSocket ss, Object server,
                                 ScheduledExecutorService executor)
        throws IOException
    {
        this(ss, server, executor, null);
    }

    /**
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     * @param log message log; pass null for default
     */
    public StandardSessionServer(ServerSocket ss, Object server,
                                 ScheduledExecutorService executor, Log log)
        throws IOException
    {
        if (ss == null) {
            throw new IllegalArgumentException("ServerSocket is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (log == null) {
            log = LogFactory.getLog(SessionServer.class);
        }

        mServerSocket = ss;
        mServer = server;
        mExecutor = executor;
        mLog = log;

        mSessions = new WeakCanonicalSet<Session>();

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
            for (Session session : mSessions) {
                try {
                    session.close();
                } catch (RemoteException e) {
                    warn("Failed to close session: " + session, e);
                }
            }
            mSessions.clear();
        }
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

                Session session;
                try {
                    session = Sessions.createSession(s, mServer, mExecutor);
                } catch (IOException e) {
                    warn("Unable to create session on socket: " + s, e);
                    return;
                }

                mSessions.put(session);
            } while (!spawned);
        }
    }
}
