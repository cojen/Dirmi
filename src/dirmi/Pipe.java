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
import java.io.Flushable;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import dirmi.io.ReadTimeout;
import dirmi.io.WriteTimeout;

/**
 * A pipe is a bidirectional stream which can be passed via an asynchronous
 * remote method. Client and server see the streams swapped with respect to
 * each other. Pipes can remain open as long as the session is open.
 *
 * <p>Pipes can also be used for transporting serializable objects or remote
 * objects.
 *
 * @author Brian S O'Neill
 */
public interface Pipe
    extends Flushable, Closeable, ObjectInput, ObjectOutput, ReadTimeout, WriteTimeout
{
    /**
     * Returns the Pipe's InputStream which also implements ObjectInput.
     */
    InputStream getInputStream() throws IOException;

    /**
     * Returns the Pipe's OutputStream which also implements ObjectOutput.
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Reads a Throwable which was written via writeThrowable.
     */
    Throwable readThrowable() throws IOException;

    /**
     * Writes the given Throwable with additional remote information.
     */
    void writeThrowable(Throwable t) throws IOException;

    /**
     * Disregard the state of any objects already written to the pipe, allowing
     * them to get freed.
     */
    void reset() throws IOException;

    void close() throws IOException;

    /**
     * Returns the timeout for blocking read operations. If timeout is
     * negative, blocking timeout is infinite. When a read times out, it throws
     * an InterruptedIOException.
     */
    long getReadTimeout() throws IOException;

    TimeUnit getReadTimeoutUnit() throws IOException;

    /**
     * Set the timeout for blocking read operations. If timeout is negative,
     * blocking timeout is infinite. When a read times out, it throws an
     * InterruptedIOException.
     */
    void setReadTimeout(long time, TimeUnit unit) throws IOException;

    /**
     * Returns the timeout for blocking write operations. If timeout is
     * negative, blocking timeout is infinite. When a write times out, it
     * throws an InterruptedIOException.
     */
    long getWriteTimeout() throws IOException;

    TimeUnit getWriteTimeoutUnit() throws IOException;

    /**
     * Set the timeout for blocking write operations. If timeout is negative,
     * blocking timeout is infinite. When a write times out, it throws an
     * InterruptedIOException.
     */
    void setWriteTimeout(long time, TimeUnit unit) throws IOException;
}
