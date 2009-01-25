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

package org.cojen.dirmi;

import java.io.Closeable;
import java.io.Flushable;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;

/**
 * A pipe is a bidirectional stream which can be passed via an {@link
 * Asynchronous asynchronous} remote method. Pipes can remain open as long as
 * the session is open, and all pipes are closed when the session is
 * closed. Here's an example remote method declaration which uses a pipe:
 *
 * <pre>
 * <b>&#64;Asynchronous</b>
 * <b>Pipe</b> uploadFile(String name, <b>Pipe</b> pipe) throws RemoteException;
 * </pre>
 *
 * The remote method declaration requires the return type to be a pipe and one
 * parameter must also be a pipe. The client-side invocation of the remote
 * method simply passes null for the pipe parameter, and the server-side
 * implementation returns null instead of a pipe. Example client call:
 *
 * <pre>
 *     Pipe pipe = server.uploadFile("notes.txt", null);
 *     byte[] notes = ...
 *     pipe.writeInt(notes.length);
 *     pipe.write(notes);
 *     pipe.close();
 * </pre>
 *
 * The remote method implementation might look like this:
 *
 * <pre>
 * public Pipe uploadFile(String name, Pipe pipe) {
 *     byte[] notes = new byte[pipe.readInt()];
 *     pipe.readFully(notes);
 *     pipe.close();
 *     ...
 *     return null;
 * }
 * </pre>
 *
 * Pipes are an extension of the remote method invocation itself, which is why
 * only one pipe can be passed per call. Any arguments which were passed along
 * with the pipe are written to the same underlying object stream. For
 * long-lived pipes, be sure to call {@link #reset reset} occasionally to allow
 * any previously written objects to be freed.
 *
 * <p>If the pipe is used to pass additional method arguments, consider
 * declaring the method with the {@link CallMode#EVENTUAL eventual} calling
 * mode. By calling {@link #flush flush} after all arguments are written, the
 * number of transported packets is reduced. For the above example, the remote
 * method can be declared as:
 *
 * <pre>
 * <b>&#64;Asynchronous(CallMode.EVENTUAL)</b>
 * Pipe uploadFile(String name, Pipe pipe) throws RemoteException;
 * </pre>
 *
 * The example client call doesn't need to do anything different, since closing
 * the pipe implicitly flushes it. If the server is required to return a value
 * over the pipe, then the client call might be written as:
 *
 * <pre>
 *     Pipe pipe = server.uploadFile("notes.txt", null);
 *     byte[] notes = ...
 *     pipe.writeInt(notes.length);
 *     pipe.write(notes);
 *     // Flush to ensure arguments and file are transported.
 *     pipe.flush();
 *     // Read server response.
 *     Object response = pipe.readObject();
 *     pipe.close();
 * </pre>
 *
 * Methods which use pipes may also utilize {@link Timeout timeout}
 * annotations. A timeout task is started when the remote method is invoked,
 * and it must be explicitly {@link #cancelTimeout cancelled} upon receiving
 * the pipe. Timeout tasks may also be explicitly {@link #startTimeout started}
 * without requiring the annotation.
 *
 * @author Brian S O'Neill
 */
public interface Pipe extends Flushable, Closeable, ObjectInput, ObjectOutput {
    /**
     * Returns the pipe's InputStream which also implements ObjectInput.
     * Closing the stream is equivalent to closing the pipe.
     */
    InputStream getInputStream() throws IOException;

    /**
     * Returns the pipe's OutputStream which also implements ObjectOutput.
     * Closing the stream is equivalent to closing the pipe.
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Reads a Throwable which was written via writeThrowable, which may be null.
     */
    Throwable readThrowable() throws IOException, ReconstructedException;

    /**
     * Writes the given Throwable with additional remote information. Throwable
     * may be null.
     */
    void writeThrowable(Throwable t) throws IOException;

    /**
     * Disregard the state of any objects already written to the pipe, allowing
     * them to get freed.
     */
    void reset() throws IOException;

    /**
     * Flushes the pipe by writing any buffered output to the transport layer.
     */
    void flush() throws IOException;

    /**
     * Starts a task which forcibly closes this pipe after the timeout has
     * elapsed, unless it is {@link #cancelTimeout cancelled} in time. If a
     * timeout is already in progress when this method is called, it is
     * replaced with a new timeout.
     *
     * @return false if task could not be started because existing timeout
     * could not be cancelled or pipe is closed
     * @throws IOException if task could not be scheduled
     */
    boolean startTimeout(long timeout, TimeUnit unit) throws IOException;

    /**
     * Cancels a timeout task which was started by {@link #startTimeout
     * startTimeout} or an initial timeout when using the {@link
     * CallMode#EVENTUAL eventual} calling mode. If no timeout task exists when
     * this method is called, it does nothing and returns true.
     *
     * @return false if too late to cancel task and pipe will be closed
     */
    boolean cancelTimeout();

    /**
     * Closes both the input and output of the pipe. Any buffered output is
     * flushed first.
     */
    void close() throws IOException;
}
