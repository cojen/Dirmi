/*
 *  Copyright 2024 Cojen.org
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

/**
 * Base class for remote stubs which delegate to an invoker for supporting AutoDispose. This
 * class must not declare any new public instance methods because they can conflict with
 * user-specified remote methods which have the same signature.
 *
 * @author Brian S. O'Neill
 */
public non-sealed abstract class StubWrapper extends Stub {
    protected final StubInvoker invoker;

    protected StubWrapper(StubInvoker invoker) {
        super(invoker.id);
        this.invoker = invoker;
    }

    @Override
    final StubSupport support() {
        return invoker.support();
    }

    @Override
    final StubInvoker invoker() {
        return invoker;
    }

    /**
     * @see StubInvoker.WithRef
     * @see StubMaker
     */
    public static final class Factory {
        private MethodHandle ctorHandle;

        Factory() {
        }

        void init(MethodHandle ctorHandle) {
            this.ctorHandle = ctorHandle;
        }

        StubWrapper newWrapper(StubInvoker invoker) {
            try {
                return (StubWrapper) ctorHandle.invoke(invoker);
            } catch (NullPointerException e) {
                // Wasn't initialized.
                throw e;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        }
    }
}
