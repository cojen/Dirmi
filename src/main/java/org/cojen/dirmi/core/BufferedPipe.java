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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.reflect.Array;

import java.io.EOFException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.io.UTFDataFormatException;

import java.math.BigInteger;
import java.math.BigDecimal;

import java.net.SocketAddress;

import java.nio.ByteOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.Pipe;

import static org.cojen.dirmi.core.TypeCodes.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class BufferedPipe implements Pipe {
    private static final int MAX_BUFFER_SIZE = 8192; // must be a power of 2

    private static final VarHandle cShortArrayBEHandle;
    private static final VarHandle cIntArrayBEHandle;
    private static final VarHandle cLongArrayBEHandle;

    static {
        try {
            cShortArrayBEHandle = MethodHandles.byteArrayViewVarHandle
                (short[].class, ByteOrder.BIG_ENDIAN);
            cIntArrayBEHandle = MethodHandles.byteArrayViewVarHandle
                (int[].class, ByteOrder.BIG_ENDIAN);
            cLongArrayBEHandle = MethodHandles.byteArrayViewVarHandle
                (long[].class, ByteOrder.BIG_ENDIAN);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final SocketAddress mLocalAddress, mRemoteAddress;

    private final InputStream mSourceIn;
    private final OutputStream mSourceOut;

    private byte[] mInBuffer;
    private int mInPos, mInEnd;

    private ReferenceLookup mInRefLookup;

    private byte[] mOutBuffer;
    private int mOutEnd;

    private ReferenceMap mOutRefMap;

    private In mIn;
    private Out mOut;

    BufferedPipe(InputStream in, OutputStream out) {
        this(null, null, in, out);
    }

    BufferedPipe(SocketAddress localAddr, SocketAddress remoteAttr,
                 InputStream in, OutputStream out)
    {
        mLocalAddress = localAddr;
        mRemoteAddress = remoteAttr;
        Objects.requireNonNull(in);
        Objects.requireNonNull(out);
        mSourceIn = in;
        mSourceOut = out;
        // Initial buffer sizes must be a power of 2.
        mInBuffer = new byte[32];
        mOutBuffer = new byte[32];
    }

    @Override
    public final int read() throws IOException {
        int pos = mInPos;
        int avail = mInEnd - pos;
        byte[] buf = mInBuffer;

        if (avail <= 0) {
            try {
                avail = doRead(buf, 0, buf.length);
            } catch (IOException e) {
                throw inputException(e);
            }
            if (avail <= 0) {
                return -1;
            }
            pos = 0;
            mInEnd = avail;
            buf = mInBuffer;
        }

        int b = buf[pos++] & 0xff;
        mInPos = pos;
        return b;
    }

    @Override
    public final int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public final int read(byte[] b, int off, int len) throws IOException {
        int avail = available();
        byte[] buf = mInBuffer;

        if (avail <= 0) {
            try {
                if (len >= buf.length) {
                    return doRead(b, off, len);
                }
                avail = doRead(buf, 0, buf.length);
            } catch (IOException e) {
                throw inputException(e);
            }
            if (avail <= 0) {
                return -1;
            }
            mInPos = 0;
            mInEnd = avail;
            buf = mInBuffer;
        }

        len = Math.min(avail, len);
        System.arraycopy(buf, mInPos, b, off, len);
        mInPos += len;
        return len;
    }

    @Override
    public final long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        int avail = available();

        if (avail > 0) {
            if (n >= avail) {
                mInPos = 0;
                mInEnd = 0;
                return avail;
            }
            mInPos += (int) n;
            return n;
        }

        try {
            return mSourceIn.skip(n);
        } catch (IOException e) {
            throw inputException(e);
        }
    }

    @Override
    public final int available() {
        return mInEnd - mInPos;
    }

    @Override
    public final void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    @Override
    public final void readFully(byte[] b, int off, int len) throws IOException {
        while (true) {
            int amt = read(b, off, len);
            if (amt <= 0) {
                if (len == 0) {
                    break;
                }
                throw inputException(new EOFException());
            }
            if ((len -= amt) <= 0) {
                break;
            }
            off += amt;
        }
    }

    @Override
    public final int skipBytes(int n) throws IOException {
        return (int) skip(n);
    }

    @Override
    public final boolean readBoolean() throws IOException {
        return readUnsignedByte() != 0;
    }

    @Override
    public final byte readByte() throws IOException {
        return (byte) readUnsignedByte();
    }

    @Override
    public final int readUnsignedByte() throws IOException {
        int b = read();
        if (b < 0) {
            throw inputException(new EOFException());
        }
        return b;
    }

    @Override
    public final short readShort() throws IOException {
        requireInput(2);
        int pos = mInPos;
        short value = (short) cShortArrayBEHandle.get(mInBuffer, pos);
        mInPos = pos + 2;
        return value;
    }

    @Override
    public final int readUnsignedShort() throws IOException {
        return readShort() & 0xffff;
    }

    @Override
    public final char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public final int readInt() throws IOException {
        requireInput(4);
        int pos = mInPos;
        int value = (int) cIntArrayBEHandle.get(mInBuffer, pos);
        mInPos = pos + 4;
        return value;
    }

    @Override
    public final long readLong() throws IOException {
        requireInput(8);
        int pos = mInPos;
        long value = (long) cLongArrayBEHandle.get(mInBuffer, pos);
        mInPos = pos + 8;
        return value;
    }

    @Override
    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public String readLine() throws IOException {
        // This method is unlikely to be called, so don't bother optimizing it.

        var chars = new char[32];
        int cpos = -1;
        int c;

        while ((c = read()) >= 0) {
            if (c == '\n') {
                break;
            } else if (c == '\r') {
                c = read();
                if (c >= 0 && c != '\n') {
                    mInBuffer[--mInPos] = (byte) c; // pushback
                }
                break;
            } else {
                cpos++;
                if (cpos >= chars.length) {
                    chars = Arrays.copyOf(chars, chars.length << 1);
                }
                chars[cpos] = (char) c;
            }
        }

        return cpos < 0 ? null : new String(chars, 0, cpos + 1);
    }

    @Override
    public final String readUTF() throws IOException {
        return readUTF(readUnsignedShort());
    }

    /**
     * @param len number of bytes to read
     */
    private String readUTF(int len) throws IOException {
        if (len <= 0) {
            return "";
        }

        byte[] bytes;
        int bpos;

        if (len <= MAX_BUFFER_SIZE) {
            requireInput(len);
            bytes = mInBuffer;
            bpos = mInPos;
            mInPos = bpos + len;
        } else {
            bytes = new byte[len];
            readFully(bytes);
            bpos = 0;
        }

        int endpos = bpos + len;
        var chars = new char[len];
        int cpos = 0;

        while (bpos < endpos) {
            int c = bytes[bpos] & 0xff;
            if (c > 127) {
                break;
            }
            bpos++;
            chars[cpos++] = (char) c;
        }

        while (bpos < endpos) {
            int c = bytes[bpos] & 0xff;
            switch (c >> 4) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7: {
                bpos++;
                chars[cpos++] = (char) c;
                break;
            }
            case 12: case 13: {
                bpos += 2;
                if (bpos > endpos) {
                    throw inputException(new UTFDataFormatException());
                }
                int c2 = bytes[bpos - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw inputException(new UTFDataFormatException());
                }
                chars[cpos++] = (char) (((c & 0x1f) << 6) | (c2 & 0x3f));
                break;
            }
            case 14: {
                bpos += 3;
                if (bpos > endpos) {
                    throw inputException(new UTFDataFormatException());
                }
                int c2 = bytes[bpos - 2];
                int c3 = bytes[bpos - 1];
                if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                    throw inputException(new UTFDataFormatException());
                }
                chars[cpos++] = (char) (((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f));
                break;
            }
            default:
                throw inputException(new UTFDataFormatException());
            }
        }

        return new String(chars, 0, cpos);
    }

    @Override
    public final Object readObject() throws IOException {
        int typeCode;

        // Simple objects cannot reference other objects read from the pipe.
        Object simple;

        loop: while (true) {
            typeCode = readUnsignedByte();
            switch (typeCode) {
            case T_REF_MODE_ON:     mInRefLookup = new ReferenceLookup(); continue;
            case T_REF_MODE_OFF:    mInRefLookup = null; continue;
            case T_REFERENCE:       return readReference(readUnsignedByte());
            case T_REFERENCE_L:     return readReference(readInt());
            case T_NULL:            return null;
            case T_TRUE:            simple = Boolean.TRUE; break loop;
            case T_FALSE:           simple = Boolean.FALSE; break loop;
            case T_CHAR:            simple = readChar(); break loop;
            case T_FLOAT:           simple = readFloat(); break loop;
            case T_DOUBLE:          simple = readDouble(); break loop;
            case T_BYTE:            simple = readByte(); break loop;
            case T_SHORT:           simple = readShort(); break loop;
            case T_INT:             simple = readInt(); break loop;
            case T_LONG:            simple = readLong(); break loop;
            case T_STRING:          simple = readString(readUnsignedByte()); break loop;
            case T_STRING_L:        simple = readString(readInt()); break loop;
            case T_BOOLEAN_ARRAY:   simple = readBooleanArray(readUnsignedByte()); break loop;
            case T_BOOLEAN_ARRAY_L: simple = readBooleanArray(readInt()); break loop;
            case T_CHAR_ARRAY:      simple = readCharArray(readUnsignedByte()); break loop;
            case T_CHAR_ARRAY_L:    simple = readCharArray(readInt()); break loop;
            case T_FLOAT_ARRAY:     simple = readFloatArray(readUnsignedByte()); break loop;
            case T_FLOAT_ARRAY_L:   simple = readFloatArray(readInt()); break loop;
            case T_DOUBLE_ARRAY:    simple = readDoubleArray(readUnsignedByte()); break loop;
            case T_DOUBLE_ARRAY_L:  simple = readDoubleArray(readInt()); break loop;
            case T_BYTE_ARRAY:      simple = readByteArray(readUnsignedByte()); break loop;
            case T_BYTE_ARRAY_L:    simple = readByteArray(readInt()); break loop;
            case T_SHORT_ARRAY:     simple = readShortArray(readUnsignedByte()); break loop;
            case T_SHORT_ARRAY_L:   simple = readShortArray(readInt()); break loop;
            case T_INT_ARRAY:       simple = readIntArray(readUnsignedByte()); break loop;
            case T_INT_ARRAY_L:     simple = readIntArray(readInt()); break loop;
            case T_LONG_ARRAY:      simple = readLongArray(readUnsignedByte()); break loop;
            case T_LONG_ARRAY_L:    simple = readLongArray(readInt()); break loop;
            case T_OBJECT_ARRAY:    return readObjectArray(readUnsignedByte());
            case T_OBJECT_ARRAY_L:  return readObjectArray(readInt());
            case T_LIST:            return readList(readUnsignedByte());
            case T_LIST_L:          return readList(readInt());
            case T_SET:             return readSet(readUnsignedByte());
            case T_SET_L:           return readSet(readInt());
            case T_MAP:             return readMap(readUnsignedByte());
            case T_MAP_L:           return readMap(readInt());
            case T_BIG_INTEGER:     simple = readBigInteger(readUnsignedByte()); break loop;
            case T_BIG_INTEGER_L:   simple = readBigInteger(readInt()); break loop;
            case T_BIG_DECIMAL:     simple = readBigDecimal(); break loop;
            case T_THROWABLE:       return readThrowable();
            case T_STACK_TRACE:     return readStackTraceElement();

            case T_REMOTE:          simple = objectFor(readLong()); break loop;
            case T_REMOTE_T:        simple = objectFor(readLong(), readLong()); break loop;
            case T_REMOTE_TI: {
                long id = readLong();
                long typeId = readLong();
                RemoteInfo info = RemoteInfo.readFrom(this);
                simple = objectFor(id, typeId, info);
                break loop;
            }

            default: throw inputException(new InvalidObjectException("Unknown type: " + typeCode));
            }
        }

        stashReference(simple);
 
        return simple;
    }

    Object objectFor(long id) throws IOException {
        throw new NoSuchObjectException(id);
    }

    Object objectFor(long id, long typeId) throws IOException {
        throw new NoSuchObjectException(id);
    }

    Object objectFor(long id, long typeId, RemoteInfo info) throws IOException {
        throw new NoSuchObjectException(id);
    }

    private Object readReference(int identifier) throws IOException {
        ReferenceLookup refLookup = mInRefLookup;
        if (refLookup == null) {
            throw inputException(new StreamCorruptedException("References are disabled"));
        }
        try {
            Object obj = refLookup.find(identifier);
            if (obj != null) {
                return obj;
            }
        } catch (IndexOutOfBoundsException e) {
        }
        throw inputException
            (new StreamCorruptedException("Unable to find referenced object: " + identifier));
    }

    /**
     * @return -1 if references aren't enabled
     */
    private int reserveReference() {
        ReferenceLookup refLookup = mInRefLookup;
        return refLookup == null ? - 1: refLookup.reserve();
    }

    /**
     * Does nothing if identifier is -1.
     */
    private void stashReference(int identifier, Object obj) {
        if (identifier != -1) {
            mInRefLookup.stash(identifier, obj);
        }
    }

    /**
     * Does nothing if references aren't enabled.
     */
    private void stashReference(Object obj) {
        ReferenceLookup refLookup = mInRefLookup;
        if (refLookup != null) {
            refLookup.stash(obj);
        }
    }

    /**
     * @param length number of characters to read
     */
    private String readString(int length) throws IOException {
        if (length <= 0) {
            return "";
        }

        final var chars = new char[length];
        int cpos = 0;
        int pos;

        outer: while (true) {
            requireInput(Math.min(length, MAX_BUFFER_SIZE));

            final byte[] buf = mInBuffer;
            pos = mInPos;
            final int end = mInEnd;
            final int cstart = cpos;

            chunk: {
                int c;
                while ((c = buf[pos++] & 0xff) <= 127) {
                    chars[cpos++] = (char) c;
                    if (cpos >= chars.length) {
                        break outer;
                    }
                    if (pos >= end) {
                        break chunk;
                    }
                }

                while (true) {
                    switch (c >> 4) {
                    case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7: {
                        chars[cpos++] = (char) c;
                        break;
                    }
                    case 12: case 13: {
                        if (pos >= end) {
                            // Need two bytes for this character. Backup and read some more.
                            --pos;
                            length++;
                            break chunk;
                        }
                        int c2 = buf[pos++];
                        if ((c2 & 0xc0) != 0x80) {
                            throw inputException(new UTFDataFormatException());
                        }
                        chars[cpos++] = (char) (((c & 0x1f) << 6) | (c2 & 0x3f));
                        break;
                    }
                    case 14: {
                        if (pos + 1 >= end) {
                            // Need three bytes for this character. Backup and read some more.
                            --pos;
                            length += 2;
                            break chunk;
                        }
                        int c2 = buf[pos++];
                        int c3 = buf[pos++];
                        if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                            throw inputException(new UTFDataFormatException());
                        }
                        chars[cpos++] = (char)
                            (((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f));
                        break;
                    }
                    default:
                        throw inputException(new UTFDataFormatException());
                    }

                    if (cpos >= chars.length) {
                        break outer;
                    }

                    if (pos >= end) {
                        break chunk;
                    }

                    c = buf[pos++] & 0xff;
                }
            }

            mInPos = pos;
            length -= cpos - cstart;
        }

        mInPos = pos;

        return new String(chars);
    }

    private boolean[] readBooleanArray(int length) throws IOException {
        var array = new boolean[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readBoolean();
        }
        return array;
    }

    private char[] readCharArray(int length) throws IOException {
        var array = new char[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readChar();
        }
        return array;
    }

    private float[] readFloatArray(int length) throws IOException {
        var array = new float[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readFloat();
        }
        return array;
    }

    private double[] readDoubleArray(int length) throws IOException {
        var array = new double[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readDouble();
        }
        return array;
    }

    private byte[] readByteArray(int length) throws IOException {
        var array = new byte[length];
        readFully(array);
        return array;
    }

    private short[] readShortArray(int length) throws IOException {
        var array = new short[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readShort();
        }
        return array;
    }

    private int[] readIntArray(int length) throws IOException {
        var array = new int[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readInt();
        }
        return array;
    }

    private long[] readLongArray(int length) throws IOException {
        var array = new long[length];
        // TODO: Optimize by reading chunks.
        for (int i=0; i<array.length; i++) {
            array[i] = readLong();
        }
        return array;
    }

    private Object[] readObjectArray(int length) throws IOException {
        Object[] array;

        int componentTypeCode = read();
        switch (componentTypeCode) {
        case T_TRUE: case T_FALSE: array = new Boolean[length]; break;
        case T_CHAR: array = new Character[length]; break;
        case T_FLOAT: array = new Float[length]; break;
        case T_DOUBLE: array = new Double[length]; break;
        case T_BYTE: array = new Byte[length]; break;
        case T_SHORT: array = new Short[length]; break;
        case T_INT: array = new Integer[length]; break;
        case T_LONG: array = new Long[length]; break;
        case T_STRING: case T_STRING_L: array = new String[length]; break;
        case T_BOOLEAN_ARRAY: case T_BOOLEAN_ARRAY_L: array = new boolean[length][]; break;
        case T_CHAR_ARRAY: case T_CHAR_ARRAY_L: array = new char[length][]; break;
        case T_FLOAT_ARRAY: case T_FLOAT_ARRAY_L: array = new float[length][]; break;
        case T_DOUBLE_ARRAY: case T_DOUBLE_ARRAY_L: array = new double[length][]; break;
        case T_BYTE_ARRAY: case T_BYTE_ARRAY_L: array = new byte[length][]; break;
        case T_SHORT_ARRAY: case T_SHORT_ARRAY_L: array = new short[length][]; break;
        case T_INT_ARRAY: case T_INT_ARRAY_L: array = new int[length][]; break;
        case T_LONG_ARRAY: case T_LONG_ARRAY_L: array = new long[length][]; break;

        case T_OBJECT_ARRAY: case T_OBJECT_ARRAY_L: {
            componentTypeCode = read();
            int extraDims = read();
            Class<?> arrayType = TypeCodeMap.typeClass(componentTypeCode);
            while (--extraDims >= 0) {
                arrayType = arrayType.arrayType();
            }
            array = (Object[]) Array.newInstance(arrayType, length);
            break;
        }

        case T_LIST: case T_LIST_L: array = new List[length]; break;
        case T_SET: case T_SET_L: array = new Set[length]; break;
        case T_MAP: case T_MAP_L: array = new Map[length]; break;
        case T_BIG_INTEGER: case T_BIG_INTEGER_L: array = new BigInteger[length]; break;
        case T_BIG_DECIMAL: array = new BigDecimal[length]; break;
        case T_THROWABLE: array = new Throwable[length]; break;
        case T_STACK_TRACE: array = new StackTraceElement[length]; break;
        case T_REMOTE: case T_REMOTE_T: array = new Item[length]; break;

        default: array = new Object[length]; break;
        }

        stashReference(array);

        for (int i=0; i<array.length; i++) {
            try {
                array[i] = readObject();
            } catch (ArrayStoreException e) {
                var ex = new InvalidObjectException("Malformed object array");
                ex.initCause(e);
                throw inputException(ex);
            }
        }

        return array;
    }

    private List<?> readList(int length) throws IOException {
        var list = new ArrayList<Object>(length);
        stashReference(list);
        for (int i=0; i<length; i++) {
            list.add(readObject());
        }
        return list;
    }

    private Set<?> readSet(int length) throws IOException {
        var set = new LinkedHashSet<Object>(hashCapacity(length));
        stashReference(set);
        for (int i=0; i<length; i++) {
            set.add(readObject());
        }
        return set;
    }

    private Map<?,?> readMap(int length) throws IOException {
        var map = new LinkedHashMap<Object, Object>(hashCapacity(length >> 1));
        stashReference(map);
        for (int i=0; i<length; i++) {
            map.put(readObject(), readObject());
        }
        return map;
    }

    private static int hashCapacity(int length) {
        return (int) Math.ceil(length / 0.75f);
    }

    private BigInteger readBigInteger(int length) throws IOException {
        return new BigInteger(readByteArray(length));
    }

    private BigDecimal readBigDecimal() throws IOException {
        int scale = readInt();
        var unscaled = new BigInteger((byte[]) readObject());
        return new BigDecimal(unscaled, scale);
    }

    private Throwable readThrowable() throws IOException {
        int format = readUnsignedByte();
        if (format != 1) {
            throw inputException(new InvalidObjectException("Unknown format: " + format));
        }

        int identifier = reserveReference();

        var className = (String) readObject();
        var message = (String) readObject();
        var trace = (StackTraceElement[]) readObject();
        var cause = (Throwable) readObject();
        var suppressed = (Throwable[]) readObject();

        Throwable t;

        try {
            reconstruct: {
                Class<?> exClass = loadClass(className);
                if (message == null) {
                    try {
                        t = (Throwable) exClass.getConstructor().newInstance();
                        break reconstruct;
                    } catch (Exception e) {
                    }
                }
                t = (Throwable) exClass.getConstructor(String.class).newInstance(message);
            }
        } catch (Exception e) {
            // Construct a generic exception instead.
            if (message == null) {
                message = className;
            } else {
                message = className + ": " + message;
            }
            t = new Exception(message);
        }

        stashReference(identifier, t);

        if (trace == null) {
            trace = new StackTraceElement[0];
        }

        t.setStackTrace(trace);

        if (cause != null) {
            try {
                t.initCause(cause);
            } catch (Exception e) {
            }
        }

        if (suppressed != null) {
            for (Throwable s : suppressed) {
                t.addSuppressed(s);
            }
        }

        return t;
    }

    private StackTraceElement readStackTraceElement() throws IOException {
        int format = readUnsignedByte();
        if (format != 1) {
            throw inputException(new InvalidObjectException("Unknown format: " + format));
        }

        int identifier = reserveReference();

        var classLoaderName = (String) readObject();
        var moduleName = (String) readObject();
        var moduleVersion = (String) readObject();
        var declaringClass = (String) readObject();
        var methodName = (String) readObject();
        var fileName = (String) readObject();
        int lineNumber = readInt();

        var trace = new StackTraceElement(classLoaderName, moduleName, moduleVersion,
                                          declaringClass, methodName, fileName, lineNumber);

        stashReference(identifier, trace);

        return trace;
    }

    Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private void requireInput(int required) throws IOException {
        int avail = available();
        if ((required -= avail) > 0) {
            requireInput(required, avail);
        }
    }

    private void requireInput(int required, int avail) throws IOException {
        byte[] buf = mInBuffer;
        int end = mInEnd;
        int tail = buf.length - end;
        if (tail < required) {
            // Shift buffer contents to make room.
            System.arraycopy(buf, mInPos, buf, 0, avail);
            mInPos = 0;
            mInEnd = end = avail;
            tail = buf.length - end;
        }

        try {
            while (true) {
                avail = doRead(buf, end, tail);
                if (avail <= 0) {
                    throw new EOFException();
                }
                end += avail;
                mInEnd = end;
                required -= avail;
                if (required <= 0) {
                    break;
                }
                buf = mInBuffer;
                tail = buf.length - end;
            }
        } catch (IOException e) {
            throw inputException(e);
        }
    }

    /**
     * Note: mInBuffer instance can be replaced as a side effect.
     */
    private int doRead(byte[] buf, int offset, int length) throws IOException {
        int amt = mSourceIn.read(buf, offset, length);
        if (amt == length && buf.length < MAX_BUFFER_SIZE) {
            // Filled the buffer, so try to expand it for the next time.
            expandInBuffer(buf);
        }
        return amt;
    }

    private void expandInBuffer(byte[] buf) {
        try {
            int newLength = Math.min(buf.length << 1, MAX_BUFFER_SIZE);
            mInBuffer = Arrays.copyOf(buf, newLength);
        } catch (OutOfMemoryError e) {
        }
    }

    @Override
    public final void write(int b) throws IOException {
        requireOutput(1);
        int end = mOutEnd;
        mOutBuffer[end++] = (byte) b;
        mOutEnd = end;
    }

    @Override
    public final void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public final void write(byte[] b, int off, int len) throws IOException {
        byte[] buf = mOutBuffer;
        int end = mOutEnd;

        makeRoom: {
            if (end != 0) {
                // The buffer isn't empty.

                int avail = buf.length - end;

                if (len <= avail) {
                    // The amount to write fits entirely in the available buffer space.
                    break makeRoom;
                }

                if (buf.length < MAX_BUFFER_SIZE) {
                    avail = expand(end, len);
                    buf = mOutBuffer;
                    if (len <= avail) {
                        // The amount to write fits entirely in the available buffer space.
                        break makeRoom;
                    }
                }

                // Copy what fits and flush.
                System.arraycopy(b, off, buf, end, avail);
                off += avail;
                len -= avail;
                mSourceOut.write(buf, 0, buf.length);
                mOutEnd = end = 0;
            }

            // The buffer is empty.

            if (len >= MAX_BUFFER_SIZE) {
                // Bypass the buffer entirely.
                mSourceOut.write(b, off, len);
                return;
            }

            if (len > buf.length) {
                // Expand the buffer.
                mOutBuffer = buf = new byte[roundUpPower2(len)];
            }
        }

        System.arraycopy(b, off, buf, end, len);
        mOutEnd = end + len;
    }

    @Override
    public final void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    @Override
    public final void writeByte(int v) throws IOException {
        write(v);
    }

    @Override
    public final void writeShort(int v) throws IOException {
        requireOutput(2);
        int end = mOutEnd;
        cShortArrayBEHandle.set(mOutBuffer, end, (short) v);
        mOutEnd = end + 2;
    }

    @Override
    public final void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    public final void writeInt(int v) throws IOException {
        requireOutput(4);
        int end = mOutEnd;
        cIntArrayBEHandle.set(mOutBuffer, end, (int) v);
        mOutEnd = end + 4;
    }

    @Override
    public final void writeLong(long v) throws IOException {
        requireOutput(8);
        int end = mOutEnd;
        cLongArrayBEHandle.set(mOutBuffer, end, (long) v);
        mOutEnd = end + 8;
    }

    @Override
    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToRawIntBits(v));
    }

    @Override
    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToRawLongBits(v));
    }

    @Override
    public final void writeBytes(String v) throws IOException {
        // This method is unlikely to be called, so don't bother optimizing it.
        int len = v.length();
        for (int i=0; i<len; i++) {
            write(v.charAt(i));
        }
    }

    @Override
    public final void writeChars(String v) throws IOException {
        // This method is unlikely to be called, so don't bother optimizing it.
        int len = v.length();
        for (int i=0; i<len; i++) {
            writeChar(v.charAt(i));
        }
    }

    @Override
    public final void writeUTF(String v) throws IOException {
        int strLen = v.length();

        if (strLen > 65535) {
            throw new UTFDataFormatException();
        }

        int utfLen = strLen;

        for (int i=0; i<strLen; i++) {
            int c = v.charAt(i);
            if (c >= 0x80 || c == 0) {
                utfLen += (c >= 0x800) ? 2 : 1;
            }
        }

        if (utfLen > 65535) {
            throw new UTFDataFormatException();
        }

        writeShort(utfLen);

        byte[] buf = mOutBuffer;
        int end = mOutEnd;
        int avail = buf.length - end;

        if (utfLen > avail && buf.length < MAX_BUFFER_SIZE) {
            avail = expand(end, utfLen);
            buf = mOutBuffer;
        }

        int from = 0;

        while (utfLen > avail) {
            // Not enough space in the buffer, so write it out in chunks.
            if (avail < 100) {
                // Flush to make room. Do it before no space is available, so as not to make a
                // bunch of tiny calls to the encodeUTF method.
                mSourceOut.write(buf, 0, end);
                mOutEnd = end = 0;
                avail = buf.length;
            } else {
                // Encode using a third of the available space, which is guaranteed to fit.
                encodeUTF(v, from, from += (avail / 3));
                int newEnd = mOutEnd;
                utfLen -= newEnd - end;
                end = newEnd;
                avail = buf.length - end;
            }
        }

        encodeUTF(v, from, strLen);
    }

    /**
     * Note: Caller must ensure that mOutBuffer has enough space.
     */
    private void encodeUTF(String v, int from, int to) {
        byte[] buf = mOutBuffer;
        int end = mOutEnd;

        for (; from < to; from++) {
            int c = v.charAt(from);
            if (c >= 0x80 || c == 0) {
                break;
            }
            buf[end++] = (byte) c;
        }

        for (; from < to; from++) {
            int c = v.charAt(from);
            if (c < 0x80 && c != 0) {
                buf[end++] = (byte) c;
            } else if (c < 0x800) {
                buf[end++] = (byte) (0xc0 | ((c >> 6) & 0x1f));
                buf[end++] = (byte) (0x80 | (c & 0x3f));
            } else {
                buf[end++] = (byte) (0xe0 | ((c >> 12) & 0x0f));
                buf[end++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                buf[end++] = (byte) (0x80 | (c & 0x3f));
            }
        }

        mOutEnd = end;
    }

    @Override
    public final void writeObject(Object v) throws IOException {
        if (v == null) {
            writeNull();
            return;
        }

        switch (TypeCodeMap.THE.find(v.getClass())) {
        case T_TRUE: case T_FALSE: writeObject((Boolean) v); break;
        case T_CHAR: writeObject((Character) v); break;
        case T_FLOAT: writeObject((Float) v); break;
        case T_DOUBLE: writeObject((Double) v); break;
        case T_BYTE: writeObject((Byte) v); break;
        case T_SHORT: writeObject((Short) v); break;
        case T_INT: writeObject((Integer) v); break;
        case T_LONG: writeObject((Long) v); break;
        case T_STRING: case T_STRING_L: writeObject((String) v); break;
        case T_BOOLEAN_ARRAY: case T_BOOLEAN_ARRAY_L: writeObject((boolean[]) v); break;
        case T_CHAR_ARRAY: case T_CHAR_ARRAY_L: writeObject((char[]) v); break;
        case T_FLOAT_ARRAY: case T_FLOAT_ARRAY_L: writeObject((float[]) v); break;
        case T_DOUBLE_ARRAY: case T_DOUBLE_ARRAY_L: writeObject((double[]) v); break;
        case T_BYTE_ARRAY: case T_BYTE_ARRAY_L: writeObject((byte[]) v); break;
        case T_SHORT_ARRAY: case T_SHORT_ARRAY_L: writeObject((short[]) v); break;
        case T_INT_ARRAY: case T_INT_ARRAY_L: writeObject((int[]) v); break;
        case T_LONG_ARRAY: case T_LONG_ARRAY_L: writeObject((long[]) v); break;
        case T_OBJECT_ARRAY: case T_OBJECT_ARRAY_L: writeObject((Object[]) v); break;
        case T_LIST: case T_LIST_L: writeObject((List) v); break;
        case T_SET: case T_SET_L: writeObject((Set) v); break;
        case T_MAP: case T_MAP_L: writeObject((Map) v); break;
        case T_BIG_INTEGER: case T_BIG_INTEGER_L: writeObject((BigInteger) v); break;
        case T_BIG_DECIMAL: writeObject((BigDecimal) v); break;
        case T_THROWABLE: writeObject((Throwable) v); break;
        case T_STACK_TRACE: writeObject((StackTraceElement) v); break;
        case T_REMOTE: writeObject((Stub) v); break;
        case T_REMOTE_T: writeSkeleton(v); break;
        default: throw unsupported(v);
        }
    }

    private static IllegalArgumentException unsupported(Object v) {
        return new IllegalArgumentException("Unsupported object type: " + v.getClass().getName());
    }

    /**
     * @param server non-null server side object
     */
    void writeSkeleton(Object server) throws IOException {
        throw unsupported(server);
    }

    /**
     * @param typeCode T_REMOTE_T or T_REMOTE_TI
     */
    void writeSkeletonHeader(byte typeCode, Skeleton skeleton) throws IOException {
        requireOutput(17);
        int end = mOutEnd;
        byte[] buf = mOutBuffer;
        buf[end++] = typeCode;
        cLongArrayBEHandle.set(buf, end, skeleton.id);
        cLongArrayBEHandle.set(buf, end + 8, skeleton.typeId());
        mOutEnd = end + 16;
    }

    @Override
    public final void writeObject(Boolean v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            write(v ? T_TRUE : T_FALSE);
        }
    }

    @Override
    public final void writeObject(Character v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(3);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_CHAR;
            cShortArrayBEHandle.set(buf, end, (short) v.charValue());
            mOutEnd = end + 2;
        }
    }

    @Override
    public final void writeObject(Float v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(5);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_FLOAT;
            cIntArrayBEHandle.set(buf, end, Float.floatToRawIntBits(v.floatValue()));
            mOutEnd = end + 4;
        }
    }

    @Override
    public final void writeObject(Double v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(9);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_DOUBLE;
            cLongArrayBEHandle.set(buf, end, Double.doubleToRawLongBits(v.doubleValue()));
            mOutEnd = end + 8;
        }
    }

    @Override
    public final void writeObject(Byte v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(2);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_BYTE;
            buf[end++] = v.byteValue();
            mOutEnd = end;
        }
    }

    @Override
    public final void writeObject(Short v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(3);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_SHORT;
            cShortArrayBEHandle.set(buf, end, (short) v.shortValue());
            mOutEnd = end + 2;
        }
    }

    @Override
    public final void writeObject(Integer v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(5);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_INT;
            cIntArrayBEHandle.set(buf, end, v.intValue());
            mOutEnd = end + 4;
        }
    }

    @Override
    public final void writeObject(Long v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(9);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_LONG;
            cLongArrayBEHandle.set(buf, end, v.longValue());
            mOutEnd = end + 8;
        }
    }

    @Override
    public final void writeObject(String v) throws IOException {
        if (tryWriteReferenceOrNull(v)) {
            return;
        }

        int strLen = v.length();

        if (strLen < 256) {
            requireOutput(2 + strLen * 3);
            byte[] buf = mOutBuffer;
            int end = mOutEnd;
            buf[end++] = T_STRING;
            buf[end++] = (byte) strLen;
            mOutEnd = end;
            encodeUTF(v, 0, strLen);
            return;
        }

        byte[] buf = mOutBuffer;        
        int end = mOutEnd;
        long avail = buf.length - end;
        long maxLen = 5L + strLen * 3L;

        if (maxLen > avail && buf.length < MAX_BUFFER_SIZE) {
            expand(end, (int) Math.min(maxLen, MAX_BUFFER_SIZE));
        }

        requireOutput(5);        
        end = mOutEnd;
        buf = mOutBuffer;
        buf[end++] = T_STRING_L;
        cIntArrayBEHandle.set(buf, end, strLen);
        end += 4;
        mOutEnd = end;
        avail = buf.length - end;
        maxLen -= 5; // header is now finished

        int from = 0;

        while (maxLen > avail) {
            // Not enough space in the buffer, so write it out in chunks.
            if (avail < 100) {
                // Flush to make room. Do it before no space is available, so as not to make a
                // bunch of tiny calls to the encodeUTF method.
                mSourceOut.write(buf, 0, end);
                mOutEnd = end = 0;
                avail = buf.length;
            } else {
                // Encode using a third of the available space, which is guaranteed to fit.
                int chunk = (int) (avail / 3);
                encodeUTF(v, from, from += chunk);
                maxLen -= chunk * 3;
                int newEnd = mOutEnd;
                end = newEnd;
                avail = buf.length - end;
            }
        }

        encodeUTF(v, from, strLen);
    }

    @Override
    public final void writeObject(boolean[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_BOOLEAN_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeBoolean(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(char[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_CHAR_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeChar(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(float[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_FLOAT_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeFloat(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(double[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_DOUBLE_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeDouble(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(byte[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_BYTE_ARRAY, v.length);
            write(v);
        }
    }

    @Override
    public final void writeObject(short[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_SHORT_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeShort(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(int[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_INT_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeInt(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(long[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_LONG_ARRAY, v.length);
            // TODO: Optimize by writing chunks.
            for (int i=0; i<v.length; i++) {
                writeLong(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(Object[] v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_OBJECT_ARRAY, v.length);

            Class componentType = v.getClass().getComponentType();
            int componentTypeCode = TypeCodeMap.THE.find(componentType);
            write(componentTypeCode);

            if (componentTypeCode == T_OBJECT_ARRAY) {
                int extraDims = 0;
                while (true) {
                    Class subType = componentType.getComponentType();
                    if (!subType.isArray()) {
                        if (!subType.isPrimitive()) {
                            componentType = subType;
                            extraDims++;
                        }
                        break;
                    }
                    componentType = subType;
                    extraDims++;
                }
                write(TypeCodeMap.THE.find(componentType));
                write(extraDims);
            }

            for (int i=0; i<v.length; i++) {
                writeObject(v[i]);
            }
        }
    }

    @Override
    public final void writeObject(List<?> v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_LIST, v.size());
            for (Object e : v) {
                writeObject(e);
            }
        }
    }

    @Override
    public final void writeObject(Set<?> v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_SET, v.size());
            for (Object e : v) {
                writeObject(e);
            }
        }
    }

    @Override
    public final void writeObject(Map<?,?> v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            writeVarTypeCode(T_MAP, v.size());
            for (Map.Entry<?,?> e : v.entrySet()) {
                writeObject(e.getKey());
                writeObject(e.getValue());
            }
        }
    }

    @Override
    public final void writeObject(BigInteger v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            byte[] bytes = v.toByteArray();
            writeVarTypeCode(T_BIG_INTEGER, bytes.length);
            write(bytes);
        }
    }

    @Override
    public final void writeObject(BigDecimal v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(5);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_BIG_DECIMAL;
            cIntArrayBEHandle.set(buf, end, v.scale());
            mOutEnd = end + 4;
            writeObject(v.unscaledValue().toByteArray());
        }
    }

    @Override
    public final void writeObject(Throwable v) throws IOException {
        // This method is called to write remote method responses, which is usually null. Only
        // call the writeThrowable method when there's an actual throwable to write, because it
        // then enables reference tracking mode, which allocates objects, etc.
        if (v == null) {
            writeNull();
        } else {
            writeThrowable(v);
        }
    }

    private void writeThrowable(Throwable v) throws IOException {
        enableReferences();
        try {
            if (!tryWriteReferenceOrNull(v)) {
                writeShort((T_THROWABLE << 8) | 1); // type code and encoding format
                writeObject(v.getClass().getName());
                writeObject(v.getMessage());
                writeObject(v.getStackTrace());
                writeObject(v.getCause());
                writeObject(v.getSuppressed());
            }
        } finally {
            disableReferences();
        }
    }

    @Override
    public final void writeObject(StackTraceElement v) throws IOException {
        if (v == null) {
            // Don't enable reference tracking mode when given null.
            writeNull();
        } else {
            enableReferences();
            try {
                if (!tryWriteReferenceOrNull(v)) {
                    writeShort((T_STACK_TRACE << 8) | 1); // type code and encoding format
                    writeObject(v.getClassLoaderName());
                    writeObject(v.getModuleName());
                    writeObject(v.getModuleVersion());
                    writeObject(v.getClassName());
                    writeObject(v.getMethodName());
                    writeObject(v.getFileName());
                    writeInt(v.getLineNumber());
                }
            } finally {
                disableReferences();
            }
        }
    }

    void writeObject(Stub v) throws IOException {
        if (!tryWriteReferenceOrNull(v)) {
            requireOutput(9);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = T_REMOTE; // remote id
            cLongArrayBEHandle.set(buf, end, v.id);
            mOutEnd = end + 8;
        }
    }

    /**
     * Writes a small or large type code, depending on the size of the given unsigned value
     * which is written immediately after the type code.
     *
     * @param typeCode base type code; one is added to the type code if the value doesn't fit
     * in one byte
     */
    private void writeVarTypeCode(int typeCode, int value) throws IOException {
        if ((value & 0xffff_ff00) == 0) {
            requireOutput(2);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = (byte) typeCode;
            buf[end++] = (byte) value;
            mOutEnd = end;
        } else {
            requireOutput(5);
            int end = mOutEnd;
            byte[] buf = mOutBuffer;
            buf[end++] = (byte) (typeCode + 1);
            cIntArrayBEHandle.set(buf, end, value);
            mOutEnd = end + 4;
        }
    }

    private void writeNull() throws IOException {
        ReferenceMap refMap = mOutRefMap;
        if (refMap == null || !refMap.isDisabled()) {
            write(T_NULL);
        } else {
            writeShort((T_REF_MODE_OFF << 8) | T_NULL);
            mOutRefMap = null;
        }
    }

    /**
     * @return true if an object reference or null was written
     */
    private boolean tryWriteReferenceOrNull(Object v) throws IOException {
        ReferenceMap refMap = mOutRefMap;
        if (refMap == null) {
            if (v == null) {
                write(T_NULL);
                return true;
            }
            return false;
        } else {
            return tryWriteReferenceOrNull(refMap, v);
        }
    }

    /**
     * @param refMap must not be null
     * @return true if an object reference or null was written
     */
    private boolean tryWriteReferenceOrNull(ReferenceMap refMap, Object v) throws IOException {
        if (refMap.isDisabled()) {
            if (v == null) {
                writeShort((T_REF_MODE_OFF << 8) | T_NULL);
                mOutRefMap = null;
                return true;
            } else {
                write(T_REF_MODE_OFF);
                mOutRefMap = null;
                return false;
            }
        }

        if (v == null) {
            write(T_NULL);
            return true;
        }

        if (refMap.isEmpty()) {
            write(T_REF_MODE_ON);
        }

        int identifier = refMap.add(v);
        if (identifier < 0) {
            return false;
        } else {
            writeVarTypeCode(T_REFERENCE, identifier);
            return true;
        }
    }

    @Override
    public SocketAddress localAddress() {
        return mLocalAddress;
    }

    @Override
    public SocketAddress remoteAddress() {
        return mRemoteAddress;
    }

    @Override
    public void enableReferences() {
        ReferenceMap refMap = mOutRefMap;
        if (refMap == null) {
            mOutRefMap = new ReferenceMap();
        } else {
            refMap.enable();
        }
    }

    @Override
    public boolean disableReferences() {
        ReferenceMap refMap = mOutRefMap;
        if (refMap == null) {
            throw new IllegalStateException("Not enabled");
        } else {
            return refMap.disable() == 0;
        }
    }

    @Override
    public final InputStream getInputStream() {
        In in = mIn;
        if (in == null) {
            mIn = in = new In();
        }
        return in;
    }

    @Override
    public final OutputStream getOutputStream() {
        Out out = mOut;
        if (out == null) {
            mOut = out = new Out();
        }
        return out;
    }

    @Override
    public final void flush() throws IOException {
        if (mOutEnd != 0) {
            mSourceOut.write(mOutBuffer, 0, mOutEnd);
            mOutEnd = 0;
        }
    }

    private void requireOutput(int required) throws IOException {
        int avail = mOutBuffer.length - mOutEnd;
        if (avail < required) {
            expandOrFlush(required);
        }
    }

    private void expandOrFlush(int required) throws IOException {
        byte[] buf = mOutBuffer;
        int end = mOutEnd;
        if ((end + required) <= MAX_BUFFER_SIZE) {
            expand(end, required);
        } else {
            mSourceOut.write(buf, 0, end);
            mOutEnd = 0;
        }
    }

    /**
     * @return available space
     */
    private int expand(int end, int amount) {
        int length = end + amount;
        length = length < 0 ? MAX_BUFFER_SIZE : Math.min(roundUpPower2(length), MAX_BUFFER_SIZE);
        mOutBuffer = Arrays.copyOf(mOutBuffer, length);
        return length - end;
    }

    private static int roundUpPower2(int i) {
        // Hacker's Delight figure 3-3.
        i--;
        i |= i >> 1;
        i |= i >> 2;
        i |= i >> 4;
        i |= i >> 8;
        return (i | (i >> 16)) + 1;
    }

    boolean isEmpty() {
        return available() == 0 && mOutEnd == 0;
    }

    @Override
    public void recycle() throws IOException {
        close();
    }

    @Override
    public final void close() throws IOException {
        close(null);
    }

    void close(IOException ex) throws IOException {
        try {
            mSourceOut.close();
        } catch (IOException e) {
            ex = merge(ex, e);
        }

        try {
            mSourceIn.close();
        } catch (IOException e) {
            ex = merge(ex, e);
        }

        if (ex != null) {
            throw ex;
        }
    }

    @Override
    public String toString() {
        return "Pipe@" + Integer.toHexString(System.identityHashCode(this)) +
            "{localAddress=" + localAddress() + ", remoteAddress=" + remoteAddress() + '}';
    }

    /**
     * Can only be safely called by the reading thread.
     */
    private IOException inputException(IOException ex) throws IOException {
        // Discard the buffer contents to prevent it from being read again.
        mInPos = 0;
        mInEnd = 0;
        close(ex); // always throws the exception
        throw ex;
    }

    /**
     * Can only be safely called by the reading thread.
     */
    private void closeInput() throws IOException {
        mInPos = 0;
        mInEnd = 0;
        close();
    }

    /**
     * Can only be safely called by the writing thread.
     */
    private void closeOutput() throws IOException {
        try {
            flush();
        } catch (IOException e) {
            close(e); // always throws the exception
        }
        close();
    }

    /**
     * @param e1 can be null
     * @param e2 cannot be null
     * @return e1 with e2 suppressed or else e2 when e1 is null
     */
    static <E extends Throwable> E merge(E e1, E e2) {
        if (e1 == null) {
            return e2;
        } else {
            e1.addSuppressed(e2);
        }
        return e1;
    }

    private final class In extends InputStream implements ObjectInput {
        @Override
        public int read() throws IOException {
            return BufferedPipe.this.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return BufferedPipe.this.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return BufferedPipe.this.skip(n);
        }

        @Override
        public int available() {
            return BufferedPipe.this.available();
        }

        @Override
        public void readFully(byte[] b) throws IOException {
            BufferedPipe.this.readFully(b);
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            BufferedPipe.this.readFully(b, off, len);
        }

        @Override
        public int skipBytes(int n) throws IOException {
            return BufferedPipe.this.skipBytes(n);
        }

        @Override
        public boolean readBoolean() throws IOException {
            return BufferedPipe.this.readBoolean();
        }

        @Override
        public byte readByte() throws IOException {
            return BufferedPipe.this.readByte();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return BufferedPipe.this.readUnsignedByte();
        }

        @Override
        public short readShort() throws IOException {
            return BufferedPipe.this.readShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            return BufferedPipe.this.readUnsignedShort();
        }

        @Override
        public char readChar() throws IOException {
            return BufferedPipe.this.readChar();
        }

        @Override
        public int readInt() throws IOException {
            return BufferedPipe.this.readInt();
        }

        @Override
        public long readLong() throws IOException {
            return BufferedPipe.this.readLong();
        }

        @Override
        public float readFloat() throws IOException {
            return BufferedPipe.this.readFloat();
        }

        @Override
        public double readDouble() throws IOException {
            return BufferedPipe.this.readDouble();
        }

        @Override
        public String readLine() throws IOException {
            return BufferedPipe.this.readLine();
        }

        @Override
        public String readUTF() throws IOException {
            return BufferedPipe.this.readUTF();
        }

        @Override
        public Object readObject() throws IOException {
            return BufferedPipe.this.readObject();
        }

        @Override
        public void close() throws IOException {
            BufferedPipe.this.closeInput();
        }
    }

    private final class Out extends OutputStream implements ObjectOutput {
        @Override
        public void write(int b) throws IOException {
            BufferedPipe.this.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            BufferedPipe.this.write(b, off, len);
        }

        @Override
        public void writeBoolean(boolean v) throws IOException {
            BufferedPipe.this.writeBoolean(v);
        }

        @Override
        public void writeByte(int v) throws IOException {
            BufferedPipe.this.writeByte(v);
        }

        @Override
        public void writeShort(int v) throws IOException {
            BufferedPipe.this.writeShort(v);
        }

        @Override
        public void writeChar(int v) throws IOException {
            BufferedPipe.this.writeChar(v);
        }

        @Override
        public void writeInt(int v) throws IOException {
            BufferedPipe.this.writeInt(v);
        }

        @Override
        public void writeLong(long v) throws IOException {
            BufferedPipe.this.writeLong(v);
        }

        @Override
        public void writeFloat(float v) throws IOException {
            BufferedPipe.this.writeFloat(v);
        }

        @Override
        public void writeDouble(double v) throws IOException {
            BufferedPipe.this.writeDouble(v);
        }

        @Override
        public void writeBytes(String s) throws IOException {
            BufferedPipe.this.writeBytes(s);
        }

        @Override
        public void writeChars(String s) throws IOException {
            BufferedPipe.this.writeChars(s);
        }

        @Override
        public void writeUTF(String s) throws IOException {
            BufferedPipe.this.writeUTF(s);
        }

        @Override
        public void writeObject(Object obj) throws IOException {
            BufferedPipe.this.writeObject(obj);
        }

        @Override
        public void flush() throws IOException {
            BufferedPipe.this.flush();
        }

        @Override
        public void close() throws IOException {
            BufferedPipe.this.closeOutput();
        }
    }
}
