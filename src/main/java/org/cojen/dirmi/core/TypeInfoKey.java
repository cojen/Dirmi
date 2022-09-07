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

/**
 * Cache key which combines a type class and a type info object.
 *
 * @author Brian S O'Neill
 * @see SkeletonMaker
 * @see StubMaker
 */
final class TypeInfoKey {
    private final Class<?> mType;
    private final RemoteInfo mInfo;

    TypeInfoKey(Class<?> type, RemoteInfo info) {
        mType = type;
        mInfo = info;
    }

    @Override
    public int hashCode() {
        return mType.hashCode() ^ mInfo.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof TypeInfoKey) {
            var other = (TypeInfoKey) obj;
            return mType == other.mType && mInfo.equals(other.mInfo);
        }
        return false;
    }
}
