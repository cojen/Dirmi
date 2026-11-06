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
import java.io.OutputStream;
 
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
 
import org.junit.*;
import static org.junit.Assert.*;
 
import org.cojen.dirmi.Serializer;
 
/**
 * Tests that Serializer.simple behaves correctly when a class has a public field which hides a
 * public field of a superclass. Class.getFields returns both of them, but only the one in the
 * most derived class can be accessed by name.
 *
 * <p>Either of two behaviors is acceptable: the hidden field is ignored, or else the type is
 * rejected with an IllegalArgumentException. What isn't acceptable is a serializer whose
 * write, read, and skip operations disagree with each other.
 *
 * @author Claude
 */
public class SerializerHiddenFieldTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SerializerHiddenFieldTest.class.getName());
    }
 
    public static class Base {
        public int x = 1;
        public int z = 100;
    }
 
    /** Hides Base.x with a field of a different type. */
    public static class DifferentType extends Base {
        public String x = "derived";
        public int y = 7;
    }
 
    /** Hides Base.x with a field of the same type. */
    public static class SameType extends Base {
        public int x = 2;
        public int y = 8;
    }
 
    /*
     * Fields which hide Base.x, but which aren't serializable themselves. Code which accesses
     * x by name through one of these types gets the hiding field, and so the public field of
     * Base cannot simply be serialized instead. It's important that these hiding fields are
     * considered before any field is filtered out.
     */
 
    public static class PrivateHider extends Base {
        @SuppressWarnings("unused")
        private String x = "private";
        public int y = 7;
    }
 
    public static class ProtectedHider extends Base {
        @SuppressWarnings("unused")
        protected String x = "protected";
        public int y = 7;
    }
 
    public static class PackageHider extends Base {
        @SuppressWarnings("unused")
        String x = "package";
        public int y = 7;
    }
 
    public static class TransientHider extends Base {
        public transient String x = "transient";
        public int y = 7;
    }
 
    public static class StaticHider extends Base {
        public static String x = "static";
        public int y = 7;
    }
 
    private static final int SENTINEL = 0x5eed5eed;
 
    /**
     * @return null if the type was rejected, which is an acceptable outcome
     */
    private static Serializer serializerFor(Class<?> type) {
        try {
            return Serializer.simple(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
 
    private static byte[] write(Serializer ser, Object obj) throws Exception {
        var capture = new ByteArrayOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        ser.write(pipe, obj);
        pipe.writeInt(SENTINEL);
        pipe.flush();
        return capture.toByteArray();
    }
 
    private static BufferedPipe reader(byte[] bytes) {
        return new BufferedPipe(new ByteArrayInputStream(bytes), OutputStream.nullOutputStream());
    }
 
    /**
     * Reading what was written must leave the pipe at the sentinel.
     */
    private static Object readAndCheck(Serializer ser, byte[] bytes) throws Exception {
        BufferedPipe pipe = reader(bytes);
        Object obj = ser.read(pipe);
        assertEquals("Pipe is out of sync after read", SENTINEL, pipe.readInt());
        return obj;
    }
 
    /**
     * Skipping what was written must also leave the pipe at the sentinel.
     */
    private static void skipAndCheck(Serializer ser, byte[] bytes) throws Exception {
        BufferedPipe pipe = reader(bytes);
        ser.skip(pipe, null);
        assertEquals("Pipe is out of sync after skip", SENTINEL, pipe.readInt());
    }
 
    @Test
    public void differentType() throws Exception {
        Serializer ser = serializerFor(DifferentType.class);
        if (ser == null) {
            return;
        }
 
        byte[] bytes = write(ser, new DifferentType());
 
        var copy = (DifferentType) readAndCheck(ser, bytes);
        assertEquals("derived", copy.x);
        assertEquals(7, copy.y);
        assertEquals(100, copy.z);
 
        skipAndCheck(ser, bytes);
    }
 
    @Test
    public void sameType() throws Exception {
        Serializer ser = serializerFor(SameType.class);
        if (ser == null) {
            return;
        }
 
        byte[] bytes = write(ser, new SameType());
 
        var copy = (SameType) readAndCheck(ser, bytes);
        assertEquals(2, copy.x);
        assertEquals(8, copy.y);
        assertEquals(100, copy.z);
 
        skipAndCheck(ser, bytes);
    }
 
    /**
     * A field which can't be serialized still hides the field of a superclass which has the same
     * name. The serializer must either reject the type, or else work correctly, which means that
     * no data of the wrong type is written, and that write, read, and skip all agree.
     */
    @Test
    public void hiddenByUnserializableField() throws Exception {
        Class<?>[] types = {
            PrivateHider.class, ProtectedHider.class, PackageHider.class,
            TransientHider.class, StaticHider.class
        };
 
        for (Class<?> type : types) {
            String name = type.getSimpleName();
 
            Serializer ser = serializerFor(type);
            if (ser == null) {
                continue;
            }
 
            Object obj = type.getConstructor().newInstance();
            byte[] bytes = write(ser, obj);
 
            Object copy = readAndCheck(ser, bytes);
            assertEquals(name, type, copy.getClass());
            assertEquals(name, 7, copy.getClass().getField("y").getInt(copy));
            assertEquals(name, 100, ((Base) copy).z);
 
            skipAndCheck(ser, bytes);
 
            List<String> names = fieldNames((String) ser.descriptor());
            assertEquals(name + " descriptor: " + ser.descriptor(),
                         names.size(), new HashSet<>(names).size());
 
            // The field which hides Base.x isn't serialized, and so neither is Base.x.
            assertTrue(name + " descriptor: " + ser.descriptor(), !names.contains("x"));
        }
    }
 
    /**
     * Each field name can appear only once in the descriptor, because the descriptor is
     * parsed into a map. A repeated name means that the remote side sees only one of the
     * entries, which can have the wrong type.
     */
    @Test
    public void descriptorHasNoDuplicateNames() throws Exception {
        for (Class<?> type : new Class<?>[] {DifferentType.class, SameType.class}) {
            Serializer ser = serializerFor(type);
            if (ser == null) {
                continue;
            }
 
            String descriptor = (String) ser.descriptor();
            List<String> names = fieldNames(descriptor);
 
            assertEquals(type.getSimpleName() + " descriptor: " + descriptor,
                         names.size(), new HashSet<>(names).size());
        }
    }
 
    /**
     * Parses the field names from a descriptor, which is a sequence of: name ';' type
     * descriptor. Type descriptors are either a single character, an object type which ends
     * with ';', or an array of these.
     */
    private static List<String> fieldNames(String descriptor) {
        var names = new ArrayList<String>();
        int i = 0;
        while (i < descriptor.length()) {
            int semi = descriptor.indexOf(';', i);
            names.add(descriptor.substring(i, semi));
            i = semi + 1;
            while (descriptor.charAt(i) == '[') {
                i++;
            }
            if (descriptor.charAt(i) == 'L') {
                i = descriptor.indexOf(';', i) + 1;
            } else {
                i++;
            }
        }
        return names;
    }
}
