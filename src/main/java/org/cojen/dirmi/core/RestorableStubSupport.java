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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.cojen.dirmi.DisposedException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * Support implementation used by restorable stubs after the session has reconnected. Once
 * restored, then this RestorableStubSupport instance isn't used by the stub anymore.
 *
 * @author Brian S O'Neill
 */
final class RestorableStubSupport extends ConcurrentHashMap<Stub, CountDownLatch>
    implements StubSupport
{
    private final CoreStubSupport mSupport;

    RestorableStubSupport(CoreStubSupport support) {
        mSupport = support;
    }

    @Override
    public Session session() {
        return mSupport.session();
    }

    @Override
    public void appendInfo(StringBuilder b) {
        b.append(", unrestored=").append(true);
    }

    @Override
    public <T extends Throwable> Pipe connect(Stub stub, Class<T> remoteFailureException) throws T {
        StubSupport newSupport = newSupport(stub, remoteFailureException);
        return newSupport.connect(stub, remoteFailureException);
    }

    @Override
    public <T extends Throwable> Pipe connectUnbatched(Stub stub, Class<T> remoteFailureException)
        throws T
    {
        StubSupport newSupport = newSupport(stub, remoteFailureException);
        return newSupport.connectUnbatched(stub, remoteFailureException);
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

    @SuppressWarnings("unchecked")
    private <T extends Throwable> StubSupport newSupport(Stub stub, Class<T> remoteFailureException)
        throws T
    {
        // Use a latch in order for only one thread to attempt the stub restore. Other threads
        // that come along must wait for the restore to complete.
        var latch = new CountDownLatch(1);

        StubSupport newSupport;

        while (true) {
            CountDownLatch existing = putIfAbsent(stub, latch);

            if (existing != null) {
                try {
                    existing.await();
                } catch (InterruptedException e) {
                    throw CoreUtils.remoteException(this, remoteFailureException, e);
                }
                newSupport = (StubSupport) Stub.cSupportHandle.getAcquire(stub);
                if (newSupport == this) {
                    // The restore by another thread was aborted, so try again.
                    continue;
                }
                return newSupport;
            }

            var origin = (MethodHandle) Stub.cOriginHandle.getAcquire(stub);

            try {
                var newStub = (Stub) origin.invoke();
                mSupport.session().mStubs.stealIdentity(stub, newStub);
                newSupport = (StubSupport) Stub.cSupportHandle.getAcquire(newStub);
                // Use CAS to detect if the stub has called dispose.
                var result = (StubSupport) Stub.cSupportHandle
                    .compareAndExchange(stub, this, newSupport);
                if (result != newSupport && result instanceof DisposedStubSupport) {
                    // Locally dispose the restored stub.
                    dispose(stub);
                }
                return newSupport;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                if (e instanceof DisposedException) {
                    String message = e.getMessage();
                    String prefix = "Object and origin are disposed";
                    if (message == null || !message.startsWith(prefix)) {
                        if (message == null || message == DisposedStubSupport.EXPLICIT_MESSAGE) {
                            message = prefix;
                        } else {
                            message = prefix + ": " + message;
                        }
                    }

                    var de = new DisposedException(message);
                    de.setStackTrace(e.getStackTrace());
                    e = de;

                    mSupport.session().stubDispose(stub.id, message);
                }

                throw CoreUtils.remoteException(this, remoteFailureException, e);
            } finally {
                latch.countDown();
                remove(stub, latch);
            }
        }
    }
}
