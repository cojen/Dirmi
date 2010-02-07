/*
 *  Copyright 2006-2010 Brian S O'Neill
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

package org.cojen.dirmi.info;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.Batched;
import org.cojen.dirmi.CallMode;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Ordered;

/**
 * Describes a remote method, as provided by {@link RemoteInfo}.
 *
 * @author Brian S O'Neill
 */
public interface RemoteMethod extends Serializable {
    /**
     * Returns the name of this method.
     */
    String getName();

    /**
     * Returns a unique identifier for this method, within the scope of its
     * enclosing type. The LSB of the identifier's data is 0 if method is
     * synchronous, 1 if asynchronous.
     */
    int getMethodId();

    /**
     * Returns the return type of this method, which is null if void.
     */
    RemoteParameter<?> getReturnType();

    /**
     * Returns the method parameters in an unmodifiable list.
     */
    List<? extends RemoteParameter<?>> getParameterTypes();

    /**
     * Returns the method exception types in an unmodifiable set. The set
     * elements are guaranteed to have a consistent ordering.
     */
    Set<? extends RemoteParameter<? extends Throwable>> getExceptionTypes();

    /**
     * Returns a Java-syntax signature for method.
     */
    String getSignature();

    /**
     * Returns true if this method is asynchronous.
     *
     * @see Asynchronous
     */
    boolean isAsynchronous();

    /**
     * Returns the asynchronous call mode, or null if not asynchronous.
     *
     * @see Asynchronous
     */
    CallMode getAsynchronousCallMode();

    /**
     * Returns true if this method is batched, which implies that it is
     * asynchronous.
     *
     * @see Batched
     */
    boolean isBatched();

    /**
     * @see Ordered
     */
    boolean isOrdered();

    /**
     * @see Disposer
     */
    boolean isDisposer();

    RemoteParameter<? extends Throwable> getRemoteFailureException();

    boolean isRemoteFailureExceptionDeclared();

    /**
     * Returns the method timeout, which was either explicitly defined or
     * inherited from its enclosing interface. The timeout value is negative to
     * represent infinity.
     */
    long getTimeout();

    /**
     * Returns the method timeout unit, which was either explicitly defined or
     * inherited from its enclosing interface. The unit is never null.
     */
    TimeUnit getTimeoutUnit();
}
