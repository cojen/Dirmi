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

import java.util.Arrays;

import org.cojen.dirmi.NoSuchObjectException;

/**
 * Strongly maps identifiers to Items.
 *
 * @author Brian S O'Neill
 */
final class ItemMap<I extends Item> {
    private static final int INITIAL_CAPACITY = 8; // must be power of 2

    private Item[] mItems;
    private int mSize;

    ItemMap() {
        mItems = new Item[INITIAL_CAPACITY];
    }

    synchronized int size() {
        return mSize;
    }

    synchronized void clear() {
        if (mItems.length == INITIAL_CAPACITY) {
            Arrays.fill(mItems, null);
        } else {
            mItems = new Item[INITIAL_CAPACITY];
        }
        mSize = 0;
    }

    synchronized void put(I item) {
        Item[] items = mItems;
        int slot = ((int) item.id) & (items.length - 1);

        for (Item it = items[slot]; it != null; it = it.mNext) {
            if (it == item) {
                return;
            }
        }

        int size = mSize;
        if ((size + (size >> 1)) >= items.length && grow()) {
            items = mItems;
            slot = ((int) item.id) & (items.length - 1);
        }

        item.mNext = items[slot];
        VarHandle.storeStoreFence(); // ensure that item fields are safely visible
        items[slot] = item;

        mSize = size + 1;
    }

    /**
     * Get an item by its identifier.
     */
    @SuppressWarnings("unchecked")
    I get(long id) throws NoSuchObjectException {
        // Quick find without synchronization.
        Item[] items = mItems;
        for (Item it = items[((int) id) & (items.length - 1)]; it != null; it = it.mNext) {
            if (it.id == id) {
                return (I) it;
            }
        }

        synchronized (this) {
            items = mItems;
            for (Item it = items[((int) id) & (items.length - 1)]; it != null; it = it.mNext) {
                if (it.id == id) {
                    return (I) it;
                }
            }
        }

        throw new NoSuchObjectException(String.valueOf(id));
    }

    /**
     * Remove an item from the map by its identifier.
     */
    @SuppressWarnings("unchecked")
    synchronized I remove(long id) {
        Item[] items = mItems;
        int slot = ((int) id) & (items.length - 1);

        for (Item it = items[slot], prev = null; it != null; ) {
            Item next = it.mNext;
            if (it.id == id) {
                if (prev == null) {
                    items[slot] = next;
                } else {
                    prev.mNext = next;
                }
                mSize--;
                it.mNext = null;
                return (I) it;
            }
            prev = it;
            it = next;
        }

        return null;
    }

    /**
     * Remove an item from the map.
     */
    void remove(I item) {
        remove(item.id);
    }

    private boolean grow() {
        Item[] items = mItems;

        int capacity = items.length << 1;
        if (capacity < 0) {
            return false;
        }

        var newItems = new Item[capacity];

        int newMask = capacity - 1;

        for (int i=0; i<items.length; i++) {
            for (Item it = items[i]; it != null; ) {
                Item next = it.mNext;
                int ix = ((int) it.id) & newMask;
                it.mNext = newItems[ix];
                newItems[ix] = it;
                it = next;
            }
        }

        mItems = newItems;

        return true;
    }
}
