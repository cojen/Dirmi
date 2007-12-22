/*
 *  Copyright 2006 Brian S O'Neill
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

import java.io.Closeable;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Establishes {@link Connection}s between threads. To establish a connection,
 * one thread must call accept, and another must call connect.
 *
 * <p>Connections produced by PipedBroker support timeouts, and if an
 * InterruptedIOException is thrown, the connection is still valid. For write
 * operations, the amount of bytes transferred before timing out is always
 * zero. That is, a write operation which times out is never partially
 * successful.
 *
 * @author Brian S O'Neill
 */
public class PipedBroker implements Broker {
    private final BlockingQueue<Con> mAcceptQueue;

    public PipedBroker() {
        mAcceptQueue = new SynchronousQueue<Con>();
    }

    public Connection connect() throws IOException {
        Con con = new Con();
        try {
            mAcceptQueue.put(new Con(con));
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
        return con;
    }

    public Connection tryConnect(long time, TimeUnit unit) throws IOException {
        Con con = new Con();
        try {
            if (!mAcceptQueue.offer(new Con(con), time, unit)) {
                return null;
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
        return con;
    }

    public Connection accept() throws IOException {
        try {
            return mAcceptQueue.take();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public Connection tryAccept(long time, TimeUnit unit) throws IOException {
        try {
            return mAcceptQueue.poll(time, unit);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public void close() throws IOException {
        // FIXME
    }

    private static class Con implements Connection {
        private final PipedInputStream mIn;
        private final PipedOutputStream mOut;

        Con() {
            mIn = new PipedInputStream();
            mOut = new PipedOutputStream();
        }

        Con(Con con) throws IOException {
            mIn = new PipedInputStream(con.mOut);
            mOut = new PipedOutputStream(con.mIn);
        }

        public InputStream getInputStream() throws IOException {
            return mIn;
        }

        public long getReadTimeout() throws IOException {
            return mIn.getReadTimeout();
        }

        public TimeUnit getReadTimeoutUnit() throws IOException {
            return mIn.getReadTimeoutUnit();
        }

        public void setReadTimeout(long time, TimeUnit unit) throws IOException {
            mIn.setReadTimeout(time, unit);
        }

        public OutputStream getOutputStream() throws IOException {
            return mOut;
        }

        public long getWriteTimeout() throws IOException {
            return mOut.getWriteTimeout();
        }

        public TimeUnit getWriteTimeoutUnit() throws IOException {
            return mOut.getWriteTimeoutUnit();
        }

        public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
            mOut.setWriteTimeout(time, unit);
        }

        public String getLocalAddressString() {
            return null;
        }

        public String getRemoteAddressString() {
            return null;
        }

        public void close() throws IOException {
            mOut.close();
            mIn.close();
        }
    }
}
