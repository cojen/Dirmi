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

import org.cojen.dirmi.Remote;

/**
 * Base class for remote stubs. It must not declare any new public instance methods because
 * they can conflict with user-specified remote methods which have the same signature.
 *
 * @author Brian S O'Neill
 */
public class Stub extends Item implements Remote {
    static final VarHandle cSupportHandle, cWriterHandle, cOriginHandle;

    private static final MethodHandle cRootOrigin;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cSupportHandle = lookup.findVarHandle(Stub.class, "support", StubSupport.class);
            cWriterHandle = lookup.findVarHandle(Stub.class, "miw", MethodIdWriter.class);
            cOriginHandle = lookup.findVarHandle(Stub.class, "origin", MethodHandle.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }

        cRootOrigin = MethodHandles.constant(Stub.class, null);
    }

    /**
     * Set the root origin such that isRestorable always returns false. The root must be
     * restored specially.
     */
    static void setRootOrigin(Stub root) {
        cOriginHandle.setRelease(root, cRootOrigin);
    }

    protected StubSupport support;
    protected MethodIdWriter miw;

    // Is set when this stub has become restorable.
    protected MethodHandle origin;

    public Stub(long id, StubSupport support, MethodIdWriter miw) {
        super(id);
        this.support = support;
        this.miw = miw;
        VarHandle.storeStoreFence();
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

    @Override
    public String toString() {
        String name = getClass().getName();

        // Prune off the generated suffix.
        int ix = name.lastIndexOf('-');
        if (ix > 0) {
            name = name.substring(0, ix);
        }

        var b = new StringBuilder();

        b.append(name).append('@').append(Integer.toHexString(System.identityHashCode(this)))
            .append("{id=").append(id);

        support.appendInfo(b);

        return b.append('}').toString();
    }
}
