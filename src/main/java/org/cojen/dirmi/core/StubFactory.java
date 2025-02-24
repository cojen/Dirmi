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
 * Produces new StubInvoker instances for client-side Remote objects. A StubInvoker instance
 * marshals requests to a remote {@link Skeleton} which in turn calls the real method. Any
 * response is marshaled back for the StubInvoker to decode.
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
     * Creates a new stub instance. If it has any data fields, then they're in a latched state
     * until readDataAndUnlatch or readOrSkipData is called.
     *
     * @param id remote object identifier
     * @param support for invoking remote methods
     */
    protected abstract StubInvoker newStub(long id, StubSupport support);

    /**
     * If any data fields are defined, reads and sets them into a DataContainer.
     *
     * <p>Note: This method isn't defined in StubInvoker because the generated code cannot be
     * package-private.
     *
     * @param pipe can pass null if data is unavailable
     */
    protected void readDataAndUnlatch(StubInvoker stub, Pipe pipe) throws IOException {
    }

    /**
     * If any data fields are defined, reads and sets them into a DataContainer. If they're
     * already set, then they are skipped.
     *
     * <p>Note: This method isn't defined in StubInvoker because the generated code cannot be
     * package-private.
     *
     * @param pipe can pass null if data is unavailable
     */
    protected void readOrSkipData(StubInvoker stub, Pipe pipe) throws IOException {
    }

    /**
     * Returns a non-null DataContainer instance if any fields are defined.
     *
     * <p>Note: This method isn't defined in StubInvoker because the generated code cannot be
     * package-private.
     */
    protected DataContainer getData(StubInvoker stub) {
        return null;
    }

    /**
     * If any data fields are defined, explicitly set them. If the given DataContainer is null
     * or is the wrong type, then the data is set to be unavailable.
     *
     * <p>Note: This method isn't defined in StubInvoker because the generated code cannot be
     * package-private.
     */
    protected void setData(StubInvoker stub, DataContainer data) {
    }

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
