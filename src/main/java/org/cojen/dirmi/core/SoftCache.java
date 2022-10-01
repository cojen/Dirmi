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
import java.lang.ref.SoftReference;

/**
 * Simple cache of softly referenced values.
 *
 * @author Brian S O'Neill
 */
final class SoftCache<K, V> extends ReferenceQueue<Object> {
    private Entry<K, V>[] mEntries;
    private int mSize;

    @SuppressWarnings({"unchecked"})
    public SoftCache() {
        // Initial capacity must be a power of 2.
        mEntries = new Entry[2];
    }

    /**
     * Can be called without explicit synchronization, but entries can appear to go missing.
     * Double check with synchronization.
     */
    public V get(K key) {
        Object ref = poll();
        if (ref != null) {
            synchronized (this) {
                cleanup(ref);
            }
        }

        var entries = mEntries;
        for (var e = entries[key.hashCode() & (entries.length - 1)]; e != null; e = e.mNext) {
            if (e.mKey.equals(key)) {
                return e.get();
            }
        }

        return null;
    }

    /**
     * @return replaced value, or null if none
     */
    @SuppressWarnings({"unchecked"})
    public synchronized V put(K key, V value) {
        Object ref = poll();
        if (ref != null) {
            cleanup(ref);
        }

        var entries = mEntries;
        int hash = key.hashCode();
        int slot = hash & (entries.length - 1);

        for (Entry<K, V> e = entries[slot], prev = null; e != null; e = e.mNext) {
            if (e.mKey.equals(key)) {
                V replaced = e.get();
                if (replaced != null) {
                    e.clear();
                }
                var newEntry = new Entry<K, V>(key, value, hash, this);
                if (prev == null) {
                    newEntry.mNext = e.mNext;
                } else {
                    prev.mNext = e.mNext;
                    newEntry.mNext = entries[slot];
                }
                VarHandle.storeStoreFence(); // ensure that entry value is safely visible
                entries[slot] = newEntry;
                return replaced;
            } else {
                prev = e;
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

        var newEntry = new Entry<K, V>(key, value, hash, this);
        newEntry.mNext = entries[slot];
        VarHandle.storeStoreFence(); // ensure that entry value is safely visible
        entries[slot] = newEntry;
        mSize++;

        return null;
    }

    /**
     * Caller must be synchronized.
     *
     * @param ref not null
     */
    @SuppressWarnings({"unchecked"})
    private void cleanup(Object ref) {
        var entries = mEntries;
        do {
            var cleared = (Entry<K, V>) ref;
            int ix = cleared.mHash & (entries.length - 1);
            for (Entry<K, V> e = entries[ix], prev = null; e != null; e = e.mNext) {
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

    private static final class Entry<K, V> extends SoftReference<V> {
        final K mKey;
        final int mHash;

        Entry<K, V> mNext;

        Entry(K key, V value, int hash, SoftCache<K, V> cache) {
            super(value, cache);
            mKey = key;
            mHash = hash;
        }
    }
}
