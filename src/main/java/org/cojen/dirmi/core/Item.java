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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Base class for objects which are associated with an identifier. They can be stored within a
 * single ItemMap instance.
 *
 * <p>This class is subclassed by Stub and so it must not declare any new public instance
 * methods because they can conflict with user-specified remote methods which have the same
 * signature.
 *
 * @author Brian S O'Neill
 * @see ItemMap
 * @see IdGenerator
 */
public class Item {
    static final VarHandle cIdHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cIdHandle = lookup.findVarHandle(Item.class, "id", long.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    protected long id;

    // ItemMap collision chain.
    Item mNext;

    Item(long id) {
        this.id = id;
        VarHandle.storeStoreFence();
    }
}
