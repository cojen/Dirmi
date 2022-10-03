/*
 *  Copyright 2007-2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

import java.math.BigInteger;
import java.math.BigDecimal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pipe is a bidirectional stream which supports basic object serialization. Only simple
 * types and collections can be serialized, and the original classes aren't necessarily
 * preserved. Graph structure isn't preserved unless reference tracking is {@link
 * #enableReferences enabled}.
 *
 * <p>Pipes are only partially thread-safe. Reading and writing is concurrent, but at most one
 * thread can be reading, and at most one thread can be writing.
 *
 * <p>Pipes are fully buffered, and closing the pipe directly discards any buffered
 * writes. Closing the {@code OutputStream} will attempt to flush the buffer first, although it
 * can only be called from the thread which is allowed to perform writes. Closing either stream
 * has the side effect of also fully closing the pipe.
 *
 * <p>Here's an example remote method declaration which uses a pipe:
 *
 * {@snippet lang="java" :
 * Pipe uploadFile(String name, Pipe pipe) throws RemoteException;
 * }
 *
 * The remote method declaration requires the return type to be a pipe, and one parameter must
 * also be a pipe. The client-side invocation of the remote method simply passes null for the
 * pipe parameter, and the server-side implementation returns null instead of a pipe. Example
 * client call:
 *
 * {@snippet lang="java" :
 *     Pipe pipe = server.uploadFile("notes.txt", null);
 *     byte[] notes = ...
 *     pipe.writeInt(notes.length);
 *     pipe.write(notes);
 *     pipe.flush();
 *     pipe.readByte(); // read ack
 *     pipe.recycle();
 * }
 *
 * The remote method implementation might look like this:
 *
 * {@snippet lang="java" :
 * @Override
 * public Pipe uploadFile(String name, Pipe pipe) {
 *     byte[] notes = new byte[pipe.readInt()];
 *     pipe.readFully(notes);
 *     pipe.writeByte(1); // ack
 *     pipe.flush();
 *     pipe.recycle();
 *     ...
 *     return null;
 * }
 * }
 *
 * When using a pipe, writes must be explicitly flushed. When a client calls a piped method,
 * the flush method must be called to ensure that the method name and parameters are actually
 * sent to the remote endpoint. Care must be taken when recycling pipes. There must not be any
 * pending input or unflushed output, and the pipe must not be used again directly. Closing the
 * pipe is safer, although it might force a new pipe connection to be established.
 *
 * @author Brian S O'Neill
 */
public interface Pipe extends Closeable, Flushable, ObjectInput, ObjectOutput, Link {
    /**
     * Enables tracking of object references as they are written, for correctly serializing
     * object graphs, and to potentially reduce the overall encoding size. This mode has higher
     * memory overhead because each object flowing through the pipe must be remembered.
     *
     * <p>This method counts the number of times it's invoked, and a matching number of calls
     * to {@link #disableReferences} is required to fully disable the mode.
     */
    void enableReferences();

    /**
     * Disables tracking of object references. Memory isn't freed on the remote side until
     * it reads another object.
     *
     * @return true if fully disabled
     * @throws IllegalStateException if not currently enabled
     */
    boolean disableReferences();

    /**
     * Returns the pipe's {@code InputStream}, which also implements {@code
     * ObjectInput}. Closing the stream is equivalent to closing the pipe.
     */
    InputStream getInputStream();

    /**
     * Returns the pipe's {@code OutputStream}, which also implements {@code
     * ObjectOutput}. Closing the stream is equivalent to closing the pipe.
     */
    OutputStream getOutputStream();

    /**
     * Attempt to recycle the connection instead of closing it. The caller must ensure that the
     * pipe has no pending input or unflushed output.
     *
     * @throws IllegalStateException if it's detected that the pipe isn't in a recyclable state
     */
    void recycle() throws IOException;

    /**
     * Read and return an object. Unlike the inherited method, reading from a pipe never throws
     * a {@link ClassNotFoundException}.
     */
    @Override
    Object readObject() throws IOException;

    // The remaining methods are hidden to keep the javadocs from being too cluttered.

    /**
     * @hidden
     */
    void writeObject(Boolean v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Character v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Float v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Double v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Byte v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Short v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Integer v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Long v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(BigInteger v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(BigDecimal v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(String v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(boolean[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(char[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(float[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(double[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(byte[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(short[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(int[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(long[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Object[] v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Throwable v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(StackTraceElement v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(List<?> v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Set<?> v) throws IOException;

    /**
     * @hidden
     */
    void writeObject(Map<?,?> v) throws IOException;
}
