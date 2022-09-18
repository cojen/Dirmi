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

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class DisposedStubSupport implements StubSupport {
    static final DisposedStubSupport THE = new DisposedStubSupport();

    @Override
    public Session session() {
        throw new IllegalStateException("Object is disposed");
    }

    @Override
    public Pipe unbatch() {
        return null;
    }

    @Override
    public void rebatch(Pipe pipe) {
    }

    @Override
    public <T extends Throwable> Pipe connect(Class<T> remoteFailureException) throws T {
        throw CoreUtils.remoteException
            (remoteFailureException, new ClosedException("Object is disposed"));
    }

    @Override
    public long remoteTypeId(Class<?> type) {
        throw new IllegalStateException();
    }

    @Override
    public <T extends Throwable> Object newAliasStub(Class<T> remoteFailureException,
                                                     long aliasId, long typeId)
    {
        throw new IllegalStateException();
    }

    @Override
    public boolean isBatching(Pipe pipe) {
        return false;
    }

    @Override
    public boolean finishBatch(Pipe pipe) {
        return false;
    }

    @Override
    public Throwable readResponse(Pipe pipe) {
        return null;
    }

    @Override
    public void finished(Pipe pipe) {
    }

    @Override
    public void batched(Pipe pipe) {
    }

    @Override
    public <T extends Throwable> T failed(Class<T> remoteFailureException,
                                          Pipe pipe, Throwable cause)
    {
        // This method isn't expected to be called, but do the right thing anyhow.
        return CoreUtils.remoteException(remoteFailureException, cause);
    }

    @Override
    public StubSupport dispose(Stub stub) {
        return this;
    }
}
