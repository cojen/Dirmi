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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.io.UTFDataFormatException;

import java.lang.reflect.Array;

import java.math.BigInteger;
import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.Set;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.ClosedException;

import org.cojen.dirmi.io.CaptureOutputStream;

import static org.cojen.dirmi.core.TypeCodes.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PipeTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(PipeTest.class.getName());
    }

    @Test
    public void emptyUTF() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        pipe.writeUTF("");
        pipe.flush();

        byte[] bytes = capture.getBytes();
        assertEquals(2, bytes.length);
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[1]);

        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());
        assertEquals("", pipe.readUTF());
    }

    @Test
    public void longUTF() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        try {
            pipe.writeUTF(new String(new char[65536]));
            fail();
        } catch (UTFDataFormatException e) {
        }

        try {
            var chars = new char[65535];
            chars[123] = '\u0800';
            pipe.writeUTF(new String(chars));
            fail();
        } catch (UTFDataFormatException e) {
        }

        // Can still write to the pipe.
        OutputStream out = pipe.outputStream();
        out.write(10);
        out.flush();
        pipe.outputStream().close();

        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        byte[] b = new byte[100];
        assertEquals(0, pipe.inputStream().read(b, 0, 0));
        assertEquals(0, b[0]);
        assertEquals(1, pipe.inputStream().read(b, 0, 20));
        assertEquals(10, b[0]);

        pipe.inputStream().close();
    }

    @Test
    public void readLine() throws Exception {
        String[] strs = {
            "hello", "hello\n", "hello\r", "hello\r\n", "hello\rworld",
            "helloworldhelloworldhelloworldhelloworld"
        };

        for (String str : strs) {
            var bin = new ByteArrayInputStream(str.getBytes("UTF-8"));
            var pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());
            var in = (ObjectInput) pipe.inputStream();
            String line = in.readLine();
            if (line.equals("hello")) {
                line = in.readLine();
                assertTrue(line == null || line.equals("world"));
            } else {
                assertEquals("helloworldhelloworldhelloworldhelloworld", line);
                assertNull(in.readLine());
            }
        }
    }

    @Test
    public void skip() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        for (int i=1; i<=2500; i++) {
            pipe.writeInt(i);
        }

        pipe.outputStream().close();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var in = (ObjectInput) pipe.inputStream();

        assertEquals(0, in.available());
        assertEquals(4, in.skipBytes(4));
        assertEquals(0, in.available());

        int avail = 32; // default initial buffer size

        assertEquals(2, in.readInt());
        assertEquals(avail -= 4, in.available());

        assertEquals(8, in.skipBytes(8));
        assertEquals(avail -= 8, in.available());
        assertEquals(5, in.readInt());
        assertEquals(avail -= 4, in.available());

        assertEquals(0, in.skipBytes(0));
        assertEquals(0, in.skipBytes(-10));
        assertEquals(0, pipe.skip(Long.MIN_VALUE));

        assertEquals(avail, in.skipBytes(100));
        assertEquals(0, in.available());
        assertEquals((2500 * 4 - (4 + 4 + 8 + 4 + avail)), in.skipBytes(Integer.MAX_VALUE));

        try {
            in.readUnsignedByte();
            fail();
        } catch (ClosedException e) {
        }

        try {
            in.readUnsignedShort();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void exceptionMerge() {
        var e1 = new Exception();
        var e2 = new Exception();
        assertEquals(e2, BufferedPipe.merge(null, e2));
        assertEquals(e1, BufferedPipe.merge(e1, e2));
        assertEquals(e2, e1.getSuppressed()[0]);
    }

    @Test
    public void boxedPrimitives() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        pipe.writeObject((Boolean) null);
        pipe.writeObject(true);
        pipe.writeObject(false);

        pipe.writeObject((Character) null);
        pipe.writeObject('a');

        pipe.writeObject((Float) null);
        pipe.writeObject(1.23f);

        pipe.writeObject((Double) null);
        pipe.writeObject(2.34d);

        pipe.writeObject((Byte) null);
        pipe.writeObject((byte) 1);

        pipe.writeObject((Short) null);
        pipe.writeObject((short) 2);

        pipe.writeObject((Integer) null);
        pipe.writeObject(3);

        pipe.writeObject((Long) null);
        pipe.writeObject(4L);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        assertEquals(null, pipe.readObject());
        assertEquals(true, pipe.readObject());
        assertEquals(false, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals('a', pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals(1.23f, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals(2.34d, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals((byte) 1, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals((short) 2, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals(3, pipe.readObject());

        assertEquals(null, pipe.readObject());
        assertEquals(4L, pipe.readObject());

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void bigNumbers() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        var rnd = new Random(8675309);
        var number = new byte[1000];
        rnd.nextBytes(number);

        BigInteger[] bigInts = {
            null, BigInteger.valueOf(Long.MAX_VALUE), new BigInteger(number)
        };

        for (BigInteger bi : bigInts) {
            pipe.writeObject((Object) bi);
        }

        BigDecimal[] bigDecs = {
            null, new BigDecimal(bigInts[1], 10), new BigDecimal(bigInts[2], -10)
        };

        for (BigDecimal bd : bigDecs) {
            pipe.writeObject((Object) bd);
        }

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        for (BigInteger bi : bigInts) {
            assertEquals(bi, pipe.readObject());
        }

        for (BigDecimal bd : bigDecs) {
            assertEquals(bd, pipe.readObject());
        }

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void strings() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        var rnd = new Random(8675309);
        var strings = new String[1000];
        for (int i=0; i<strings.length; i++) {
            strings[i] = randomString(rnd, 0, 500);
        }

        for (String s : strings) {
            pipe.writeObject(s);
        }

        pipe.writeObject((String) null);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        for (String s : strings) {
            assertEquals(s, pipe.readObject());
        }

        assertEquals(null, pipe.readObject());

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void bogus() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        try {
            pipe.writeObject(this);
            fail();
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void arrays() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        var rnd = new Random(8675309);

        Object[] arrays = {
            randomBooleanArray(rnd, 10, 200),
            randomBooleanArray(rnd, 300, 1000),
            randomCharArray(rnd, 10, 200),
            randomCharArray(rnd, 300, 1000),
            randomFloatArray(rnd, 10, 200),
            randomFloatArray(rnd, 300, 1000),
            randomDoubleArray(rnd, 10, 200),
            randomDoubleArray(rnd, 300, 1000),
            randomByteArray(rnd, 10, 200),
            randomByteArray(rnd, 300, 1000),
            randomShortArray(rnd, 10, 200),
            randomShortArray(rnd, 300, 1000),
            randomIntArray(rnd, 10, 200),
            randomIntArray(rnd, 300, 1000),
            randomLongArray(rnd, 10, 200),
            randomLongArray(rnd, 300, 1000),
            randomStringArray(rnd, 10, 200),
            randomStringArray(rnd, 300, 1000),

            new Object[] {
                true, 'a', 1.23f, 2.34d, (byte) 3, (short) 4, 5, 6L,
                BigInteger.valueOf(100), new BigDecimal("3.14"),
                new Object[] {new int[] {1, 2, 3}, new String[] {"a", "b", "c"}},
            },

            new Boolean[] {true, false}, new Character[] {'a', 'b'},
            new Float[] {}, new Double[] {1.2}, new Byte[] {(byte) -1}, new Short[] {(short) 300},
            new Integer[] {-1, 0, 1}, new Long[] {Long.MIN_VALUE, Long.MAX_VALUE},

            new boolean[][] {{true, false}, {false, true}}, new char[][] {{'a'}, {'b', 'c'}}, 
            new float[][] {{Float.intBitsToFloat(0x7fc00001)}},
            new double[][] {{1.0}, {Double.NaN, Double.longBitsToDouble(0x7ff8000000000000L)}},
            new byte[][] {{(byte) 1}}, new short[][] {{(short) 10000}},
            new int[][] {{1}, {2, 3}}, new long[][] {{1L, 2L}, {3L}},

            new int[][][] {{{}}},
            new long[][][] {{{1, 2}, {3, 4}}, {{5, 6}, {7, 8}}},
            new int[][][] {{{1, 2}, {3, 4}}, {{5, 6}, {7, 8}},
                           {{9, 10}, {11, 12}}, {{13, 14}, {15, 16}}},

            new List[] {List.of("hello", 10), List.of("world")},
            new Set[] {Set.of("hello", 10), Set.of("world")},
            new Map[] {Map.of("hello", "world")},
            new BigInteger[] {BigInteger.valueOf(123)},
            new BigDecimal[] {BigDecimal.ONE},
        };

        for (Object a : arrays) {
            pipe.writeObject(a);
        }

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        for (Object a : arrays) {
            assertTrue(arrayEquals(a, pipe.readObject()));
        }

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    private static boolean arrayEquals(Object a, Object b) {
        int alen = Array.getLength(a);
        int blen = Array.getLength(b);

        if (alen != blen) {
            return false;
        }

        for (int i=0; i<alen; i++) {
            if (!Objects.deepEquals(Array.get(a, i), Array.get(b, i))) {
                return false;
            }
        }

        return true;
    }

    private static boolean[] randomBooleanArray(Random rnd, int minLen, int maxLen) {
        var array = new boolean[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = rnd.nextBoolean();
        }
        return array;
    }

    private static char[] randomCharArray(Random rnd, int minLen, int maxLen) {
        var array = new char[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = (char) rnd.nextInt();
        }
        return array;
    }

    private static float[] randomFloatArray(Random rnd, int minLen, int maxLen) {
        var array = new float[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = rnd.nextFloat();
        }
        return array;
    }

    private static double[] randomDoubleArray(Random rnd, int minLen, int maxLen) {
        var array = new double[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = rnd.nextDouble();
        }
        return array;
    }

    private static byte[] randomByteArray(Random rnd, int minLen, int maxLen) {
        var array = new byte[minLen + rnd.nextInt(maxLen - minLen + 1)];
        rnd.nextBytes(array);
        return array;
    }

    private static short[] randomShortArray(Random rnd, int minLen, int maxLen) {
        var array = new short[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = (short) rnd.nextInt();
        }
        return array;
    }

    private static int[] randomIntArray(Random rnd, int minLen, int maxLen) {
        var array = new int[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = rnd.nextInt();
        }
        return array;
    }

    private static long[] randomLongArray(Random rnd, int minLen, int maxLen) {
        var array = new long[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = rnd.nextLong();
        }
        return array;
    }

    private static String[] randomStringArray(Random rnd, int minLen, int maxLen) {
        var array = new String[minLen + rnd.nextInt(maxLen - minLen + 1)];
        for (int i=0; i<array.length; i++) {
            array[i] = randomString(rnd, 0, 10);
        }
        return array;
    }

    @Test
    public void collections() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        var rnd = new Random(8675309);

        Object[] collections = {
            randomList(rnd, 10, 200),
            randomList(rnd, 300, 1000),
            randomSet(rnd, 10, 200),
            randomSet(rnd, 300, 1000),
            randomMap(rnd, 10, 200),
            randomMap(rnd, 300, 1000),
        };

        for (Object c : collections) {
            pipe.writeObject(c);
        }

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        for (Object c : collections) {
            assertEquals(c, pipe.readObject());
        }

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    private static List<?> randomList(Random rnd, int minLen, int maxLen) {
        int size = minLen + rnd.nextInt(maxLen - minLen + 1);
        var list = new ArrayList<Object>(size);
        for (int i=0; i<size; i++) {
            list.add(randomString(rnd, 0, 10));
        }
        return list;
    }

    private static Set<?> randomSet(Random rnd, int minLen, int maxLen) {
        int size = minLen + rnd.nextInt(maxLen - minLen + 1);
        var set = new HashSet<Object>(size);
        for (int i=0; i<size; i++) {
            set.add(randomString(rnd, 10, 100));
        }
        return set;
    }

    private static Map<?,?> randomMap(Random rnd, int minLen, int maxLen) {
        int size = minLen + rnd.nextInt(maxLen - minLen + 1);
        var map = new HashMap<Object, Object>(size);
        for (int i=0; i<size; i++) {
            map.put(randomString(rnd, 10, 100), rnd.nextInt());
        }
        return map;
    }

    @Test
    public void exception() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        var ex1 = new NullPointerException();
        var ex2 = new NullPointerException("test");
        ex2.initCause(ex1);
        var ex3 = new IndexOutOfBoundsException();
        ex3.addSuppressed(ex2);

        pipe.writeObject(ex1);
        pipe.writeObject(ex2);
        pipe.writeObject(ex3);
        pipe.writeObject((Object) null);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var rex1 = (Throwable) pipe.readObject();
        var rex2 = (Throwable) pipe.readObject();
        var rex3 = (Throwable) pipe.readObject();

        assertEquals(ex1.getClass(), rex1.getClass());
        assertEquals(ex1.getMessage(), rex1.getMessage());

        assertEquals(ex2.getClass(), rex2.getClass());
        assertEquals(ex2.getMessage(), rex2.getMessage());

        assertEquals(null, rex2.getCause().getMessage());

        assertEquals(ex3.getClass(), rex3.getClass());
        assertEquals("test", rex3.getSuppressed()[0].getMessage());

        assertNull(pipe.readObject());

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void multidimensional() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        String[][] array1 = {
            {"a", "b"}, {"c", "d"}
        };

        String[][][] array2 = {
            {{"a", "b"}, {"c", "d"}},
            {{"e", "f"}, {"g", "h"}},
        };

        pipe.writeObject(array1);
        pipe.writeObject(array2);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        assertTrue(arrayEquals(array1, (String[][]) pipe.readObject()));
        assertTrue(arrayEquals(array2, (String[][][]) pipe.readObject()));

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void typeClass() throws Exception {
        int[] typeCodes = {
            T_TRUE, T_CHAR, T_FLOAT, T_DOUBLE, T_BYTE, T_SHORT, T_INT, T_LONG, T_STRING,
            T_BOOLEAN_ARRAY, T_CHAR_ARRAY, T_FLOAT_ARRAY, T_DOUBLE_ARRAY,
            T_BYTE_ARRAY, T_SHORT_ARRAY, T_INT_ARRAY, T_LONG_ARRAY, T_OBJECT_ARRAY,
            T_LIST, T_SET, T_MAP,
            T_BIG_INTEGER, T_BIG_DECIMAL,
            T_THROWABLE, T_STACK_TRACE,
        };

        TypeCodeMap tcm = TypeCodeMap.STANDARD;
        var pipe = new BufferedPipe(InputStream.nullInputStream(), OutputStream.nullOutputStream());

        for (int typeCode : typeCodes) {
            assertEquals(typeCode, tcm.writeTypeCode(pipe, tcm.typeClass(typeCode)));
        }
    }

    @Test
    public void cycle() throws Exception {
        var list = new ArrayList<Object>();
        list.add(list);

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        pipe.enableReferences();
        pipe.writeObject(list);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var list2 = (List) pipe.readObject();
        assertEquals(1, list2.size());
        assertTrue(list2.get(0) == list2);
    }

    @Test
    public void voidObject() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        Object obj = new Object();
        pipe.writeObject(Void.TYPE);
        pipe.writeObject(Void.TYPE);

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        assertEquals(Void.TYPE, pipe.readObject());
        assertEquals(Void.TYPE, pipe.readObject());
    }

    @Test
    public void plainObject() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        pipe.enableReferences();

        Object obj = new Object();
        pipe.writeObject(obj);
        pipe.writeObject(obj);

        pipe.disableReferences();

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var obj1 = pipe.readObject();
        var obj2 = pipe.readObject();

        assertNotNull(obj1);
        assertSame(obj1, obj2);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void bigMap() throws Exception {
        var map = new TreeMap<String, String>();

        for (int i=0; i<2000; i++) {
            map.put(("" + i).intern(), ("" + (i % 1000)).intern());
        }

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        pipe.enableReferences();
        pipe.writeObject(map);
        pipe.writeObject("0");
        pipe.disableReferences();
        pipe.writeObject("0");

        pipe.flush();
        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var map2 = (Map<String, String>) pipe.readObject();
        assertEquals(map, map2);
        assertEquals("0", map2.get("1000"));
        assertTrue(map2.get("0") == map2.get("1000"));

        Object s1 = pipe.readObject();
        Object s2 = pipe.readObject();

        assertEquals("0", s1);
        assertEquals("0", s2);

        assertTrue(s1 != s2);
        assertTrue(map2.get("0") == s1);
        assertTrue(map2.get("0") != s2);

        try {
            pipe.readObject();
            fail();
        } catch (ClosedException e) {
        }
    }

    @Test
    public void brokenDisable() throws Exception {
        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        try {
            pipe.disableReferences();
            fail();
        } catch (IllegalStateException e) {
        }
    }

    @Test
    public void fuzz() throws Exception {
        long seed = 8675309;
        for (int i=0; i<100; i++) {
            fuzz(seed, (i & 1) != 0);
            seed *= 31;
        }
    }

    private void fuzz(long seed, boolean streams) throws Exception {
        var rnd = new Random(seed);

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);

        pipe.flush(); // should do nothing

        int max = 100;
        int num = rnd.nextInt(max) + 1;

        ObjectOutput out = streams ? ((ObjectOutput) pipe.outputStream()) : pipe;

        for (int i=0; i<num; i++) {
            writeRandom(rnd, out);
        }

        pipe.outputStream().close();
        byte[] bytes = capture.getBytes();

        rnd = new Random(seed);
        assertEquals(num, rnd.nextInt(max) + 1);

        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        ObjectInput in = streams ? ((ObjectInput) pipe.inputStream()) : pipe;

        for (int i=0; i<num; i++) {
            verifyRandom(rnd, in);
        }

        assertTrue(pipe.read() < 0);
    }

    private static void writeRandom(Random rnd, ObjectOutput out) throws Exception {
        int which = rnd.nextInt(18);

        switch (which) {
        default: {
            throw new AssertionError();
        }
        case 0: {
            boolean v = rnd.nextBoolean();
            out.writeBoolean(v);
            break;
        }
        case 1: {
            byte v = (byte) rnd.nextInt();
            out.writeByte(v);
            break;
        }
        case 2: {
            short v = (short) rnd.nextInt();
            out.writeShort(v);
            break;
        }
        case 3: {
            char v = (char) rnd.nextInt();
            out.writeChar(v);
            break;
        }
        case 4: {
            int v = rnd.nextInt();
            out.writeInt(v);
            break;
        }
        case 5: {
            long v = rnd.nextLong();
            out.writeLong(v);
            break;
        }
        case 6: {
            float v = rnd.nextFloat();
            out.writeFloat(v);
            break;
        }
        case 7: {
            double v = rnd.nextDouble();
            out.writeDouble(v);
            break;
        }
        case 8: {
            String v = randomString(rnd, 0, 10);
            out.writeBytes(v);
            break;
        }
        case 9: {
            String v = randomString(rnd, 0, 10);
            out.writeChars(v);
            break;
        }
        case 10: case 11: case 12: case 13: {
            String v = randomString(rnd, 0, 10000);
            out.writeUTF(v);
            break;
        }
        case 14: case 15: case 16: case 17: {
            var v = new byte[rnd.nextInt(10000)];
            rnd.nextBytes(v);
            out.write(v);
            break;
        }
        }
    }

    private static void verifyRandom(Random rnd, ObjectInput in) throws Exception {
        int which = rnd.nextInt(18);

        switch (which) {
        default: {
            throw new AssertionError();
        }
        case 0: {
            boolean expect = rnd.nextBoolean();
            boolean v = in.readBoolean();
            assertEquals(expect, v);
            break;
        }
        case 1: {
            byte expect = (byte) rnd.nextInt();
            byte v = in.readByte();
            assertEquals(expect, v);
            break;
        }
        case 2: {
            short expect = (short) rnd.nextInt();
            short v = in.readShort();
            assertEquals(expect, v);
            break;
        }
        case 3: {
            char expect = (char) rnd.nextInt();
            char v = in.readChar();
            assertEquals(expect, v);
            break;
        }
        case 4: {
            int expect = rnd.nextInt();
            int v = in.readInt();
            assertEquals(expect, v);
            break;
        }
        case 5: {
            long expect = rnd.nextLong();
            long v = in.readLong();
            assertEquals(expect, v);
            break;
        }
        case 6: {
            float expect = rnd.nextFloat();
            float v = in.readFloat();
            assertTrue(expect == v);
            break;
        }
        case 7: {
            double expect = rnd.nextDouble();
            double v = in.readDouble();
            assertTrue(expect == v);
            break;
        }
        case 8: {
            String expect = randomString(rnd, 0, 10);
            var v = new byte[expect.length()];
            in.readFully(v);
            for (int i=0; i<v.length; i++) {
                assertEquals((byte) expect.charAt(i), v[i]);
            }
            break;
        }
        case 9: {
            String expect = randomString(rnd, 0, 10);
            var v = new byte[expect.length() * 2];
            in.readFully(v);
            for (int i=0; i<v.length; i+=2) {
                assertEquals(expect.charAt(i >> 1), (char) (v[i] << 8 | (v[i + 1] & 0xff)));
            }
            break;
        }
        case 10: case 11: case 12: case 13: {
            String expect = randomString(rnd, 0, 10000);
            String v = in.readUTF();
            assertEquals(expect, v);
            break;
        }
        case 14: case 15: case 16: case 17: {
            var expect = new byte[rnd.nextInt(10000)];
            rnd.nextBytes(expect);
            var v = new byte[expect.length];
            in.readFully(v);
            assertTrue(Arrays.equals(expect, v));
            break;
        }
        }
    }

    static String randomString(Random rnd, int minLen, int maxLen) {
        return randomString(rnd, minLen, maxLen, Character.MAX_CODE_POINT);
    }

    static String randomString(Random rnd, int minLen, int maxLen, int maxCodePoint) {
        var codepoints = new int[minLen + rnd.nextInt(maxLen - minLen + 1)];

        for (int i=0; i<codepoints.length; i++) {
            switch (rnd.nextInt(3)) {
            case 0: {
                codepoints[i] = rnd.nextInt(0x80); // single byte UTF-8 chars
                break;
            }
            case 1: {
                codepoints[i] = rnd.nextInt(0x800); // double byte UTF-8 chars
                break;
            }
            case 2: {
                while (true) {
                    int cp = rnd.nextInt(maxCodePoint + 1);
                    // Exclude codepoints in the surrogate pair range.
                    if (!(0xd800 <= cp && cp <= 0xdfff)) {
                        codepoints[i] = cp;
                        break;
                    }
                }
                break;
            }
            }
        }

        return new String(codepoints, 0, codepoints.length);
    }
}
