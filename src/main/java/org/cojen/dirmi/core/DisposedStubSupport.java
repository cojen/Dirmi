/*
 *  Copyright 2009-2022 Cojen.org
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

import org.cojen.dirmi.DisposedException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class DisposedStubSupport implements StubSupport {
    static final String EXPLICIT_MESSAGE = "Object is disposed";
    static final DisposedStubSupport EXPLICIT = new DisposedStubSupport(EXPLICIT_MESSAGE);

    static DisposedStubSupport newDisconnected(CoreSession<?> session) {
        return newDisconnected(session, null);
    }

    /**
     * To be called by stubs which are lenient restorable.
     *
     * @param session must not be null
     * @param cause must not be null
     */
    static DisposedStubSupport newDisconnected(CoreSession<?> session, Throwable cause) {
        String message = "Object is disposed due to session disconnect";
        return new DisposedStubSupport(session, message, cause);
    }

    private final CoreSession<?> mSession;
    private final String mMessage;
    private final Throwable mCause;

    DisposedStubSupport(String message) {
        this(null, message, null);
    }

    private DisposedStubSupport(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * @param session pass null if object is permanently disposed
     */
    private DisposedStubSupport(CoreSession<?> session, String message, Throwable cause) {
        mSession = session;
        mMessage = message;
        mCause = cause;
    }

    @Override
    public CoreSession<?> session() {
        if (mSession == null) {
            throw new IllegalStateException(mMessage);
        }
        return mSession;
    }

    @Override
    public boolean isLenientRestorable() {
        return mSession != null && mCause != null;
    }

    @Override
    public void appendInfo(StringBuilder b) {
        b.append(", disposed=").append(true);
    }

    @Override
    public <T extends Throwable> Pipe connect(Stub stub, Class<T> remoteFailureException) throws T {
        DisposedException ex;
        if (mCause == null) {
            ex = new DisposedException(mMessage);
        } else {
            String causeMessage = mCause.getMessage();
            if (causeMessage == null || causeMessage.isEmpty()) {
                causeMessage = mCause.toString();
            }
            ex = new DisposedException(mMessage + " (" + causeMessage + ')', mCause);
        }
        throw CoreUtils.remoteException(remoteFailureException, ex);
    }

    @Override
    public <T extends Throwable> Pipe connectUnbatched(Stub stub, Class<T> remoteFailureException)
        throws T
    {
        return connect(stub, remoteFailureException);
    }

    @Override
    public <T extends Throwable> Pipe tryConnect(Stub stub, Class<T> remoteFailureException)
        throws T
    {
        // When null is returned, the caller is expected to then call newDisconnectedStub.
        return mSession == null ? connect(stub, remoteFailureException) : null;
    }

    @Override
    public <T extends Throwable> Pipe tryConnectUnbatched(Stub stub,
                                                          Class<T> remoteFailureException)
        throws T
    {
        return tryConnect(stub, remoteFailureException);
    }

    @Override
    public boolean validate(Stub stub, Pipe pipe) {
        return true;
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
    public Stub newDisconnectedStub(Class<?> type, Throwable cause) {
        return session().newDisconnectedStub(type, cause);
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
    public void dispose(Stub stub) {
        Stub.cSupportHandle.setRelease(stub, this);
    }
}
