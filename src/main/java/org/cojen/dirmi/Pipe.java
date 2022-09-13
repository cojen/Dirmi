/*
 *  Copyright 2022 Cojen.org
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
 * types and collections can be serialized, the original classes aren't necessarily preserved,
 * and graph structure isn't guaranteed.
 *
 * <p>Pipes are only partially thread-safe. Reading and writing can be performed by independent
 * threads, but concurrent reads and concurrent writes isn't supported.
 *
 * <p>Pipes are fully buffered, and closing the pipe directly discards any buffered
 * writes. Closing the {@code OutputStream} will attempt to flush the buffer first, although it
 * can only be called from the thread which is allowed to perform writes. Closing either stream
 * has the side effect of also fully closing the pipe.
 *
 * @author Brian S O'Neill
 */
public interface Pipe extends Closeable, Flushable, ObjectInput, ObjectOutput, Link {
    /**
     * Enables tracking of object references as they are written, for correctly serializing
     * object graphs, and to potentially reduce the overall encoding size. This mode has higher
     * memory overhead because each object flowing through the pipe must be remembered.
     *
     * <p>This method call is reentrant and it counts the number of times it was invoked. A
     * matching number of calls to {@link #disableReferences} is required to fully disable the
     * mode.
     */
    void enableReferences();

    /**
     * Disables tracking of object references. Memory isn't freed on the remote side until
     * another object is read.
     *
     * @return true if fully disabled
     * @throws IllegalStateException if not currently enabled
     */
    boolean disableReferences();

    /**
     * Returns the pipe's {@code InputStream} which also implements {@code
     * ObjectInput}. Closing the stream is equivalent to closing the pipe.
     */
    InputStream getInputStream();

    /**
     * Returns the pipe's {@code OutputStream} which also implements {@code
     * ObjectOutput}. Closing the stream is equivalent to closing the pipe.
     */
    OutputStream getOutputStream();

    /**
     * Attempt to recycle the connection instead of closing it. Caller must ensure that the
     * pipe has no pending input or unflushed output.
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
