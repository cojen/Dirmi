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
import org.cojen.dirmi.Session;

/**
 * Base class for remote stubs.
 *
 * @author Brian S O'Neill
 */
public class Stub extends Item implements Remote {
    static final VarHandle cSupportHandle, cOriginHandle;

    private static final MethodHandle cRootOrigin;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cSupportHandle = lookup.findVarHandle(Stub.class, "support", StubSupport.class);
            cOriginHandle = lookup.findVarHandle(Stub.class, "origin", MethodHandle.class);
        } catch (Throwable e) {
            throw new Error(e);
        }

        cRootOrigin = MethodHandles.constant(Stub.class, null);
    }

    /**
     * Set the root origin such that isRestorable(root) always returns false. The root must be
     * restored specially.
     */
    static void setRootOrigin(Stub root) {
        cOriginHandle.setRelease(root, cRootOrigin);
    }

    static boolean isRestorable(Stub stub) {
        var origin = (MethodHandle) cOriginHandle.getAcquire(stub);
        return origin != null && origin != cRootOrigin;
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

        if (!(support instanceof DisposedStubSupport)) {
            Session session = support.session();
            b.append(", localAddress=").append(session.localAddress())
                .append(", remoteAddress=").append(session.remoteAddress());
        }

        return b.append('}').toString();
    }
}
