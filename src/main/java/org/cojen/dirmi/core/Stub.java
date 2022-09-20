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
    static final VarHandle SUPPORT_HANDLE;

    static {
        try {
            var lookup = MethodHandles.lookup();
            SUPPORT_HANDLE = lookup.findVarHandle(Stub.class, "support", StubSupport.class);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    protected StubSupport support;

    public Stub(long id, StubSupport support) {
        super(id);
        this.support = support;
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

        Session session = support.session();

        return name + '@' +
            Integer.toHexString(System.identityHashCode(this)) + "{id=" + id +
            ", localAddress=" + session.localAddress() +
            ", remoteAddress=" + session.remoteAddress() + '}';
    }
}
