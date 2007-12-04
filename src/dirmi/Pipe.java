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

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import dirmi.io.ReadTimeout;
import dirmi.io.WriteTimeout;

/**
 * A pipe is a bidirectional byte stream which can be passed via a remote
 * method. The client and server see the streams swapped with respect to each
 * other. Pipes can remain open as long as the session is open.
 *
 * @author Brian S O'Neill
 */
public abstract class Pipe implements Remote, Closeable, ReadTimeout, WriteTimeout {
    /**
     * Create an initially unconnected pipe.
     */
    public static Pipe create() {
        return new Coupling();
    }

    /**
     * @throws IOException if not connected
     */
    public abstract InputStream getInputStream() throws IOException;

    /**
     * @throws IOException if not connected
     */
    public abstract OutputStream getOutputStream() throws IOException;

    public abstract void close() throws IOException;

    /**
     * Returns the timeout for blocking read operations. If timeout is
     * negative, blocking timeout is infinite. When a read times out, it throws
     * an InterruptedIOException.
     *
     * @throws IOException if not connected
     */
    public abstract long getReadTimeout() throws IOException;

    /**
     * @throws IOException if not connected
     */
    public abstract TimeUnit getReadTimeoutUnit() throws IOException;

    /**
     * Set the timeout for blocking read operations. If timeout is negative,
     * blocking timeout is infinite. When a read times out, it throws an
     * InterruptedIOException.
     *
     * @throws IOException if not connected
     */
    public abstract void setReadTimeout(long time, TimeUnit unit) throws IOException;

    /**
     * Returns the timeout for blocking write operations. If timeout is
     * negative, blocking timeout is infinite. When a write times out, it
     * throws an InterruptedIOException.
     *
     * @throws IOException if not connected
     */
    public abstract long getWriteTimeout() throws IOException;

    /**
     * @throws IOException if not connected
     */
    public abstract TimeUnit getWriteTimeoutUnit() throws IOException;

    /**
     * Set the timeout for blocking write operations. If timeout is negative,
     * blocking timeout is infinite. When a write times out, it throws an
     * InterruptedIOException.
     *
     * @throws IOException if not connected
     */
    public abstract void setWriteTimeout(long time, TimeUnit unit) throws IOException;

    /**
     * @throws IOException if already connected
     */
    public abstract void connect(Pipe pipe) throws IOException;

    private static class Coupling extends Pipe {
        private static final AtomicReferenceFieldUpdater<Coupling, Pipe> cPipeUpdater =
            AtomicReferenceFieldUpdater.newUpdater(Coupling.class, Pipe.class, "mPipe");

        private volatile Pipe mPipe;

        Coupling() {
        }

        public InputStream getInputStream() throws IOException {
            return pipe().getInputStream();
        }

        public OutputStream getOutputStream() throws IOException {
            return pipe().getOutputStream();
        }

        public void close() throws IOException {
            Pipe pipe = cPipeUpdater.getAndSet(this, null);
            if (pipe != null) {
                pipe.close();
            }
        }

        public long getReadTimeout() throws IOException {
            return pipe().getReadTimeout();
        }

        public TimeUnit getReadTimeoutUnit() throws IOException {
            return pipe().getReadTimeoutUnit();
        }

        public void setReadTimeout(long time, TimeUnit unit) throws IOException {
            pipe().setReadTimeout(time, unit);
        }

        public long getWriteTimeout() throws IOException {
            return pipe().getWriteTimeout();
        }

        public TimeUnit getWriteTimeoutUnit() throws IOException {
            return pipe().getWriteTimeoutUnit();
        }

        public void setWriteTimeout(long time, TimeUnit unit) throws IOException {
            pipe().setWriteTimeout(time, unit);
        }

        public void connect(Pipe pipe) throws IOException {
            if (!cPipeUpdater.compareAndSet(this, null, pipe)) {
                throw new IOException("Already connected");
            }
        }

        private Pipe pipe() throws IOException {
            Pipe pipe = mPipe;
            if (pipe == null) {
                throw new IOException("Not connected");
            }
            return pipe;
        }
    }
}
