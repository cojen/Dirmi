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

import java.net.ServerSocket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.*;
import static org.junit.Assert.*;

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

        env.customSerializers
            (Map.of(StringBuilder.class, new StringBuilderSerializer(),
                    ConcurrentHashMap.class, new ConcurrentHashMapSerializer())
             );

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
            (Map.of(ConcurrentHashMap.class, new ConcurrentHashMapSerializer(),
                    UUID.class, new UUIDSerializer(),
                    Optional.class, new OptionalSerializer())
             );

        var serverEnv = Environment.create();

        serverEnv.customSerializers
            (Map.of(StringBuilder.class, new StringBuilderSerializer(),
                    UUID.class, new UUIDSerializer())
             );

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

        env.customSerializers(Map.of(UUID.class, new UUIDSerializer()));

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

        env.customSerializers(Map.of(UUID.class, new UUIDSerializer()));

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

    private static class ConcurrentHashMapSerializer implements Serializer<ConcurrentHashMap<?,?>> {
        @Override
            public void write(Pipe pipe, ConcurrentHashMap<?,?> map) throws IOException {
            pipe.writeInt(map.size());
            for (Map.Entry e : map.entrySet()) {
                pipe.writeObject(e.getKey());
                pipe.writeObject(e.getValue());
            }
        }

        @Override
        public ConcurrentHashMap<?,?> read(Pipe pipe) throws IOException {
            int size = pipe.readInt();
            var map = new ConcurrentHashMap<>();
            for (int i=0; i<size; i++) {
                map.put(pipe.readObject(), pipe.readObject());
            }
            return map;
        }
    }

    private static class StringBuilderSerializer implements Serializer<StringBuilder> {
        @Override
        public void write(Pipe pipe, StringBuilder obj) throws IOException {
            pipe.writeObject(obj.toString());
        }

        @Override
        public StringBuilder read(Pipe pipe) throws IOException {
            return new StringBuilder((String) pipe.readObject());
        }
    }

    private static class UUIDSerializer implements Serializer<UUID> {
        @Override
        public void write(Pipe pipe, UUID obj) throws IOException {
            pipe.writeLong(obj.getMostSignificantBits());
            pipe.writeLong(obj.getLeastSignificantBits());
        }

        @Override
        public UUID read(Pipe pipe) throws IOException {
            return new UUID(pipe.readLong(), pipe.readLong());
        }
    }

    private static class OptionalSerializer implements Serializer<Optional<?>> {
        @Override
        public void write(Pipe pipe, Optional<?> obj) throws IOException {
            pipe.writeObject(obj.orElse(null));
        }

        @Override
        public Optional<?> read(Pipe pipe) throws IOException {
            return Optional.ofNullable(pipe.readObject());
        }
    }
}
