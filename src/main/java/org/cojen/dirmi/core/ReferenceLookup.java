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

import java.util.Arrays;

/**
 * A companion to ReferenceMap which supports finding objects by identifier. Not thread-safe.
 *
 * @author Brian S O'Neill
 */
final class ReferenceLookup {
    private Object[] mReferences;
    private int mSize;

    ReferenceLookup() {
        mReferences = new Object[16];
    }

    /**
     * Returns a new identifier starting from 0.
     */
    int reserve() {
        int identifier = mSize;
        Object[] refs = mReferences;
        if (identifier >= refs.length) {
            mReferences = Arrays.copyOf(refs, refs.length << 1);
        }
        mSize = identifier + 1;
        return identifier;
    }

    /**
     * Stash an object by the identifier reserved earlier.
     */
    void stash(int identifier, Object object) {
        mReferences[identifier] = object;
    }

    /**
     * Stash an object by the next identifier, starting from 0.
     */
    void stash(Object object) {
        int identifier = mSize;
        Object[] refs = mReferences;
        if (identifier >= refs.length) {
            mReferences = refs = Arrays.copyOf(refs, refs.length << 1);
        }
        refs[identifier] = object;
        mSize = identifier + 1;
    }

    /**
     * Returns null or throws IndexOutOfBoundsException if not found.
     */
    Object find(int identifier) {
        return mReferences[identifier];
    }
}
