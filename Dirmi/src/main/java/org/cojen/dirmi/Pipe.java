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

/**
 * A pipe is a bidirectional stream which can be passed via an {@link
 * Asynchronous asynchronous} remote method. Client and server see the streams
 * swapped with respect to each other. Pipes can remain open as long as the
 * session is open, and all pipes are closed when the session is closed.
 *
 * <p>Methods which use pipes may also utilize {@link Timeout timeouts}. It
 * only applies to the sending of the request, however. All other operations on
 * the pipe do not have a timeout applied.
 *
 * <pre>
 * &#64;Asynchronous
 * <b>Pipe</b> uploadFile(String name, <b>Pipe</b> pipe) throws RemoteException, IOException;
 * </pre>
 *
 * @author Brian S O'Neill
 */
public interface Pipe extends Flushable, Closeable, ObjectInput, ObjectOutput {
    /**
     * Returns the Pipe's InputStream which also implements ObjectInput.
     * Closing the stream is equivalent to closing the pipe.
     */
    InputStream getInputStream() throws IOException;

    /**
     * Returns the Pipe's OutputStream which also implements ObjectOutput.
     * Closing the stream is equivalent to closing the pipe.
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Reads a Throwable which was written via writeThrowable, which may be null.
     */
    Throwable readThrowable() throws IOException;

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
}
