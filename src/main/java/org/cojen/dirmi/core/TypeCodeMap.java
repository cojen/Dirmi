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

import java.lang.ref.WeakReference;

import java.math.BigInteger;
import java.math.BigDecimal;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cojen.dirmi.core.TypeCodes.*;

/**
 * Weakly maps classes to type codes.
 *
 * @author Brian S O'Neill
 */
final class TypeCodeMap {
    static final TypeCodeMap THE;

    static {
        var map = new TypeCodeMap(64);

        map.put(Boolean.class, T_TRUE);
        map.put(Character.class, T_CHAR);
        map.put(Float.class, T_FLOAT);
        map.put(Double.class, T_DOUBLE);
        map.put(Byte.class, T_BYTE);
        map.put(Short.class, T_SHORT);
        map.put(Integer.class, T_INT);
        map.put(Long.class, T_LONG);
        map.put(String.class, T_STRING);
        map.put(boolean[].class, T_BOOLEAN_ARRAY);
        map.put(char[].class, T_CHAR_ARRAY);
        map.put(float[].class, T_FLOAT_ARRAY);
        map.put(double[].class, T_DOUBLE_ARRAY);
        map.put(byte[].class, T_BYTE_ARRAY);
        map.put(short[].class, T_SHORT_ARRAY);
        map.put(int[].class, T_INT_ARRAY);
        map.put(long[].class, T_LONG_ARRAY);
        map.put(Object[].class, T_OBJECT_ARRAY);
        map.put(List.class, T_LIST);
        map.put(Set.class, T_SET);
        map.put(Map.class, T_MAP);
        map.put(BigInteger.class, T_BIG_INTEGER);
        map.put(BigDecimal.class, T_BIG_DECIMAL);
        map.put(Throwable.class, T_THROWABLE);
        map.put(StackTraceElement.class, T_STACK_TRACE);
        map.put(Stub.class, T_REMOTE);
        map.put(Skeleton.class, T_REMOTE_T);

        THE = map;
    }

    static Class<?> typeClass(int typeCode) {
        switch (typeCode) {
        case T_TRUE: case T_FALSE: return Boolean.class;
        case T_CHAR: return Character.class;
        case T_FLOAT: return Float.class;
        case T_DOUBLE: return Double.class;
        case T_BYTE: return Byte.class;
        case T_SHORT: return Short.class;
        case T_INT: return Integer.class;
        case T_LONG: return Long.class;
        case T_STRING: case T_STRING_L: return String.class;
        case T_BOOLEAN_ARRAY: case T_BOOLEAN_ARRAY_L: return boolean[].class;
        case T_CHAR_ARRAY: case T_CHAR_ARRAY_L: return char[].class;
        case T_FLOAT_ARRAY: case T_FLOAT_ARRAY_L: return float[].class;
        case T_DOUBLE_ARRAY: case T_DOUBLE_ARRAY_L: return double[].class;
        case T_BYTE_ARRAY: case T_BYTE_ARRAY_L: return byte[].class;
        case T_SHORT_ARRAY: case T_SHORT_ARRAY_L: return short[].class;
        case T_INT_ARRAY: case T_INT_ARRAY_L: return int[].class;
        case T_LONG_ARRAY: case T_LONG_ARRAY_L: return long[].class;
        case T_OBJECT_ARRAY: case T_OBJECT_ARRAY_L: return Object[].class;
        case T_LIST: case T_LIST_L: return List.class;
        case T_SET: case T_SET_L: return Set.class;
        case T_MAP: case T_MAP_L: return Map.class;
        case T_BIG_INTEGER: case T_BIG_INTEGER_L: return BigInteger.class;
        case T_BIG_DECIMAL: return BigDecimal.class;
        case T_THROWABLE: return Throwable.class;
        case T_STACK_TRACE: return StackTraceElement.class;
        case T_REMOTE: case T_REMOTE_T: return Item.class;
        default: return Object.class;
        }
    }

    /**
     * Returns true if the given type can be used in a method declaration.
     */
    static boolean isDeclarable(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return true;
        }
        int typeCode = THE.find(clazz);
        if (typeCode == 0) {
            return THE.isAssignableTo(clazz);
        }
        return clazz == typeClass(typeCode);
    }

    private Entry[] mEntries;
    private int mSize;

    /**
     * @param capacity must be a power of 2; capacity can increase
     */
    private TypeCodeMap(int capacity) {
        mEntries = new Entry[capacity];
    }

    /**
     * @return the type code or 0 if the given class is unsupported
     */
    int find(Class<?> clazz) {
        var entries = mEntries;
        for (var e = entries[clazz.hashCode() & (entries.length - 1)]; e != null; e = e.mNext) {
            if (clazz == e.get()) {
                return e.mTypeCode;
            }
        }
        return infer(clazz);
    }

    private int infer(Class<?> clazz) {
        int typeCode;

        if (Throwable.class.isAssignableFrom(clazz)) {
            typeCode = T_THROWABLE;
        } else if (Object[].class.isAssignableFrom(clazz)) {
            typeCode = T_OBJECT_ARRAY;
        } else if (List.class.isAssignableFrom(clazz)) {
            typeCode = T_LIST;
        } else if (Set.class.isAssignableFrom(clazz)) {
            typeCode = T_SET;
        } else if (Map.class.isAssignableFrom(clazz)) {
            typeCode = T_MAP;
        } else {
            return 0;
        }

        put(clazz, typeCode);

        return typeCode;
    }

    private synchronized void put(Class<?> clazz, int typeCode) {
        var entries = mEntries;
        int hash = clazz.hashCode();
        int index = hash & (entries.length - 1);

        for (Entry e = entries[index]; e != null; e = e.mNext) {
            if (clazz == e.get()) {
                // Entry already exists.
                return;
            }
        }

        int size = mSize;

        if ((size + (size >> 1)) >= entries.length && entries.length < (1 << 30)) {
            // Rehash.
            var newEntries = new Entry[entries.length << 1];
            size = 0;
            for (int i=0; i<entries.length; i++) {
                for (var existing = entries[i]; existing != null; ) {
                    var e = existing;
                    existing = existing.mNext;
                    if (e.get() != null) {
                        size++;
                        index = e.mHash & (newEntries.length - 1);
                        e.mNext = newEntries[index];
                        newEntries[index] = e;
                    }
                }
            }
            mEntries = entries = newEntries;
            mSize = size;
            index = hash & (entries.length - 1);
        }

        var newEntry = new Entry(clazz, hash, typeCode);
        newEntry.mNext = entries[index];
        entries[index] = newEntry;
        mSize++;
    }

    /**
     * Returns true if the given type can be assigned any of the supported types.
     */
    private boolean isAssignableTo(Class<?> clazz) {
        // Quick case.
        if (clazz == Object.class) {
            return true;
        }

        synchronized (this) {
            var entries = mEntries;
            for (int i=entries.length; --i>=0 ;) {
                for (var e = entries[i]; e != null; e = e.mNext) {
                    Class<?> supported = e.get();
                    if (supported != null && clazz.isAssignableFrom(supported)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static final class Entry extends WeakReference<Class<?>> {
        final int mHash;
        final int mTypeCode;

        Entry mNext;

        Entry(Class<?> key, int hash, int typeCode) {
            super(key);
            mHash = hash;
            mTypeCode = typeCode;
        }
    }
}
