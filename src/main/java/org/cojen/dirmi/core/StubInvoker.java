/*
 *  Copyright 2011-2022 Cojen.org
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Base class for remote stubs which actually invoke the remote methods over the pipe. This
 * class must not declare any new public or protected instance methods because they can
 * conflict with user-specified remote methods which have the same signature.
 *
 * @author Brian S. O'Neill
 */
public non-sealed class StubInvoker extends Stub {
    static final VarHandle cSupportHandle, cWriterHandle, cOriginHandle;

    private static final MethodHandle cRootOrigin;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cSupportHandle = lookup.findVarHandle(StubInvoker.class, "support", StubSupport.class);
            cWriterHandle = lookup.findVarHandle(StubInvoker.class, "miw", MethodIdWriter.class);
            cOriginHandle = lookup.findVarHandle(StubInvoker.class, "origin", MethodHandle.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }

        cRootOrigin = MethodHandles.constant(StubInvoker.class, null);
    }

    /**
     * Set the root origin such that isRestorable always returns false. The root must be
     * restored specially.
     */
    static void setRootOrigin(StubInvoker root) {
        cOriginHandle.setRelease(root, cRootOrigin);
    }

    protected StubSupport support;
    protected MethodIdWriter miw;

    // Is set when this stub has become restorable.
    protected MethodHandle origin;

    public StubInvoker(long id, StubSupport support, MethodIdWriter miw) {
        super(id);
        this.support = support;
        this.miw = miw;
        VarHandle.storeStoreFence();
    }

    /**
     * Is called by StubMap.putAndSelectStub to finish initialization of this stub instance and
     * return a selected stub.
     *
     * Note: This method must not be public or else it can conflict with a user-specified
     * remote method which has the same signature.
     *
     * @return this or a StubWrapper
     */
    Stub init() {
        return this;
    }

    /**
     * Is called by StubMap.putAndSelectStub to select this stub instance or wrapper. If null
     * is returned, then the wrapper was reclaimed and so this stub instance should be rejected
     * entirely.
     *
     * Note: This method must not be public or else it can conflict with a user-specified
     * remote method which has the same signature.
     *
     * @return this, or a StubWrapper, or null
     */
    Stub select() {
        return this;
    }

    /**
     * Note: This method must not be public or else it can conflict with a user-specified
     * remote method which has the same signature.
     */
    @Override
    final StubSupport support() {
        return (StubSupport) cSupportHandle.getAcquire(this);
    }

    @Override
    final StubInvoker invoker() {
        return this;
    }

    /**
     * Returns true if this stub is restorable following a disconnect.
     *
     * Note: This method must not be public or else it can conflict with a user-specified
     * remote method which has the same signature.
     *
     * @see #setRootOrigin
     */
    final boolean isRestorable() {
        var origin = (MethodHandle) cOriginHandle.getAcquire(this);
        if (origin == null) {
            return ((StubSupport) cSupportHandle.getAcquire(this)).isLenientRestorable();
        } else {
            return origin != cRootOrigin;
        }
    }

    /**
     * Called to track the number of times the remote side has referenced this stub invoker
     * instance. It only needs to do anything for invokers which support automatic disposal.
     */
    void incTransportCount() {
    }

    /**
     * Clears the transport count value, and returns the old value. This operation is only
     * valid for invokers which support automatic disposal. If zero is returned (although not
     * expected), the remote skeleton should be forcibly disposed.
     */
    long resetTransportCount() {
        return 0;
    }

    /**
     * Base class for invokers which support automatic disposal. This class must not declare
     * any new public or protected instance methods because they can conflict with
     * user-specified remote methods which have the same signature.
     */
    private static abstract class WithRef extends StubInvoker {
        private StubWrapper.Factory wrapperFactory;

        private volatile long transportCount = 1L;

        private static final VarHandle cTransportCountHandle;

        static {
            try {
                var lookup = MethodHandles.lookup();
                cTransportCountHandle = lookup.findVarHandle
                    (WithRef.class, "transportCount", long.class);
            } catch (Throwable e) {
                throw CoreUtils.rethrow(e);
            }
        }

        public WithRef(long id, StubSupport support, MethodIdWriter miw,
                       StubWrapper.Factory wrapperFactory)
        {
            super(id, support, miw);
            this.wrapperFactory = wrapperFactory;
        }

        final StubWrapper initWrapper() {
            StubWrapper wrapper = wrapperFactory.newWrapper(this);
            wrapperFactory = null; // not needed anymore
            return wrapper;
        }

        @Override
        void incTransportCount() {
            cTransportCountHandle.getAndAdd(this, 1L);
        }

        @Override
        long resetTransportCount() {
            return (long) cTransportCountHandle.getAndSet(this, 0L);
        }
    }

    /**
     * Base class for invokers which support automatic disposal and don't have any methods for
     * which "isUnacknowledged" is true. This class must not declare any new public or
     * protected instance methods because they can conflict with user-specified remote methods
     * which have the same signature.
     */
    public static abstract class WithBasicRef extends WithRef {
        private AutoDisposer.BasicRef ref;

        public WithBasicRef(long id, StubSupport support, MethodIdWriter miw,
                            StubWrapper.Factory wrapperFactory)
        {
            super(id, support, miw, wrapperFactory);
        }

        @Override
        final Stub init() {
            // Only bother creating the ref object until it's determined that this object is
            // needed. See StubMap.putAndSelectStub.
            var wrapper = initWrapper();
            ref = new AutoDisposer.BasicRef(wrapper, this);
            return wrapper;
        }

        @Override
        final Stub select() {
            return ref.get();
        }
    }

    /**
     * Base class for invokers which support automatic disposal and have at least one method
     * for which "isUnacknowledged" is true. This class must not declare any new public or
     * protected instance methods because they can conflict with user-specified remote methods
     * which have the same signature.
     */
    public static abstract class WithCountedRef extends WithRef {
        protected AutoDisposer.CountedRef ref;

        public WithCountedRef(long id, StubSupport support, MethodIdWriter miw,
                              StubWrapper.Factory wrapperFactory)
        {
            super(id, support, miw, wrapperFactory);
        }

        @Override
        final Stub init() {
            // Only bother creating the ref object until it's determined that this object is
            // needed. See StubMap.putAndSelectStub.
            var wrapper = initWrapper();
            ref = new AutoDisposer.CountedRef(wrapper, this);
            return wrapper;
        }

        @Override
        final Stub select() {
            return ref.get();
        }

        /**
         * Note: Calling this method might block if a notification needs to be written.
         */
        final void decRefCount(long amount) {
            ref.decRefCount(amount);
        }
    }
}
