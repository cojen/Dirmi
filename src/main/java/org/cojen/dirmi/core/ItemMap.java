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

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.cojen.dirmi.NoSuchObjectException;

/**
 * Strongly maps identifiers to Items.
 *
 * @author Brian S O'Neill
 */
class ItemMap<I extends Item> {
    static final int INITIAL_CAPACITY = 8; // must be power of 2

    protected Item[] mItems;
    protected int mSize;

    ItemMap() {
        mItems = new Item[INITIAL_CAPACITY];
    }

    synchronized int size() {
        return mSize;
    }

    synchronized void clear() {
        Arrays.fill(mItems, null);
        mSize = 0;
    }

    /**
     * @return old item
     */
    @SuppressWarnings("unchecked")
    final synchronized I put(I item) {
        Item[] items = mItems;
        int slot = ((int) item.id) & (items.length - 1);

        for (Item it = items[slot], prev = null; it != null; ) {
            if (it == item) {
                return item;
            }
            Item next = it.mNext;
            if (it.id == item.id) {
                if (prev == null) {
                    item.mNext = next;
                } else {
                    prev.mNext = next;
                    item.mNext = items[slot];
                }
                VarHandle.storeStoreFence(); // ensure that item fields are safely visible
                items[slot] = item;
                it.mNext = null;
                return (I) it;
            }
            prev = it;
            it = next;
        }

        doPut(items, item, slot);

        return null;
    }

    /**
     * @return existing item or the given item
     */
    @SuppressWarnings("unchecked")
    final synchronized I putIfAbsent(I item) {
        Item[] items = mItems;
        int slot = ((int) item.id) & (items.length - 1);

        for (Item it = items[slot]; it != null; it = it.mNext) {
            if (it == item) {
                return item;
            }
            if (it.id == item.id) {
                return (I) it;
            }
        }

        doPut(items, item, slot);

        return item;
    }

    // Caller must be synchronized.
    protected final void doPut(Item[] items, I item, int slot) {
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
     * Get an item by its identifier, returning null if not found. This method doesn't perform
     * any synchronization and so it can yield false negatives.
     */
    @SuppressWarnings("unchecked")
    final I tryGet(long id) {
        Item[] items = mItems;
        for (Item it = items[((int) id) & (items.length - 1)]; it != null; it = it.mNext) {
            if (it.id == id) {
                return (I) it;
            }
        }
        return null;
    }

    /**
     * Get an item by its identifier.
     */
    @SuppressWarnings("unchecked")
    final I get(long id) throws NoSuchObjectException {
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

        throw new NoSuchObjectException(id);
    }

    /**
     * Like tryGet but performs a synchronized double check.
     */
    final I getOrNull(long id) {
        I item = tryGet(id);
        if (item == null) {
            synchronized (this) {
                item = tryGet(id);
            }
        }
        return item;
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
    final I remove(I item) {
        return remove(item.id);
    }

    /**
     * Change an item's identity by atomically removing it from the map, changing the id, and
     * then adding the item back into the map.
     */
    final synchronized I changeIdentity(I item, long newId) {
        remove(item);
        Item.cIdHandle.setRelease(item, newId);
        return putIfAbsent(item);
    }

    /**
     * Atomically remove the "from" item from the map and change the "item" id.
     */
    final synchronized I stealIdentity(I item, I from) {
        remove(from);
        return changeIdentity(item, from.id);
    }

    /**
     * Puts an item in the map, unless an existing item exists with the same id. If so, a new
     * id is randomly selected, and then the put is attempted again.
     */
    final void putUnique(I item) {
        while (putIfAbsent(item) != item) {
            Item.cIdHandle.setRelease(item, IdGenerator.randomNonZero());
        }
    }

    /**
     * Moves all items from the given map into this one.
     */
    final synchronized void moveAll(ItemMap<I> from) {
        from.forEachToRemove(item -> {
            this.putIfAbsent(item);
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    final synchronized void forEach(Consumer<I> action) {
        Item[] items = mItems;
        for (int i=0; i<items.length; i++) {
            for (Item it = items[i]; it != null; it = it.mNext) {
                action.accept((I) it);
            }
        }
    }

    /**
     * @param action when it returns true, the item should be removed
     */
    @SuppressWarnings("unchecked")
    final synchronized void forEachToRemove(Predicate<I> action) {
        Item[] items = mItems;
        for (int i=0; i<items.length; i++) {
            for (Item it = items[i], prev = null; it != null; ) {
                Item next = it.mNext;
                if (action.test((I) it)) {
                    if (prev == null) {
                        items[i] = next;
                    } else {
                        prev.mNext = next;
                    }
                    mSize--;
                } else {
                    prev = it;
                }
                it = next;
            }
        }
    }

    private boolean grow() {
        Item[] items = mItems;

        int capacity = items.length << 1;
        if (capacity < 0) {
            return false;
        }

        var newItems = new Item[capacity];

        for (int i=0; i<items.length; i++) {
            for (Item it = items[i]; it != null; ) {
                Item next = it.mNext;
                int slot = ((int) it.id) & (newItems.length - 1);
                it.mNext = newItems[slot];
                newItems[slot] = it;
                it = next;
            }
        }

        mItems = newItems;

        return true;
    }
}
