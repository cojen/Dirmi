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

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class CoreStubSupport implements StubSupport {
    private final CoreSession<?> mSession;
    private final ThreadLocal<Pipe> mLocalPipe;

    CoreStubSupport(CoreSession<?> session) {
        mSession = session;
        mLocalPipe = new ThreadLocal<>();
    }

    @Override
    public CoreSession session() {
        return mSession;
    }

    @Override
    public Pipe unbatch() {
        Pipe pipe = mLocalPipe.get();
        if (pipe != null) {
            mLocalPipe.remove();
        }
        return pipe;
    }

    @Override
    public void rebatch(Pipe pipe) {
        if (pipe != null) {
            mLocalPipe.set(pipe);
        }
    }

    @Override
    public <T extends Throwable> Pipe connect(Stub stub, Class<T> remoteFailureException) throws T {
        Pipe pipe = mLocalPipe.get();
        if (pipe != null) {
            return pipe;
        }
        try {
            return mSession.connect();
        } catch (Throwable e) {
            throw CoreUtils.remoteException(remoteFailureException, e);
        }
    }

    @Override
    public long remoteTypeId(Class<?> type) {
        return mSession.remoteTypeId(type);
    }

    @Override
    public <T extends Throwable> Object newAliasStub(Class<T> remoteFailureException,
                                                     long aliasId, long typeId)
        throws T
    {
        try {
            return mSession.objectFor(aliasId, typeId);
        } catch (Throwable e) {
            throw CoreUtils.remoteException(remoteFailureException, e);
        }
    }

    @Override
    public boolean isBatching(Pipe pipe) {
        return mLocalPipe.get() == pipe;
    }

    @Override
    public boolean finishBatch(Pipe pipe) {
        if (mLocalPipe.get() != pipe) {
            return false;
        } else {
            mLocalPipe.remove();
            return true;
        }
    }

    @Override
    public Throwable readResponse(Pipe pipe) throws IOException {
        var ex = (Throwable) pipe.readObject();
        if (ex != null) {
            CoreUtils.assignTrace(pipe, ex);
        }
        return ex;
    }

    @Override
    public void finished(Pipe pipe) {
        try {
            pipe.recycle();
        } catch (IOException e) {
            mSession.uncaughtException(e);
        }
    }

    @Override
    public void batched(Pipe pipe) {
        mLocalPipe.set(pipe);
    }

    @Override
    public <T extends Throwable> T failed(Class<T> remoteFailureException,
                                          Pipe pipe, Throwable cause)
    {
        mLocalPipe.remove();
        CoreUtils.closeQuietly(pipe);
        return CoreUtils.remoteException(remoteFailureException, cause);
    }

    @Override
    public StubSupport dispose(Stub stub) {
        return mSession.stubDispose(stub);
    }
}
