/*
 *  Copyright 2006 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.core;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Concurrent map which tracks integer values for arbitrary keys. Zero values
 * occupy no space in the map.
 *
 * @author Brian S O'Neill
 * @see AtomicInteger
 */
public class AtomicIntegerMap<K> {
    private final ConcurrentMap<K, AtomicInteger> mCounts;

    public AtomicIntegerMap() {
	mCounts = new ConcurrentHashMap<K, AtomicInteger>();
    }

    /**
     * Clears the contents of the map.
     */
    public void clear() {
	mCounts.clear();
    }

    /**
     * Gets the current value.
     *
     * @return the current value
     */
    public int get(K key) {
	AtomicInteger ai = mCounts.get(key);
	return ai == null ? 0 : ai.get();
    }

    /**
     * Atomically sets to the given value.
     *
     * @param newValue the new value
     */
    public void set(K key, int newValue) {
	// Implementation calls compareAndSet to avoid race conditions with tombstone removal.
	while (true) {
	    AtomicInteger ai = mCounts.get(key);
            if (compareAndSet(key, ai == null ? 0 : ai.get(), newValue, ai)) {
                return;
	    }
        }
    }

    /**
     * Atomically sets to the given value and returns the old value.
     *
     * @param newValue the new value
     * @return the previous value
     */
    public int getAndSet(K key, int newValue) {
	while (true) {
	    AtomicInteger ai = mCounts.get(key);
	    int current = ai == null ? 0 : ai.get();
            if (compareAndSet(key, current, newValue, ai)) {
                return current;
	    }
        }
    }

    /**
     * Atomically sets the value to the given updated value if the current
     * value == the expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return true if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
    public boolean compareAndSet(K key, int expect, int update) {
	return compareAndSet(key, expect, update, mCounts.get(key));
    }

    /**
     * Atomically increments by one the current value.
     *
     * @return the previous value
     */
    public int getAndIncrement(K key) {
	return getAndAdd(key, 1);
    }

    /**
     * Atomically decrements by one the current value.
     *
     * @return the previous value
     */
    public int getAndDecrement(K key) {
	return getAndAdd(key, -1);
    }

    /**
     * Atomically adds the given value to the current value.
     *
     * @param delta the value to add
     * @return the previous value
     */
    public int getAndAdd(K key, int delta) {
	while (true) {
	    AtomicInteger ai = mCounts.get(key);
            int current = ai == null ? 0 : ai.get();
            int next = current + delta;
            if (compareAndSet(key, current, next, ai)) {
                return current;
	    }
        }
    }

    /**
     * Atomically increments by one the current value.
     *
     * @return the updated value
     */
    public int incrementAndGet(K key) {
	return addAndGet(key, 1);
    }

    /**
     * Atomically decrements by one the current value.
     *
     * @return the updated value
     */
    public int decrementAndGet(K key) {
	return addAndGet(key, -1);
    }

    /**
     * Atomically adds the given value to the current value.
     *
     * @param delta the value to add
     * @return the updated value
     */
    public int addAndGet(K key, int delta) {
        while (true) {
	    AtomicInteger ai = mCounts.get(key);
            int current = ai == null ? 0 : ai.get();
            int next = current + delta;
            if (compareAndSet(key, current, next, ai)) {
                return next;
	    }
        }
    }

    private boolean compareAndSet(K key, int expect, int update, AtomicInteger ai) {
	if (ai == null) {
	    if (expect == 0) {
		if (update == 0) {
		    return true;
		}
		return mCounts.putIfAbsent(key, new AtomicInteger(update)) == null;
	    }
	} else if (ai.get() != 0 && expect != 0) {
	    if (ai.compareAndSet(expect, update)) {
		if (update == 0) {
		    // Remove tombstone to allow future updates.
		    mCounts.remove(key);
		}
		return true;
	    }
	}
	return false;
    }
}
