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

import org.cojen.dirmi.core.SerializerMaker;

/**
 * Supports writing and reading of object instances to/from a pipe.
 *
 * @author Brian S O'Neill
 * @see Environment#customSerializers
 */
public interface Serializer<T> {
    /**
     * Generates and returns a serializer for a record type or simple class. A simple class
     * must have a public no-arg constructor, and only public fields are serialized. Static and
     * transient fields aren't serialized.
     *
     * @throws IllegalArgumentException if the given type isn't supported
     */
    static <T> Serializer<T> simple(Class<T> type) {
        return SerializerMaker.forClass(type);
    }

    /**
     * Writes a non-null object to the pipe.
     */
    void write(Pipe pipe, T obj) throws IOException;

    /**
     * Reads an object from the pipe, which can be null.
     */
    T read(Pipe pipe) throws IOException;
}
