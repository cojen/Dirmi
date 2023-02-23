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

package org.cojen.dirmi.core;

import java.io.IOException;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.UnimplementedException;

/**
 * Writes a method id as a byte or an int, possibly with a mapping step beforehand if the
 * remote skeleton has changed following a session reconnect.
 *
 * @author Brian S O'Neill
 */
public interface MethodIdWriter {
    /**
     * @throws UnimplementedException if the remote side doesn't implement the method
     */
    void writeMethodId(Pipe pipe, int id, String name) throws IOException;

    /**
     * Called by a stub method which is annotated as Unimplemented. The method id that's passed
     * in doesn't match any method id available on the remote side at the time the stub class
     * was generated.
     *
     * @throws UnimplementedException by default
     */
    default void writeSyntheticMethodId(Pipe pipe, int id, String name) throws IOException {
        throw new UnimplementedException("Unimplemented on the remote side");
    }

    static class Unimplemented implements MethodIdWriter {
        static final Unimplemented THE = new Unimplemented();

        private Unimplemented() {
        }

        @Override
        public void writeMethodId(Pipe pipe, int id, String name) throws IOException {
            throw new UnimplementedException("Unimplemented on the remote side: " + name);
        }
    }
}
