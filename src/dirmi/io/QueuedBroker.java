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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Broker which vends connections from queues.
 *
 * @author Brian S O'Neill
 */
public class QueuedBroker implements Broker {
    private final BlockingQueue<Connection> mReadyToConnect;
    private final BlockingQueue<Connection> mReadyToAccept;

    private volatile boolean mClosed;

    public QueuedBroker() {
        this(new LinkedBlockingQueue<Connection>(),
             new LinkedBlockingQueue<Connection>());
    }

    public QueuedBroker(BlockingQueue<Connection> connectQueue,
                        BlockingQueue<Connection> acceptQueue)
    {
        mReadyToConnect = connectQueue;
        mReadyToAccept = acceptQueue;
    }

    public Connection connect() throws IOException {
        checkClosed();
        try {
            Connection con = mReadyToConnect.take();
            if (con == Unconnection.THE) {
                connectClose(); // enqueue again for any other waiters
                throw new IOException("Broker is closed");
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        checkClosed();
        try {
            Connection con = mReadyToConnect.poll(time, unit);
            if (con == Unconnection.THE) {
                connectClose(); // enqueue again for any other waiters
                throw new IOException("Broker is closed");
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public Connection accept() throws IOException {
        checkClosed();
        try {
            Connection con = mReadyToAccept.take();
            if (con == Unconnection.THE) {
                acceptClose(); // enqueue again for any other waiters
                throw new IOException("Broker is closed");
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        checkClosed();
        try {
            Connection con = mReadyToAccept.poll(time, unit);
            if (con == Unconnection.THE) {
                acceptClose(); // enqueue again for any other waiters
                throw new IOException("Broker is closed");
            }
            return con;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    /**
     * Pass connection to connect queue, possibly blocking.
     */
    public void connected(Connection con) throws IOException {
        if (mClosed || mReadyToConnect == null) {
            con.close();
            return;
        }
        try {
            mReadyToConnect.put(con);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    /**
     * Pass connection to accept queue, possibly blocking.
     */
    public void accepted(Connection con) throws IOException {
        if (mClosed || mReadyToAccept == null) {
            con.close();
            return;
        }
        try {
            mReadyToAccept.put(con);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public void close() throws IOException {
        mClosed = true;

        // Drain any existing connections.
        if (mReadyToConnect != null) {
            Connection con;
            while ((con = mReadyToConnect.poll()) != null) {
                try {
                    con.close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
        }

        if (mReadyToAccept != null) {
            Connection con;
            while ((con = mReadyToAccept.poll()) != null) {
                try {
                    con.close();
                } catch (IOException e) {
                    // Don't care.
                }
            }
        }

        // Notify any waiters.
        connectClose();
        acceptClose();
    }

    protected void checkClosed() throws IOException {
        if (mClosed) {
            throw new IOException("Broker is closed");
        }
    }

    private void connectClose() throws IOException {
        if (mReadyToConnect != null) {
            try {
                mReadyToConnect.put(Unconnection.THE);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }

    private void acceptClose() throws IOException {
        if (mReadyToAccept != null) {
            try {
                mReadyToAccept.put(Unconnection.THE);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }
}
