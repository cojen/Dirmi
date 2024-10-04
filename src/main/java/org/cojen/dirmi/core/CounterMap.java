/*
 *  Copyright 2024 Cojen.org
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
 * Thread-safe map of long identifiers to long counters.
 *
 * @author Brian S. O'Neill
 */
final class CounterMap {
    private Entry[] mEntries;
    private int mSize;

    CounterMap() {
        mEntries = new Entry[16]; // must be power of 2
    }

    /**
     * Remove all the entries into a linked list, or else return null if the map is empty.
     */
    synchronized Entry drain() {
        if (mSize == 0) {
            return null;
        }

        Entry[] entries = mEntries;
        Entry head = null, tail = null;

        for (int i=0; i<entries.length; i++) {
            Entry e = entries[i];

            if (e == null) {
                continue;
            }

            if (tail == null) {
                head = e;
            } else {
                tail.next = e;
            }

            while (true) {
                tail = e.next;
                if (tail == null) {
                    tail = e;
                    break;
                }
                e = tail;
            }

            entries[i] = null;
        }

        mSize = 0;

        return head;
    }

    /**
     * Increment a counter value for the given identifier.
     */
    synchronized void increment(long id) {
        Entry[] entries = mEntries;
        int hash = Long.hashCode(id);
        int slot = hash & (entries.length - 1);

        for (Entry e = entries[slot]; e != null; e = e.next) {
            if (id == e.id) {
                e.counter++;
                return;
            }
        }

        int size = mSize;

        if ((size + (size >> 1)) >= entries.length && size < (1 << 30)) {
            // Rehash.
            var newEntries = new Entry[entries.length << 1];
            for (int i=0; i<entries.length; i++) {
                for (var e = entries[i]; e != null; ) {
                    Entry next = e.next;
                    slot = Long.hashCode(e.id) & (newEntries.length - 1);
                    e.next = newEntries[slot];
                    newEntries[slot] = e;
                    e = next;
                }
            }
            mEntries = entries = newEntries;
            slot = hash & (entries.length - 1);
        }

        var newEntry = new Entry(id);
        newEntry.next = entries[slot];
        entries[slot] = newEntry;
        mSize = size + 1;
    }

    static final class Entry {
        final long id;
        long counter;
        Entry next;

        Entry(long id) {
            this.id = id;
            counter = 1;
        }
    }
}
