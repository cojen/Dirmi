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

import java.io.InvalidObjectException;
import java.io.IOException;

import java.lang.ref.SoftReference;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.function.Consumer;

import org.cojen.dirmi.Serializer;

import static org.cojen.dirmi.core.TypeCodes.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class TypeCodeMap {
    static final TypeCodeMap STANDARD = new TypeCodeMap();

    private record Key(int typeCode, Map<Object, Serializer> serializers) { }

    private static final SoftCache<Key, TypeCodeMap> cCache = new SoftCache<>();

    /**
     * @param typeCode the first custom type code to assign
     * @param serializers custom serializers; keys are classes or else they are read only
     */
    static TypeCodeMap find(int typeCode, Map<Object, Serializer> serializers) {
        Key key = new Key(typeCode, serializers);

        TypeCodeMap tcm = cCache.get(key);
        if (tcm == null) {
            synchronized (cCache) {
                tcm = cCache.get(key);
                if (tcm == null) {
                    tcm = new TypeCodeMap(typeCode, serializers);
                    cCache.put(key, tcm);
                }
            }
        }

        return tcm;
    }

    private final Custom[] mCustomSerializers;

    private Entry[] mEntries;
    private int mSize;

    /**
     * @param typeCode the first custom type code to assign
     * @param serializers custom serializers; keys are classes or else they are read only
     */
    @SuppressWarnings("unchecked")
    private TypeCodeMap(int typeCode, Map<Object, Serializer> serializers) {
        if (serializers == null || serializers.isEmpty()) {
            mCustomSerializers = STANDARD.mCustomSerializers;
            mEntries = new Entry[8];
        } else {
            mCustomSerializers = new Custom[serializers.size()];
            mEntries = new Entry[CoreUtils.roundUpPower2(serializers.size() + 8)];

            for (Map.Entry<Object, Serializer> e : serializers.entrySet()) {
                Object key = e.getKey();
                Class clazz = key instanceof Class ? (Class) key : Object.class;

                Serializer serializer = e.getValue();
                if (serializer == null) {
                    serializer = NullSerializer.THE;
                }

                Custom custom;
                
                if (typeCode < 256) {
                    custom = new Custom1(clazz, typeCode, serializer);
                } else if (typeCode < 65536) {
                    custom = new Custom2(clazz, typeCode, serializer);
                } else {
                    custom = new Custom4(clazz, typeCode, serializer);
                }

                mCustomSerializers[typeCode - T_FIRST_CUSTOM] = custom;

                if (key instanceof Class) {
                    put(clazz, custom);
                }

                typeCode++;
            }
        }
    }

    /**
     * Constructor for standard entries.
     */
    private TypeCodeMap() {
        mCustomSerializers = new Custom[0];
        mEntries = new Entry[CoreUtils.roundUpPower2(T_FIRST_CUSTOM - 1)];

        put(Void.TYPE.getClass(), T_VOID);
        put(Object.class, T_OBJECT);
        put(Boolean.class, T_TRUE);
        put(Character.class, T_CHAR);
        put(Float.class, T_FLOAT);
        put(Double.class, T_DOUBLE);
        put(Byte.class, T_BYTE);
        put(Short.class, T_SHORT);
        put(Integer.class, T_INT);
        put(Long.class, T_LONG);
        put(String.class, T_STRING);
        put(boolean[].class, T_BOOLEAN_ARRAY);
        put(char[].class, T_CHAR_ARRAY);
        put(float[].class, T_FLOAT_ARRAY);
        put(double[].class, T_DOUBLE_ARRAY);
        put(byte[].class, T_BYTE_ARRAY);
        put(short[].class, T_SHORT_ARRAY);
        put(int[].class, T_INT_ARRAY);
        put(long[].class, T_LONG_ARRAY);
        put(Object[].class, T_OBJECT_ARRAY);
        put(List.class, T_LIST);
        put(Set.class, T_SET);
        put(Map.class, T_MAP);
        put(BigInteger.class, T_BIG_INTEGER);
        put(BigDecimal.class, T_BIG_DECIMAL);
        put(Throwable.class, T_THROWABLE);
        put(StackTraceElement.class, T_STACK_TRACE);
        put(Stub.class, T_REMOTE);
    }

    Class<?> typeClass(int typeCode) {
        switch (typeCode) {
        case T_REMOTE: case T_REMOTE_T: return Object.class;
        case T_VOID: return Void.TYPE;
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
        }

        typeCode -= T_FIRST_CUSTOM;
        if (typeCode >= 0) {
            Custom[] customs = mCustomSerializers;
            if (typeCode < customs.length) {
                return customs[typeCode].mClass;
            }
        }

        return Object.class;
    }

    /**
     * @param typeCode must be a custom type code
     */
    Object readCustom(BufferedPipe pipe, int typeCode) throws IOException {
        try {
            return mCustomSerializers[typeCode - T_FIRST_CUSTOM].read(pipe);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new InvalidObjectException("Unknown type: " + typeCode);
        }
    }

    /**
     * Note: Should only be called when reference mode is off.
     *
     * @param typeCode must be a custom type code
     */
    void skipCustom(BufferedPipe pipe, Consumer<Object> remoteConsumer, int typeCode)
        throws IOException
    {
        try {
            mCustomSerializers[typeCode - T_FIRST_CUSTOM].skip(pipe, remoteConsumer);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new InvalidObjectException("Unknown type: " + typeCode);
        }
    }

    /**
     * @param obj non-null object to write to the pipe
     */
    @SuppressWarnings("unchecked")
    void write(BufferedPipe pipe, Object obj) throws IOException {
        Class<?> clazz = obj.getClass();
        Entry e = tryFind(clazz);
        if (e == null) {
            e = infer(clazz);
        }
        e.write(pipe, obj);
    }

    /**
     * @return the written type code, which is T_OBJECT if unknown
     */
    int writeTypeCode(BufferedPipe pipe, Class<?> clazz) throws IOException {
        Entry e = tryFind(clazz);
        if (e == null) {
            e = tryInfer(clazz);
            if (e == null) {
                pipe.write(T_OBJECT);
                return T_OBJECT;
            }
        }
        return e.writeTypeCode(pipe);
    }

    /**
     * Can be called without explicit synchronization, but entries can appear to go missing.
     * Double check with synchronization.
     */
    private Entry tryFind(Class<?> clazz) {
        var entries = mEntries;
        for (var e = entries[clazz.hashCode() & (entries.length - 1)]; e != null; e = e.mNext) {
            if (clazz == e.clazz()) {
                return e;
            }
        }
        return null;
    }

    private Entry infer(Class<?> clazz) throws IllegalArgumentException {
        Entry e = tryInfer(clazz);
        if (e == null) {
            throw BufferedPipe.unsupported(clazz);
        }
        return e;
    }

    private Entry tryInfer(Class<?> clazz) {
        Entry e;
        // Double check finding it with synchronization.
        synchronized (this) {
            e = tryFind(clazz);
            if (e != null) {
                return e;
            }
        }

        if (this != STANDARD) {
            synchronized (STANDARD) {
                e = STANDARD.tryFind(clazz);
            }
        }

        int typeCode;

        if (e != null) {
            typeCode = e.mTypeCode;
        } else if (Throwable.class.isAssignableFrom(clazz)) {
            typeCode = T_THROWABLE;
        } else if (Object[].class.isAssignableFrom(clazz)) {
            typeCode = T_OBJECT_ARRAY;
        } else if (List.class.isAssignableFrom(clazz)) {
            typeCode = T_LIST;
        } else if (Set.class.isAssignableFrom(clazz)) {
            typeCode = T_SET;
        } else if (Map.class.isAssignableFrom(clazz)) {
            typeCode = T_MAP;
        } else if (Stub.class.isAssignableFrom(clazz)) {
            typeCode = T_REMOTE;
        } else if (CoreUtils.isRemote(clazz)) {
            typeCode = T_REMOTE_T;
        } else {
            return null;
        }

        return put(clazz, typeCode);
    }

    private Entry put(Class<?> clazz, int typeCode) {
        return put(clazz, new Standard(clazz, typeCode));
    }

    private synchronized Entry put(Class<?> clazz, Entry newEntry) {
        var entries = mEntries;
        int hash = clazz.hashCode();
        int index = hash & (entries.length - 1);

        for (Entry e = entries[index]; e != null; e = e.mNext) {
            if (clazz == e.clazz()) {
                // Entry already exists.
                return e;
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
                    Class ec = e.clazz();
                    if (ec != null) {
                        size++;
                        index = ec.hashCode() & (newEntries.length - 1);
                        e.mNext = newEntries[index];
                        newEntries[index] = e;
                    }
                }
            }
            mEntries = entries = newEntries;
            mSize = size;
            index = hash & (entries.length - 1);
        }

        newEntry.mNext = entries[index];
        entries[index] = newEntry;
        mSize++;

        return newEntry;
    }

    private abstract static sealed class Entry {
        final int mTypeCode;

        Entry mNext;

        Entry(int typeCode) {
            mTypeCode = typeCode;
        }

        /**
         * Returns null if GC'd.
         */
        abstract Class clazz();

        /**
         * @param obj non-null object to write to the pipe
         */
        abstract void write(BufferedPipe pipe, Object obj) throws IOException;

        abstract int writeTypeCode(BufferedPipe pipe) throws IOException;
    }

    private static final class Standard extends Entry {
        final SoftReference<Class> mClassRef;

        Standard(Class clazz, int typeCode) {
            super(typeCode);
            mClassRef = new SoftReference<>(clazz);
        }

        @Override
        Class clazz() {
            return mClassRef.get();
        }

        @Override
        void write(BufferedPipe pipe, Object obj) throws IOException {
            switch (mTypeCode) {
            case T_REMOTE: pipe.writeStub((Stub) obj); break;
            case T_REMOTE_T: pipe.writeSkeleton(obj); break;
            case T_VOID: pipe.write(T_VOID); break;
            case T_OBJECT: pipe.writePlainObject(obj); break;
            case T_TRUE: case T_FALSE: pipe.writeBooleanObj((Boolean) obj); break;
            case T_CHAR: pipe.writeCharObj((Character) obj); break;
            case T_FLOAT: pipe.writeFloatObj((Float) obj); break;
            case T_DOUBLE: pipe.writeDoubleObj((Double) obj); break;
            case T_BYTE: pipe.writeByteObj((Byte) obj); break;
            case T_SHORT: pipe.writeShortObj((Short) obj); break;
            case T_INT: pipe.writeIntObj((Integer) obj); break;
            case T_LONG: pipe.writeLongObj((Long) obj); break;
            case T_STRING: case T_STRING_L: pipe.writeString((String) obj); break;
            case T_BOOLEAN_ARRAY: case T_BOOLEAN_ARRAY_L: pipe.writeBooleanA((boolean[]) obj);break;
            case T_CHAR_ARRAY: case T_CHAR_ARRAY_L: pipe.writeCharA((char[]) obj); break;
            case T_FLOAT_ARRAY: case T_FLOAT_ARRAY_L: pipe.writeFloatA((float[]) obj); break;
            case T_DOUBLE_ARRAY: case T_DOUBLE_ARRAY_L: pipe.writeDoubleA((double[]) obj); break;
            case T_BYTE_ARRAY: case T_BYTE_ARRAY_L: pipe.writeByteA((byte[]) obj); break;
            case T_SHORT_ARRAY: case T_SHORT_ARRAY_L: pipe.writeShortA((short[]) obj); break;
            case T_INT_ARRAY: case T_INT_ARRAY_L: pipe.writeIntA((int[]) obj); break;
            case T_LONG_ARRAY: case T_LONG_ARRAY_L: pipe.writeLongA((long[]) obj); break;
            case T_OBJECT_ARRAY: case T_OBJECT_ARRAY_L: pipe.writeObjectA((Object[]) obj); break;
            case T_LIST: case T_LIST_L: pipe.writeList((List) obj); break;
            case T_SET: case T_SET_L: pipe.writeSet((Set) obj); break;
            case T_MAP: case T_MAP_L: pipe.writeMap((Map) obj); break;
            case T_BIG_INTEGER: case T_BIG_INTEGER_L: pipe.writeBigInteger((BigInteger) obj); break;
            case T_BIG_DECIMAL: pipe.writeBigDecimal((BigDecimal) obj); break;
            case T_THROWABLE: pipe.writeThrowable((Throwable) obj); break;
            case T_STACK_TRACE: pipe.writeStackTraceElement((StackTraceElement) obj); break;
            default: throw BufferedPipe.unsupported(obj.getClass());
            }
        }

        @Override
        int writeTypeCode(BufferedPipe pipe) throws IOException {
            pipe.write(mTypeCode);
            return mTypeCode;
        }
    }

    private abstract static non-sealed class Custom extends Entry {
        final Class mClass;
        final Serializer mSerializer;

        Custom(Class clazz, int typeCode, Serializer serializer) {
            super(typeCode);
            mClass = clazz;
            mSerializer = serializer;
        }

        @Override
        Class clazz() {
            return mClass;
        }

        Object read(BufferedPipe pipe) throws IOException {
            int identifier = pipe.reserveReference();
            Object obj = mSerializer.read(pipe);
            pipe.stashReference(identifier, obj);
            return obj;
        }

        void skip(BufferedPipe pipe, Consumer<Object> remoteConsumer) throws IOException {
            mSerializer.skip(pipe, remoteConsumer);
        }

        @Override
        final void write(BufferedPipe pipe, Object obj) throws IOException {
            writeTypeCode(pipe);
            mSerializer.write(pipe, obj);
        }
    }

    /**
     * Writes a one byte header.
     */
    private static final class Custom1 extends Custom {
        Custom1(Class clazz, int typeCode, Serializer serializer) {
            super(clazz, typeCode, serializer);
        }

        @Override
        int writeTypeCode(BufferedPipe pipe) throws IOException {
            int typeCode = mTypeCode;
            pipe.write(typeCode);
            return typeCode;
        }
    }

    /**
     * Writes a three byte header for supporting up to 65536 types.
     */
    private static final class Custom2 extends Custom {
        Custom2(Class clazz, int typeCode, Serializer serializer) {
            super(clazz, typeCode, serializer);
        }

        @Override
        int writeTypeCode(BufferedPipe pipe) throws IOException {
            pipe.write(T_CUSTOM_2);
            int typeCode = mTypeCode;
            pipe.writeShort(typeCode);
            return typeCode;
        }
    }

    /**
     * Writes a five byte header for supporting up to 2^32 types.
     */
    private static final class Custom4 extends Custom {
        Custom4(Class clazz, int typeCode, Serializer serializer) {
            super(clazz, typeCode, serializer);
        }

        @Override
        int writeTypeCode(BufferedPipe pipe) throws IOException {
            pipe.write(T_CUSTOM_4);
            int typeCode = mTypeCode;
            pipe.writeInt(typeCode);
            return typeCode;
        }
    }
}
