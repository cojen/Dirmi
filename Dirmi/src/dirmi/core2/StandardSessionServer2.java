/*
 *  Copyright 2008 Brian S O'Neill
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

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Session;
import dirmi.SessionServer2;

import dirmi.nio2.StreamBroker;
import dirmi.nio2.StreamBrokerAcceptor;
import dirmi.nio2.StreamListener;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionServer2 implements SessionServer2 {
    private final StreamBrokerAcceptor mAcceptor;
    private final Object mServer;
    private final ScheduledExecutorService mExecutor;
    private final Log mLog;

    public StandardSessionServer2(StreamBrokerAcceptor acceptor, Object server,
                                  ScheduledExecutorService executor)
    {
        this(acceptor, server, executor, null);
    }

    /**
     * @param server optional server object to export
     * @param executor shared executor for remote methods
     * @param log message log; pass null for default
     */
    public StandardSessionServer2(StreamBrokerAcceptor acceptor, Object server,
                                  ScheduledExecutorService executor, Log log)
    {
        if (acceptor == null) {
            throw new IllegalArgumentException("Broker acceptor is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (log == null) {
            log = LogFactory.getLog(SessionServer2.class);
        }

        mAcceptor = acceptor;
        mServer = server;
        mExecutor = executor;
        mLog = log;
    }

    public Session accept() throws IOException {
        StreamBroker broker = mAcceptor.accept();
        return new StandardSession(broker, mServer, mExecutor, mLog);
    }

    public void close() throws IOException {
        mAcceptor.close();
    }
}
