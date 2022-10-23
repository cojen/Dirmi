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

import java.lang.invoke.VarHandle;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Specialized set for managing canonical objects: sharable objects that are typically
 * immutable. This is a generic version of the String.intern method.
 *
 * <p>Objects that do not customize the hashCode and equals methods don't make sense to be
 * canonicalized because each instance will be considered unique. The object returned from the
 * add method will always be the same as the one passed in.
 *
 * @author Brian S O'Neill
 */
final class CanonicalSet<V> extends ReferenceQueue<Object> {
    private Entry<V>[] mEntries;
    private int mSize;

    @SuppressWarnings({"unchecked"})
    public CanonicalSet() {
        // Initial capacity must be a power of 2.
        mEntries = new Entry[2];
    }

    /**
     * Returns the original value or another equal value.
     */
    @SuppressWarnings({"unchecked"})
    public V add(V value) {
        Object ref = poll();
        if (ref != null) {
            cleanup(ref);
        }

        int hash = value.hashCode();

        var entries = mEntries;
        for (Entry<V> e = entries[hash & (entries.length - 1)]; e != null; e = e.mNext) {
            V v = e.get();
            if (value.equals(v)) {
                return v;
            }
        }

        synchronized (this) {
            entries = mEntries;
            int slot = hash & (entries.length - 1);
            for (Entry<V> e = entries[slot]; e != null; e = e.mNext) {
                V v = e.get();
                if (value.equals(v)) {
                    return v;
                }
            }

            int size = mSize;

            if ((size + (size >> 1)) >= entries.length && entries.length < (1 << 30)) {
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

            var newEntry = new Entry<V>(value, hash, this);
            newEntry.mNext = entries[slot];
            VarHandle.storeStoreFence(); // ensure that entry value is safely visible
            entries[slot] = newEntry;
            mSize++;

            return value;
        }
    }

    /**
     * @param ref not null
     */
    @SuppressWarnings({"unchecked"})
    private synchronized void cleanup(Object ref) {
        var entries = mEntries;
        do {
            var cleared = (Entry<V>) ref;
            int ix = cleared.mHash & (entries.length - 1);
            for (Entry<V> e = entries[ix], prev = null; e != null; e = e.mNext) {
                if (e == cleared) {
                    if (prev == null) {
                        entries[ix] = e.mNext;
                    } else {
                        prev.mNext = e.mNext;
                    }
                    mSize--;
                    break;
                } else {
                    prev = e;
                }
            }
        } while ((ref = poll()) != null);
    }

    private static final class Entry<V> extends WeakReference<V> {
        final int mHash;
        Entry<V> mNext;

        Entry(V value, int hash, CanonicalSet<V> set) {
            super(value, set);
            mHash = hash;
        }
    }
}
