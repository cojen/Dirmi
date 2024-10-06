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

import org.cojen.dirmi.Remote;

/**
 * Base class for remote stubs. It must not declare any new public instance methods because
 * they can conflict with user-specified remote methods which have the same signature.
 *
 * @author Brian S O'Neill
 */
public abstract sealed class Stub extends Item implements Remote permits StubInvoker, StubWrapper {
    protected Stub(long id) {
        super(id);
    }

    abstract StubSupport support();

    abstract StubInvoker invoker();

    // The hashCode implementation should just be the one inherited from Object. Don't rely on
    // the stub id because it can change after a session reconnect.

    @Override
    public final String toString() {
        var b = new StringBuilder();

        String name = getClass().getName();

        int ix = name.lastIndexOf('-');
        if (ix < 0) {
            b.append(name);
        } else {
            // Prune off the generated suffix.
            b.append(name, 0, ix);
        }

        b.append('@').append(Integer.toHexString(System.identityHashCode(this)))
            .append("{id=").append(id);

        support().appendInfo(b);

        return b.append('}').toString();
    }
}
