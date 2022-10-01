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
 * Maps objects to int identifiers. Not thread-safe.
 *
 * @author Brian S O'Neill
 */
final class ReferenceMap {
    private Entry[] mEntries;
    private int mSize;

    private int mEnableCount;

    ReferenceMap() {
        mEntries = new Entry[16]; // must be power of 2
    }

    boolean isDisabled() {
        return mEnableCount == -1;
    }

    void enable() {
        int count = mEnableCount + 1;
        if (count == -1) {
            throw new IllegalStateException("Enable limit exceeded");
        }
        if (count == 0) {
            // Re-enable after having been disabled.
            assert mEntries == null;
            assert mSize == 0;
            mEntries = new Entry[16];
        }
        mEnableCount = count;
    }

    /**
     * @return 0 if fully disabled
     */
    int disable() {
        int count = mEnableCount;
        if (count == -1) {
            throw new IllegalStateException("Not enabled");
        }
        if (count == 0) {
            mEntries = null; // help GC
            mSize = 0;
        }
        mEnableCount = count - 1;
        return count;
    }

    boolean isEmpty() {
        return mSize == 0;
    }

    /**
     * Add an object to the map and return a new or existing identifier. If the identifier is
     * new, the returned value is negative. Flip the bits to obtain the true value.
     */
    int add(Object object) {
        var entries = mEntries;
        int hash = System.identityHashCode(object);
        int slot = hash & (entries.length - 1);

        for (Entry e = entries[slot]; e != null; e = e.mNext) {
            if (object == e.mObject) {
                // Entry already exists.
                return e.mIdentifier;
            }
        }

        int size = mSize;

        if ((size + (size >> 1)) >= entries.length) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Reference limit reached");
            }

            if (size < (1 << 30)) {
                // Rehash.
                var newEntries = new Entry[entries.length << 1];
                for (int i=0; i<entries.length; i++) {
                    for (var e = entries[i]; e != null; ) {
                        Entry next = e.mNext;
                        slot = e.mHash & (newEntries.length - 1);
                        e.mNext = newEntries[slot];
                        newEntries[slot] = e;
                        e = next;
                    }
                }
                mEntries = entries = newEntries;
                slot = hash & (entries.length - 1);
            }
        }

        var newEntry = new Entry(object, hash, size);
        newEntry.mNext = entries[slot];
        entries[slot] = newEntry;
        mSize = size + 1;

        return ~size;
    }

    private static final class Entry {
        final Object mObject;
        final int mHash;
        final int mIdentifier;

        Entry mNext;

        Entry(Object object, int hash, int identifier) {
            mObject = object;
            mHash = hash;
            mIdentifier = identifier;
        }
    }
}
