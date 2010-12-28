/*
 *  Copyright 2010 Brian S O'Neill
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

package org.cojen.dirmi.util;

import java.util.Collection;
import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 * @deprecated use Cojen {@link org.cojen.util.Cache} interface
 */
@Deprecated
public abstract class Cache<K, V> {
    public static <K, V> Cache<K, V> newSoftValueCache(int capacity) {
        try {
            return wrap(new org.cojen.util.SoftValueCache<K, V>(capacity));
        } catch (NoClassDefFoundError e) {
            // Use older class for compatibility.
            return wrap(new org.cojen.util.SoftValuedHashMap<K, V>(capacity));
        }
    }

    public static <K, V> Cache<K, V> newWeakValueCache(int capacity) {
        try {
            return wrap(new org.cojen.util.WeakValueCache<K, V>(capacity));
        } catch (NoClassDefFoundError e) {
            // Use older class for compatibility.
            return wrap(new org.cojen.util.WeakValuedHashMap<K, V>(capacity));
        }
    }

    public static <K, V> Cache<K, V> newWeakIdentityCache(int capacity) {
        try {
            return wrap(new org.cojen.util.WeakIdentityCache<K, V>(capacity));
        } catch (NoClassDefFoundError e) {
            // Use older class for compatibility.
            return wrap(new org.cojen.util.WeakIdentityMap<K, V>(capacity));
        }
    }

    public abstract int size();

    public abstract boolean isEmpty();

    public abstract V get(K key);

    public abstract V put(K key, V value);

    public abstract V remove(K key);

    public abstract void clear();

    public abstract void copyKeysInto(Collection<? super K> c);

    static <K, V> Cache<K, V> wrap(final org.cojen.util.Cache<K, V> cache) {
        return new Cache<K, V>() {
            public int size() {
                return cache.size();
            }

            public boolean isEmpty() {
                return cache.isEmpty();
            }

            public V get(K key) {
                return cache.get(key);
            }

            public V put(K key, V value) {
                return cache.put(key, value);
            }

            public V remove(K key) {
                return cache.remove(key);
            }

            public void clear() {
                cache.clear();
            }

            public void copyKeysInto(Collection<? super K> c) {
                cache.copyKeysInto(c);
            }

            public String toString() {
                return cache.toString();
            }
        };
    }

    static <K, V> Cache<K, V> wrap(final Map<K, V> map) {
        return new Cache<K, V>() {
            public synchronized int size() {
                return map.size();
            }

            public synchronized boolean isEmpty() {
                return map.isEmpty();
            }

            public synchronized V get(K key) {
                return map.get(key);
            }

            public synchronized V put(K key, V value) {
                return map.put(key, value);
            }

            public synchronized V remove(K key) {
                return map.remove(key);
            }

            public synchronized void clear() {
                map.clear();
            }

            public synchronized void copyKeysInto(Collection<? super K> c) {
                c.addAll(map.keySet());
            }

            public synchronized String toString() {
                return map.toString();
            }
        };
    }
}
