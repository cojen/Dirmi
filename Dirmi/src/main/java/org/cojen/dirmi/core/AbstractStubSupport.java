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

import java.lang.reflect.Constructor;

import java.io.EOFException;
import java.io.IOException;

import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.Completion;
import org.cojen.dirmi.RemoteTimeoutException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class AbstractStubSupport implements StubSupport {
    protected final VersionedIdentifier mObjId;

    AbstractStubSupport(VersionedIdentifier id) {
        mObjId = id;
    }

    // Used by batched methods that return a remote object.
    AbstractStubSupport() {
        mObjId = VersionedIdentifier.identify(this);
    }

    @Override
    public <V> Completion<V> createCompletion(Object stub) {
        return new RemoteCompletionServer<V>(stub);
    }

    @Override
    public <T extends Throwable> T failedAndCancelTimeout(Class<T> remoteFailureEx,
                                                          InvocationChannel channel,
                                                          Throwable cause,
                                                          long timeout, TimeUnit unit)
    {
        if (!channel.cancelTimeout() && cause instanceof IOException) {
            // Since cancel task ran, assume cause is timeout.
            cause = new RemoteTimeoutException(timeout, unit);
        }
        return failed(remoteFailureEx, channel, cause);
    }

    @Override
    public <T extends Throwable> T failedAndCancelTimeout(Class<T> remoteFailureEx,
                                                          InvocationChannel channel,
                                                          Throwable cause,
                                                          double timeout, TimeUnit unit)
    {
        if (!channel.cancelTimeout() && cause instanceof IOException) {
            // Since cancel task ran, assume cause is timeout.
            cause = new RemoteTimeoutException(timeout, unit);
        }
        return failed(remoteFailureEx, channel, cause);
    }

    @Override
    public int stubHashCode() {
        return mObjId.hashCode();
    }

    @Override
    public boolean stubEquals(StubSupport support) {
        if (this == support) {
            return true;
        }
        if (support instanceof AbstractStubSupport) {
            return mObjId.equals(((AbstractStubSupport) support).mObjId);
        }
        return false;
    }

    @Override
    public String stubToString() {
        return mObjId.toString();
    }

    protected <T extends Throwable> T remoteException(Class<T> remoteFailureEx, Throwable cause) {
        if (possibleCommunicationFailure(cause)) {
            checkCommunication(cause);
        }

        RemoteException ex;
        if (cause == null) {
            ex = new RemoteException();
        } else {
            if (cause instanceof EOFException) {
                // EOF is not as meaningful in this context, so replace it.
                cause = new ClosedException();
            }

            if (remoteFailureEx.isAssignableFrom(cause.getClass())) {
                return (T) cause;
            }

            if (cause instanceof RemoteException) {
                ex = (RemoteException) cause;
            } else {
                String message = cause.getMessage();
                if (message == null || (message = message.trim()).length() == 0) {
                    message = cause.toString();
                }
                if (cause instanceof java.net.ConnectException) {
                    ex = new org.cojen.dirmi.ConnectException(message, (Exception) cause);
                } else if (cause instanceof java.net.UnknownHostException) {
                    ex = new org.cojen.dirmi.UnknownHostException(message, (Exception) cause);
                } else {
                    ex = new RemoteException(message, cause);
                }
            }
        }

        if (!remoteFailureEx.isAssignableFrom(RemoteException.class)) {
            // Find appropriate constructor.
            for (Constructor ctor : remoteFailureEx.getConstructors()) {
                Class[] paramTypes = ctor.getParameterTypes();
                if (paramTypes.length != 1) {
                    continue;
                }
                if (paramTypes[0].isAssignableFrom(RemoteException.class)) {
                    try {
                        return (T) ctor.newInstance(ex);
                    } catch (Exception e) {
                    }
                }
            }
        }

        return (T) ex;
    }


    protected long toNanos(double timeout, TimeUnit unit) {
        double factor;
        switch (unit) {
        case NANOSECONDS:
            factor = 1e0;
            break;
        case MICROSECONDS:
            factor = 1e3;
            break;
        case MILLISECONDS:
            factor = 1e6;
            break;
        case SECONDS:
            factor = 1e9;
            break;
        case MINUTES:
            factor = 1e9 * 60;
            break;
        case HOURS:
            factor = 1e9 * 60 * 60;
            break;
        case DAYS:
            factor = 1e9 * 60 * 60 * 24;
            break;
        default:
            throw new IllegalArgumentException(unit.toString());
        }

        return (long) (timeout * factor);
    }

    protected boolean possibleCommunicationFailure(Throwable cause) {
        if (!(cause instanceof IOException)) {
            return false;
        }

        if (cause.getClass() == IOException.class ||
            cause instanceof EOFException ||
            cause instanceof java.nio.channels.ClosedChannelException ||
            cause instanceof ClosedException ||
            cause.getClass().getName().startsWith("java.net"))
        {
            return true;
        }

        return false;
    }

    /**
     * Asynchronously check if the session is able to communicate to the other
     * endpoint.
     */
    protected abstract void checkCommunication(Throwable cause);
}
