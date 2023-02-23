/*
 *  Copyright 2006-2022 Cojen.org
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

/**
 * Produces new Stub instances for client-side Remote objects. A Stub instance marshals
 * requests to a remote {@link Skeleton} which in turn calls the real method. Any response is
 * marshaled back for the Stub to decode.
 *
 * @author Brian S O'Neill
 */
public abstract class StubFactory extends Item implements MethodIdWriter {
    /**
     * @param typeId remote object type id
     */
    protected StubFactory(long typeId) {
        super(typeId);
    }

    /**
     * @param id remote object identifier
     * @param support for invoking remote methods
     */
    protected abstract Stub newStub(long id, StubSupport support);

    /**
     * Writes byte methodIds.
     */
    public static abstract class BW extends StubFactory {
        protected BW(long typeId) {
            super(typeId);
        }

        @Override
        public void writeMethodId(Pipe pipe, int methodId, String name) throws IOException {
            pipe.writeByte(methodId);
        }
    }

    /**
     * Writes short methodIds.
     */
    public static abstract class SW extends StubFactory {
        protected SW(long typeId) {
            super(typeId);
        }

        @Override
        public void writeMethodId(Pipe pipe, int methodId, String name) throws IOException {
            pipe.writeShort(methodId);
        }
    }

    /**
     * Writes int methodIds.
     */
    public static abstract class IW extends StubFactory {
        protected IW(long typeId) {
            super(typeId);
        }

        @Override
        public void writeMethodId(Pipe pipe, int methodId, String name) throws IOException {
            pipe.writeInt(methodId);
        }
    }
}
