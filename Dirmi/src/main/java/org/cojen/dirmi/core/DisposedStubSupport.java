/*
 *  Copyright 2009-2010 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Completion;
import org.cojen.dirmi.NoSuchObjectException;

/**
 * StubSupport implementation returned by the disposed method.
 *
 * @author Brian S O'Neill
 */
class DisposedStubSupport extends AbstractStubSupport {
    DisposedStubSupport(VersionedIdentifier id) {
        super(id);
    }

    @Override
    public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx)
        throws T
    {
        throw remoteException
            (remoteFailureEx, new NoSuchObjectException("Object has been disposed: " + mObjId));
    }

    @Override
    public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx,
                                                          long timeout, TimeUnit unit)
        throws T
    {
        return invoke(remoteFailureEx);
    }

        @Override
    public <T extends Throwable> InvocationChannel invoke(Class<T> remoteFailureEx,
                                                          double timeout, TimeUnit unit)
        throws T
    {
        return invoke(remoteFailureEx);
    }

    @Override
    public <T extends Throwable, R extends Remote> R createBatchedRemote
                                           (Class<T> remoteFailureEx,
                                            InvocationChannel channel,
                                            Class<R> type)
        throws T
    {
        throw new IllegalStateException();
    }

    @Override
    public void batched(InvocationChannel channel) {
        throw new IllegalStateException();
    }

    @Override
    public void batchedAndCancelTimeout(InvocationChannel channel) {
        throw new IllegalStateException();
    }

    @Override
    public void release(InvocationChannel channel) {
        throw new IllegalStateException();
    }

    @Override
    public void finished(InvocationChannel channel, boolean reset) {
        throw new IllegalStateException();
    }

    @Override
    public void finishedAndCancelTimeout(InvocationChannel channel, boolean reset) {
        throw new IllegalStateException();
    }

    @Override
    public <T extends Throwable> T failed(Class<T> remoteFailureEx,
                                          InvocationChannel channel,
                                          Throwable cause)
    {
        throw new IllegalStateException();
    }

    @Override
    public StubSupport dispose() {
        return this;
    }
}
