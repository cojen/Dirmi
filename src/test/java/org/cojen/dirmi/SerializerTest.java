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

package org.cojen.dirmi;

import java.io.IOException;

import java.lang.reflect.Constructor;

import java.net.ServerSocket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SerializerTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SerializerTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        var env = Environment.create();

        env.customSerializers(new StringBuilderSerializer(), new ConcurrentHashMapSerializer());

        env.export("main", new R1Server());

        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        Object obj = r1.echo(new StringBuilder("hello"));
        assertTrue(obj instanceof StringBuilder);
        assertEquals("hello", obj.toString());

        var map = new ConcurrentHashMap<String, String>();
        map.put("hello", "world");

        obj = r1.echo(map);
        assertTrue(obj instanceof ConcurrentHashMap);
        assertEquals("world", ((ConcurrentHashMap) obj).get("hello"));

        var map2 = new HashMap<String, String>();
        map2.put("hello!", "world!");
        obj = r1.echo(map2);
        assertTrue(!(obj instanceof ConcurrentHashMap));
        assertEquals("world!", ((Map) obj).get("hello!"));

        env.close();
    }

    @Test
    public void mismatch() throws Exception {
        var clientEnv = Environment.create();

        clientEnv.customSerializers
            (new ConcurrentHashMapSerializer(), new UUIDSerializer(), new OptionalSerializer());

        var serverEnv = Environment.create();

        serverEnv.customSerializers(new StringBuilderSerializer(), new UUIDSerializer());

        serverEnv.export("main", new R1Server());

        var ss = new ServerSocket(0);
        serverEnv.acceptAll(ss);

        var session = clientEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        var map = new ConcurrentHashMap<String, String>();
        map.put("hello", "world");

        // Server doesn't support ConcurrentHashMap, and so null is received.
        Object obj = r1.echo(map);
        assertNull(obj);

        // Client doesn't support StringBuilder, and so null is received.
        obj = r1.invent(StringBuilder.class.getName());
        assertNull(obj);

        // Both sides support UUID.
        UUID uuid = UUID.randomUUID();
        obj = r1.echo(uuid);
        assertEquals(uuid, obj);

        clientEnv.close();
        serverEnv.close();
    }

    @Test
    public void array() throws Exception {
        var env = Environment.create();

        env.customSerializers(new UUIDSerializer());

        env.export("main", new R1Server());

        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        UUID[] array = { UUID.randomUUID(), UUID.randomUUID() };

        Object obj = r1.echo(array);
        assertArrayEquals(array, (UUID[]) obj);

        UUID[][] array2 = {
            { UUID.randomUUID(), UUID.randomUUID() },
            { UUID.randomUUID(), UUID.randomUUID() }
        };

        obj = r1.echo(array2);
        assertArrayEquals(array2, (UUID[][]) obj);

        env.close();
    }

    @Test
    public void list() throws Exception {
        var env = Environment.create();

        env.customSerializers(new UUIDSerializer());

        env.export("main", new R1Server());

        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        List<UUID> list = List.of(UUID.randomUUID(), UUID.randomUUID());

        Object obj = r1.echo(list);
        assertEquals(list, (List) obj);

        env.close();
    }

    @Test
    public void simple() throws Exception {
        var env = Environment.create();

        env.customSerializers(
            Serializer.simple(PointClass.class),
            Serializer.simple(PointRec.class),
            Serializer.simple(SomeClass.class),
            Serializer.simple(Thread.State.class),
            // No public fields, and so it won't serialize anything.
            Serializer.simple(HashMap.class)
        );

        env.export("main", new R1Server());

        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 root = session.root();

        assertEquals(new PointClass(1, 2), root.echo(new PointClass(1, 2)));
        assertEquals(new PointRec(1, 2), root.echo(new PointRec(1, 2)));
        assertEquals(new SomeClass(null, null, "hello"),
                     root.echo(new SomeClass("x", "y", "hello")));

        assertEquals(Thread.State.NEW, root.echo(Thread.State.NEW));
        assertEquals(Thread.State.WAITING, root.echo(Thread.State.WAITING));

        var map = new HashMap<String, String>();
        map.put("hello", "world");
        assertEquals(new HashMap<>(), root.echo(map));

        env.close();
    }

    public static class PointClass {
        public int x, y;

        public PointClass() {
        }

        PointClass(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            var other = (PointClass) obj;
            return x == other.x && y == other.y;
        }
    }

    public record PointRec(int x, int y) {}

    public static class SomeClass {
        public SomeClass() {
        }

        SomeClass(String b, String c, String d) {
            this.b = b;
            this.c = c;
            this.d = d;
        }

        public static String a;
        public transient String b;
        String c;
        public String d;

        @Override
        public boolean equals(Object obj) {
            var other = (SomeClass) obj;
            return Objects.equals(b, other.b)
                && Objects.equals(c, other.c)
                && Objects.equals(d, other.d);
        }

        @Override
        public String toString() {
            return "b=" + b + ", c=" + c + ", d=" + d;
        }
    }

    @Test
    public void simpleWrong() throws Exception {
        record Rec() { }

        try {
            Serializer.simple(Rec.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Not public"));
        }

        try {
            Serializer.simple(List.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("No public no-arg"));
        }
    }

    @Test
    public void adaptRecord() throws Exception {
        String name = getClass().getName() + "$AdaptRecord";

        Class<?> rec1Class;
        Constructor<?> rec1Ctor;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "rec1").public_();
            cm.addField(int.class, "a");
            cm.addField(double[].class, "c");
            cm.addField(String.class, "d");
            cm.asRecord();
            rec1Class = cm.finish();

            rec1Ctor = rec1Class.getConstructor(int.class, double[].class, String.class);
        }

        Class<?> rec2Class;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "rec2").public_();
            cm.addField(String[].class, "c");
            cm.addField(String.class, "b");
            cm.addField(int.class, "a");
            cm.asRecord();
            rec2Class = cm.finish();
        }

        var clientEnv = Environment.create();
        clientEnv.customSerializers(Serializer.simple(rec1Class));

        var serverEnv = Environment.create();
        serverEnv.customSerializers(Serializer.simple(rec2Class));
        serverEnv.export("main", new R1Server());

        var ss = new ServerSocket(0);
        serverEnv.acceptAll(ss);

        var session = clientEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 root = session.root();

        Object rec1 = rec1Ctor.newInstance(123, new double[] {3.14}, "hello");

        Object result = root.echo(rec1);

        assertEquals(123, rec1Class.getMethod("a").invoke(result));
        assertNull(rec1Class.getMethod("c").invoke(result));
        assertNull(rec1Class.getMethod("d").invoke(result));

        clientEnv.close();
        serverEnv.close();
    }

    @Test
    public void adaptClass() throws Exception {
        String name = getClass().getName() + "$AdaptClass";

        Class<?> rec1Class;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "class1").public_();
            cm.addField(int.class, "a").public_();
            cm.addField(double[].class, "c").public_();
            cm.addField(String.class, "d").public_();
            cm.addConstructor().public_();
            rec1Class = cm.finish();
        }

        Class<?> rec2Class;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "class2").public_();
            cm.addField(String[].class, "c").public_();
            cm.addField(String.class, "b").public_();
            cm.addField(int.class, "a").public_();
            cm.addConstructor().public_();
            rec2Class = cm.finish();
        }

        var clientEnv = Environment.create();
        clientEnv.customSerializers(Serializer.simple(rec1Class));

        var serverEnv = Environment.create();
        serverEnv.customSerializers(Serializer.simple(rec2Class));
        serverEnv.export("main", new R1Server());

        var ss = new ServerSocket(0);
        serverEnv.acceptAll(ss);

        var session = clientEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 root = session.root();

        Object rec1 = rec1Class.getConstructor().newInstance();

        rec1Class.getField("a").set(rec1, 123);
        rec1Class.getField("c").set(rec1, new double[] {3.14});
        rec1Class.getField("d").set(rec1, "hello");

        Object result = root.echo(rec1);

        assertEquals(123, rec1Class.getField("a").get(result));
        assertNull(rec1Class.getField("c").get(result));
        assertNull(rec1Class.getField("d").get(result));

        clientEnv.close();
        serverEnv.close();
    }

    @Test
    public void adaptEnum() throws Exception {
        String name = getClass().getName() + "$AdaptEnum";

        Class<?> enum1Class;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "enum1").public_().enum_();
            cm.extend(Enum.class);

            cm.addField(cm, "A").public_().static_().final_();
            cm.addField(cm, "B").public_().static_().final_();
            cm.addField(cm, "D").public_().static_().final_();

            MethodMaker mm = cm.addConstructor(String.class, int.class).private_();
            mm.invokeSuperConstructor(mm.param(0), mm.param(1));

            mm = cm.addClinit();
            mm.field("A").set(mm.new_(cm, "A", 2));
            mm.field("B").set(mm.new_(cm, "B", 1));
            mm.field("D").set(mm.new_(cm, "D", 0));
            
            mm = cm.addMethod(cm.arrayType(1), "values").public_().static_();
            var arrayVar = mm.new_(cm.arrayType(1), 3);
            arrayVar.aset(0, mm.field("D"));
            arrayVar.aset(1, mm.field("B"));
            arrayVar.aset(2, mm.field("A"));
            mm.return_(arrayVar);

            enum1Class = cm.finish();
        }

        Class<?> enum2Class;
        {
            ClassMaker cm = ClassMaker.beginExplicit(name, null, "enum2").public_().enum_();
            cm.extend(Enum.class);

            cm.addField(cm, "B").public_().static_().final_();
            cm.addField(cm, "C").public_().static_().final_();

            MethodMaker mm = cm.addConstructor(String.class, int.class).private_();
            mm.invokeSuperConstructor(mm.param(0), mm.param(1));

            mm = cm.addClinit();
            mm.field("B").set(mm.new_(cm, "B", 0));
            mm.field("C").set(mm.new_(cm, "C", 1));

            mm = cm.addMethod(cm.arrayType(1), "values").public_().static_();
            var arrayVar = mm.new_(cm.arrayType(1), 2);
            arrayVar.aset(0, mm.field("B"));
            arrayVar.aset(1, mm.field("C"));
            mm.return_(arrayVar);

            enum2Class = cm.finish();
        }

        var clientEnv = Environment.create();
        clientEnv.customSerializers(Serializer.simple(enum1Class));

        var serverEnv = Environment.create();
        serverEnv.customSerializers(Serializer.simple(enum2Class));
        serverEnv.export("main", new R1Server());

        var ss = new ServerSocket(0);
        serverEnv.acceptAll(ss);

        var session = clientEnv.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 root = session.root();

        Object A1 = enum1Class.getField("A").get(null);
        Object B1 = enum1Class.getField("B").get(null);
        Object D1 = enum1Class.getField("D").get(null);

        assertNull(root.echo(A1));
        assertEquals(B1, root.echo(B1));
        assertNull(root.echo(D1));

        clientEnv.close();
        serverEnv.close();
    }

    public static interface R1 extends Remote {
        Object echo(Object v) throws RemoteException;

        Object invent(String type) throws RemoteException;
    }

    private static class R1Server implements R1 {
        @Override
        public Object echo(Object v) {
            return v;
        }

        @Override
        public Object invent(String type) {
            if (type.equals(StringBuilder.class.getName())) {
                return new StringBuilder("hello!!!");
            }
            throw new IllegalArgumentException();
        }
    }

    private static class ConcurrentHashMapSerializer implements Serializer {
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(ConcurrentHashMap.class);
        }

        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            var map = (ConcurrentHashMap<?,?>) obj;
            pipe.writeInt(map.size());
            for (Map.Entry e : map.entrySet()) {
                pipe.writeObject(e.getKey());
                pipe.writeObject(e.getValue());
            }
        }

        @Override
        public Object read(Pipe pipe) throws IOException {
            int size = pipe.readInt();
            var map = new ConcurrentHashMap<>();
            for (int i=0; i<size; i++) {
                map.put(pipe.readObject(), pipe.readObject());
            }
            return map;
        }
    }

    private static class StringBuilderSerializer implements Serializer {
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(StringBuilder.class);
        }

        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            pipe.writeObject(((StringBuilder) obj).toString());
        }

        @Override
        public Object read(Pipe pipe) throws IOException {
            return new StringBuilder((String) pipe.readObject());
        }
    }

    private static class UUIDSerializer implements Serializer {
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(UUID.class);
        }

        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            var uuid = (UUID) obj;
            pipe.writeLong(uuid.getMostSignificantBits());
            pipe.writeLong(uuid.getLeastSignificantBits());
        }

        @Override
        public Object read(Pipe pipe) throws IOException {
            return new UUID(pipe.readLong(), pipe.readLong());
        }
    }

    private static class OptionalSerializer implements Serializer {
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(Optional.class);
        }

        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            pipe.writeObject(((Optional<?>) obj).orElse(null));
        }

        @Override
        public Object read(Pipe pipe) throws IOException {
            return Optional.ofNullable(pipe.readObject());
        }
    }
}
