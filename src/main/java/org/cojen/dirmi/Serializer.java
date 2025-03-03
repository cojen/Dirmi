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

import java.io.IOException;

import java.util.Set;

import java.util.function.Consumer;

import org.cojen.dirmi.core.SerializerMaker;
import org.cojen.dirmi.core.Stub;

/**
 * Supports writing and reading of object instances to/from a pipe.
 *
 * @author Brian S O'Neill
 * @see Environment#customSerializers
 */
public interface Serializer {
    /**
     * Generates and returns a serializer for a record type, an enum type, or a simple class. A
     * simple class must have a public no-arg constructor, and only public fields are
     * serialized. Static and transient fields aren't serialized either.
     *
     * @throws IllegalArgumentException if the given type isn't supported
     */
    static Serializer simple(Class<?> type) {
        return SerializerMaker.forClass(type);
    }

    /**
     * Returns a non-null set of classes.
     */
    Set<Class<?>> supportedTypes();

    /**
     * Writes a non-null object to the pipe.
     */
    void write(Pipe pipe, Object obj) throws IOException;

    /**
     * Reads an object from the pipe. The returned object can be null.
     */
    Object read(Pipe pipe) throws IOException;

    /**
     * Skip an object instead of reading it. By default, the read method is called.
     *
     * @param remoteConsumer receives all client-side remote objects, which aren't truly
     * skipped; can pass null to do nothing with them
     */
    default void skip(Pipe pipe, Consumer<Object> remoteConsumer) throws IOException {
        Object obj = read(pipe);
        if (remoteConsumer != null && obj instanceof Stub) {
            remoteConsumer.accept(obj);
        }
    }

    /**
     * Returns an opaque serializable object describing the encoding format. The descriptor
     * type itself can only depend on built-in serializers and is typically a string.
     */
    default Object descriptor() {
        return null;
    }

    /**
     * Adapts this serializer to conform to the given descriptor or else returns this instance
     * if no adaptation is required. If adaptation isn't possible, can return null to specify a
     * serializer which always reads and writes null, thus discarding the object entirely.
     *
     * @param descriptor an object which was provided by the {@link #descriptor} method
     */
    default Serializer adapt(Object descriptor) {
        return descriptor == null ? this : null;
    }
}
