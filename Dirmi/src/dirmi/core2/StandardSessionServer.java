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

package dirmi.core2;

import java.io.IOException;

import java.net.SocketAddress;

import java.rmi.RemoteException;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.cojen.util.WeakCanonicalSet;

import dirmi.Session;
import dirmi.SessionServer;

import dirmi.nio2.MessageAcceptor;
import dirmi.nio2.MessageChannel;
import dirmi.nio2.MessageListener;
import dirmi.nio2.MultiplexedStreamBroker;
import dirmi.nio2.SocketMessageProcessor;
import dirmi.nio2.StreamBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionServer implements SessionServer {
    final SocketMessageProcessor mProcessor;
    final MessageAcceptor mAcceptor;

    final Object mServer;
    final ScheduledExecutorService mExecutor;
    final Log mLog;

    final WeakCanonicalSet<Session> mSessions;

    volatile boolean mClosing;

    /**
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     */
    public StandardSessionServer(SocketAddress bindpoint, Object server,
                                 ScheduledExecutorService executor)
        throws IOException
    {
        this(bindpoint, server, executor, null);
    }

    /**
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     * @param log message log; pass null for default
     */
    public StandardSessionServer(SocketAddress bindpoint, Object server,
                                 ScheduledExecutorService executor, Log log)
        throws IOException
    {
        if (bindpoint == null) {
            throw new IllegalArgumentException("SocketAddress bindpoint is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (log == null) {
            log = LogFactory.getLog(SessionServer.class);
        }

        mProcessor = new SocketMessageProcessor(executor);
        mAcceptor = mProcessor.newAcceptor(bindpoint);

        mServer = server;
        mExecutor = executor;
        mLog = log;

        mSessions = new WeakCanonicalSet<Session>();

        mAcceptor.accept(new Handler());
    }

    public void close() throws RemoteException {
        mClosing = true;

        try {
            mProcessor.close();
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

    private class Handler implements MessageListener {
        public void established(MessageChannel channel) {
            if (!mClosing) {
                mAcceptor.accept(this);
            }
            try {
                StreamBroker broker = new MultiplexedStreamBroker(channel);
                Session session = new StandardSession(broker, mServer, mExecutor);
                mSessions.put(session);
            } catch (IOException e) {
                warn("Unable to create session on channel: " + channel, e);
            }
        }

        public void failed(IOException e) {
            String message = "Failure accepting channel";
            error(message, e);
        }
    }
}
