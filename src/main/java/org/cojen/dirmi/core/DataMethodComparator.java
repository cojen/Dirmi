/*
 *  Copyright 2025 Cojen.org
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

import java.util.Comparator;

/**
 * Orders RemoteMethods by their return type. Primitive types are first, then non-serialized
 * objects, and then serialized objects. This allows for more efficient wire encoding by
 * clustering similar types togther.
 *
 * @author Brian S. O'Neill
 */
final class DataMethodComparator implements Comparator<RemoteMethod> {
    @Override
    public int compare(RemoteMethod a, RemoteMethod b) {
        int aSize = CoreUtils.primitiveSize(a.returnType());
        int bSize = CoreUtils.primitiveSize(b.returnType());

        if (aSize >= 0) { // a is primitive
            if (bSize < 0) { // b isn't primitive
                return -1;
            }
        } else if (bSize >= 0) { // b is primitive
            return 1;
        }

        if (!a.isSerializedReturnType()) {
            if (b.isSerializedReturnType()) {
                return -1;
            }
        } else if (!b.isSerializedReturnType()) {
            return 1;
        }

        return a.compareTo(b);
    }
}
