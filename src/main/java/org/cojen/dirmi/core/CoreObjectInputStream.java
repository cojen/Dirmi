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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;

import org.cojen.dirmi.Pipe;

/**
 * Used for @Serialized methods.
 *
 * @author Brian S O'Neill
 */
public final class CoreObjectInputStream extends ObjectInputStream {
    private final CorePipe mPipe;

    public CoreObjectInputStream(Pipe pipe) throws IOException {
        this((CorePipe) pipe);
    }

    CoreObjectInputStream(CorePipe pipe) throws IOException {
        super(pipe.inputStream());
        enableResolveObject(true);
        mPipe = pipe;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc)
        throws IOException, ClassNotFoundException
    {
        return mPipe.mSession.resolveClass(desc.getName());
    }

    @Override
    protected final Object resolveObject(Object obj) throws IOException {
        if (obj instanceof MarshalledStub) {
            return mPipe.objectFor(((MarshalledStub) obj).id);
        } else if (obj instanceof MarshalledSkeleton) {
            var ms = (MarshalledSkeleton) obj;
            byte[] infoBytes = ms.infoBytes;
            if (infoBytes == null) {
                return mPipe.objectFor(ms.id, ms.typeId);
            }
            var bin = new ByteArrayInputStream(infoBytes);
            var pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());
            RemoteInfo info = RemoteInfo.readFrom(pipe);
            return mPipe.objectFor(ms.id, ms.typeId, info);
        } else {
            return obj;
        }
    }
}
