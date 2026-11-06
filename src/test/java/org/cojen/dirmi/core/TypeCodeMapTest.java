/*
 *  Copyright 2026 Cojen.org
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.OutputStream;
 
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
 
import org.junit.*;
import static org.junit.Assert.*;
 
import org.cojen.dirmi.Serializer;
 
import static org.cojen.dirmi.core.TypeCodes.*;
 
/**
 * Tests that custom type codes can start above T_FIRST_CUSTOM. This happens when the remote side
 * is a newer version which defined more standard types, and bumped up T_FIRST_CUSTOM. Both
 * sides exchange their first custom type code, and the highest one is used by both sides.
 *
 * @author Claude
 */
public class TypeCodeMapTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(TypeCodeMapTest.class.getName());
    }
 
    public static class A {
        public int v = 1;
 
        @Override
        public boolean equals(Object obj) {
            return obj instanceof A a && v == a.v;
        }
 
        @Override
        public int hashCode() {
            return v;
        }
    }
 
    public static class B {
        public String s = "b";
 
        @Override
        public boolean equals(Object obj) {
            return obj instanceof B b && s.equals(b.s);
        }
 
        @Override
        public int hashCode() {
            return s.hashCode();
        }
    }
 
    public static class C extends A { }
 
    public static class D extends B { }
 
    public enum E { X, Y, Z }
 
    public record R(int a, String b) { }
 
    private static final Class<?>[] TYPES = {A.class, B.class, C.class, D.class, E.class, R.class};
 
    private static final int SENTINEL = 0x5eed5eed;
 
    /**
     * Returns a map in which the custom type codes are assigned starting at the given value.
     */
    private static TypeCodeMap map(int firstCode) {
        var serializers = new LinkedHashMap<Object, Serializer>();
        for (Class<?> type : TYPES) {
            serializers.put(type, Serializer.simple(type));
        }
        return TypeCodeMap.find(firstCode, serializers);
    }
 
    private static Object[] samples() {
        var c = new C();
        c.v = 3;
        var d = new D();
        d.s = "d";
        return new Object[] {
            new A(), new B(), c, d, E.Y, new R(5, "five"),
            // Arrays of custom types need the type code to be converted back into a class.
            new A[] {new A(), null}, new E[] {E.Z, E.X}, new R[] {new R(1, "one")},
            // A mix of standard and custom types.
            new Object[] {"standard", 5, new A(), E.X, new Object[] {new B()}},
        };
    }
 
    private static byte[] write(TypeCodeMap tcm, Object obj) throws Exception {
        var capture = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        pipe.initTypeCodeMap(tcm);
        pipe.writeObject(obj);
        pipe.writeInt(SENTINEL);
        pipe.flush();
        return capture.toByteArray();
    }
 
    private static BufferedPipe reader(TypeCodeMap tcm, byte[] bytes) {
        var pipe = new BufferedPipe
            (new ByteArrayInputStream(bytes), OutputStream.nullOutputStream());
        pipe.initTypeCodeMap(tcm);
        return pipe;
    }
 
    /**
     * Writes the object, reads it back, and also skips over it. Both reading and skipping must
     * leave the pipe at the sentinel.
     */
    private static void roundTrip(TypeCodeMap tcm, Object obj, String desc) throws Exception {
        byte[] bytes = write(tcm, obj);
 
        BufferedPipe pipe = reader(tcm, bytes);
        Object copy = pipe.readObject();
        assertEquals(desc + ": out of sync after read", SENTINEL, pipe.readInt());
 
        if (obj.getClass().isArray()) {
            assertEquals(desc, obj.getClass(), copy.getClass());
            assertTrue(desc + ": " + Arrays.deepToString(new Object[] {copy}),
                       Objects.deepEquals(obj, copy));
        } else {
            assertEquals(desc, obj, copy);
        }
 
        pipe = reader(tcm, bytes);
        pipe.skipObject(null);
        assertEquals(desc + ": out of sync after skip", SENTINEL, pipe.readInt());
    }
 
    /**
     * The standard first custom type code has always worked.
     */
    @Test
    public void standardFirstCode() throws Exception {
        check(T_FIRST_CUSTOM);
    }
 
    /**
     * The remote side defined more standard types. The number of extra types is arbitrary.
     */
    @Test
    public void higherFirstCode() throws Exception {
        for (int extra : new int[] {1, 2, 3, 10}) {
            check(T_FIRST_CUSTOM + extra);
        }
    }
 
    /**
     * The type codes are one byte when less than 256, two bytes when less than 65536, and
     * otherwise four bytes. The first code is high enough here for the custom type codes to
     * cross the boundaries between these sizes.
     */
    @Test
    public void codeSizeBoundaries() throws Exception {
        check(253);          // 253, 254, 255, 256, 257, 258
        check(256);          // all two bytes
        check(65533);        // 65533, 65534, 65535, 65536, 65537, 65538
    }
 
    private static void check(int firstCode) throws Exception {
        TypeCodeMap tcm = map(firstCode);
        for (Object obj : samples()) {
            roundTrip(tcm, obj, "first code " + firstCode + ", " + describe(obj));
        }
    }
 
    private static String describe(Object obj) {
        return obj.getClass().getSimpleName();
    }
 
    /**
     * A type code which is higher than the first one that this side knows about, but lower than
     * the first custom type code, is a standard type which only the newer side knows about. It
     * isn't a custom type, and it cannot be understood. This must be reported as a normal
     * exception, and it must not be confused with a custom type.
     */
    @Test
    public void unknownStandardType() throws Exception {
        TypeCodeMap tcm = map(T_FIRST_CUSTOM + 3);
 
        for (int code = T_FIRST_CUSTOM; code < T_FIRST_CUSTOM + 3; code++) {
            try {
                reader(tcm, new byte[] {(byte) code, 0, 0, 0, 0}).readObject();
                fail("Type was accepted: " + code);
            } catch (InvalidObjectException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Unknown type"));
            }
        }
    }
}
