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

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Manages a map of entries which are initially empty and then filled later. A thread waits
 * for the map to be filled in.
 *
 * @author Brian S O'Neill
 */
final class WaitMap<K, V> {
    private final ConcurrentHashMap<K, V> mMap;
    private final Semaphore mSem;

    private int mExpect;

    WaitMap() {
        mMap = new ConcurrentHashMap<>();
        mSem = new Semaphore(0);
    }

    /**
     * Add an empty entry from one thread only.
     *
     * @return false if the key was already added
     */
    @SuppressWarnings("unchecked")
    boolean add(K key) {
        // Use this as an empty marker because ConcurrentHashMap doesn't allow nulls.
        if (mMap.putIfAbsent(key, (V) this) != null) {
            return false;
        }
        mExpect++;
        return true;
    }

    /**
     * Only the thread that added entries should call await.
     */
    Map<K, V> await() throws InterruptedException {
        mSem.acquire(mExpect);
        return mMap;
    }

    /**
     * Put an entry via any thread.
     */
    void put(K key, V value) {
        if (mMap.put(key, value) == this) {
            mSem.release();
        }
    }

    /**
     * Remove an entry via any thread.
     */
    void remove(K key) {
        if (mMap.remove(key) == this) {
            mSem.release();
        }
    }
}
