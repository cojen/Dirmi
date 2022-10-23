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
import java.io.ObjectOutputStream;

import org.cojen.dirmi.Pipe;

/**
 * Used for @Serialized methods.
 *
 * @author Brian S O'Neill
 */
public final class CoreObjectOutputStream extends ObjectOutputStream {
    private final CorePipe mPipe;

    public CoreObjectOutputStream(Pipe pipe) throws IOException {
        this((CorePipe) pipe);
    }

    CoreObjectOutputStream(CorePipe pipe) throws IOException {
        super(pipe.outputStream());
        enableReplaceObject(true);
        mPipe = pipe;
    }

    @Override
    public final void drain() throws IOException {
        super.drain();
    }

    @Override
    protected final Object replaceObject(Object obj) throws IOException {
        if (obj instanceof Stub) {
            return new MarshalledStub((Stub) obj);
        } else if (CoreUtils.isRemote(obj.getClass())) {
            return CoreSession.marshallSkeleton(mPipe, obj);
        } else {
            return obj;
        }
    }
}
