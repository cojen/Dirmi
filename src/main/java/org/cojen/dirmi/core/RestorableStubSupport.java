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

import java.lang.invoke.MethodHandle;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * Support implementation used by restorable stubs after the session has reconnected. Once
 * restored, then this RestorableStubSupport instance isn't used by the stub anymore.
 *
 * @author Brian S O'Neill
 */
final class RestorableStubSupport implements StubSupport {
    private final CoreStubSupport mSupport;

    RestorableStubSupport(CoreStubSupport support) {
        mSupport = support;
    }

    @Override
    public Session session() {
        return mSupport.session();
    }

    @Override
    public Pipe unbatch() {
        return mSupport.unbatch();
    }

    @Override
    public void rebatch(Pipe pipe) {
        mSupport.rebatch(pipe);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Throwable> Pipe connect(Stub stub, Class<T> remoteFailureException) throws T {
        var origin = (MethodHandle) Stub.cOriginHandle.getAcquire(stub);

        StubSupport newSupport;

        try {
            // FIXME: Use a CountDownLatch to only perform one restore.
            var newStub = (Stub) origin.invoke();
            mSupport.session().mStubs.stealIdentity(stub, newStub);
            newSupport = (StubSupport) Stub.cSupportHandle.getAcquire(newStub);
            Stub.cSupportHandle.setRelease(stub, newSupport);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw CoreUtils.remoteException(remoteFailureException, e);
        }

        return newSupport.connect(stub, remoteFailureException);
    }

    @Override
    public long remoteTypeId(Class<?> type) {
        return mSupport.remoteTypeId(type);
    }

    @Override
    public <T extends Throwable> Object newAliasStub(Class<T> remoteFailureException,
                                                     long aliasId, long typeId)
        throws T
    {
        return mSupport.newAliasStub(remoteFailureException, aliasId, typeId);
    }

    @Override
    public boolean isBatching(Pipe pipe) {
        return mSupport.isBatching(pipe);
    }

    @Override
    public boolean finishBatch(Pipe pipe) {
        return mSupport.finishBatch(pipe);
    }

    @Override
    public Throwable readResponse(Pipe pipe) throws IOException {
        return mSupport.readResponse(pipe);
    }

    @Override
    public void finished(Pipe pipe) {
        mSupport.finished(pipe);
    }

    @Override
    public void batched(Pipe pipe) {
        mSupport.batched(pipe);
    }

    @Override
    public <T extends Throwable> T failed(Class<T> remoteFailureException,
                                          Pipe pipe, Throwable cause)
    {
        return mSupport.failed(remoteFailureException, pipe, cause);
    }

    @Override
    public StubSupport dispose(Stub stub) {
        return mSupport.dispose(stub);
    }
}
