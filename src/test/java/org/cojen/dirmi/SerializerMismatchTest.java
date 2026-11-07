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

package org.cojen.dirmi;
 
import java.io.IOException;
 
import java.net.ServerSocket;
 
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
 
import org.junit.*;
import static org.junit.Assert.*;
 
/**
 * Tests what happens when the two sides of a session use incompatible serializers for the same
 * type. For example, one side has a hand-written serializer and the other side uses
 * Serializer.simple. The serializer which cannot adapt must discard the objects, as documented
 * by Serializer.adapt. The session must still be usable, and it must not hang or throw an
 * unexpected exception.
 *
 * @author Claude
 */
public class SerializerMismatchTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SerializerMismatchTest.class.getName());
    }
 
    public static class Foo {
        public int a = 42;
        public String b = "hello";
    }
 
    public enum E { A, B, C, D }
 
    public record R(int a, String b) { }
 
    public static interface Echo extends Remote {
        Object echo(Object obj) throws RemoteException;
    }
 
    public static class EchoServer implements Echo {
        @Override
        public Object echo(Object obj) {
            return obj;
        }
    }
 
    /**
     * A hand-written serializer which has a descriptor and which can adapt only to itself.
     */
    private static class Described implements Serializer {
        private final String mDescriptor;
 
        Described(String descriptor) {
            mDescriptor = descriptor;
        }
 
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(Foo.class);
        }
 
        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            var foo = (Foo) obj;
            pipe.writeInt(foo.a);
            pipe.writeObject(foo.b);
        }
 
        @Override
        public Object read(Pipe pipe) throws IOException {
            var foo = new Foo();
            foo.a = pipe.readInt();
            foo.b = (String) pipe.readObject();
            return foo;
        }
 
        @Override
        public Object descriptor() {
            return mDescriptor;
        }
 
        @Override
        public Serializer adapt(Object descriptor) {
            return mDescriptor.equals(descriptor) ? this : null;
        }
    }
 
    /**
     * A typical hand-written serializer, which uses the default descriptor (null) and the
     * default adapt method.
     */
    private static class Plain implements Serializer {
        @Override
        public Set<Class<?>> supportedTypes() {
            return Set.of(Foo.class);
        }
 
        @Override
        public void write(Pipe pipe, Object obj) throws IOException {
            var foo = (Foo) obj;
            pipe.writeInt(foo.a);
            pipe.writeObject(foo.b);
        }
 
        @Override
        public Object read(Pipe pipe) throws IOException {
            var foo = new Foo();
            foo.a = pipe.readInt();
            foo.b = (String) pipe.readObject();
            return foo;
        }
    }
 
    private Environment mServerEnv;
    private ServerSocket mServerSocket;
    private final List<Environment> mClientEnvs = new ArrayList<>();
    private final List<Throwable> mUncaught = Collections.synchronizedList(new ArrayList<>());
 
    @After
    public void teardown() throws Exception {
        for (Environment env : mClientEnvs) {
            env.close();
        }
        if (mServerEnv != null) {
            mServerEnv.close();
        }
        if (mServerSocket != null) {
            mServerSocket.close();
        }
    }
 
    private Session<Echo> connect(Serializer clientSerializer) throws Exception {
        Environment env = Environment.create();
        mClientEnvs.add(env);
        env.customSerializers(clientSerializer);
        return env.connect(Echo.class, "main", "localhost", mServerSocket.getLocalPort());
    }
 
    /**
     * Connects the two sides, and checks that the session works, and that Foo objects are
     * discarded.
     */
    private void mismatched(Serializer serverSerializer, Serializer clientSerializer)
        throws Exception
    {
        mServerEnv = Environment.create();
        mServerEnv.uncaughtExceptionHandler((session, e) -> mUncaught.add(e));
        mServerEnv.customSerializers(serverSerializer);
        mServerEnv.export("main", new EchoServer());
        mServerSocket = new ServerSocket(0);
        mServerEnv.acceptAll(mServerSocket);
 
        Session<Echo> session = connect(clientSerializer);
 
        // The objects are discarded, because the serializers aren't compatible.
        assertEquals(null, session.root().echo(new Foo()));
 
        // But everything else must still work.
        assertEquals("still works", session.root().echo("still works"));
        assertEquals(null, session.root().echo(new Foo()));
 
        // And the server must keep serving other clients which are compatible with it.
        Session<Echo> other = connect(serverSerializer);
        Foo copy = (Foo) other.root().echo(new Foo());
        assertEquals(42, copy.a);
        assertEquals("hello", copy.b);
 
        assertEquals("Unexpected exceptions: " + mUncaught, 0, mUncaught.size());
    }
 
    // ----- End to end -----
 
    @Test(timeout = 20_000)
    public void compatible() throws Exception {
        mServerEnv = Environment.create();
        mServerEnv.customSerializers(new Described("v1"));
        mServerEnv.export("main", new EchoServer());
        mServerSocket = new ServerSocket(0);
        mServerEnv.acceptAll(mServerSocket);
 
        Foo copy = (Foo) connect(new Described("v1")).root().echo(new Foo());
        assertEquals(42, copy.a);
        assertEquals("hello", copy.b);
    }
 
    /**
     * The descriptor isn't in the format which is generated by Serializer.simple, but it looks
     * valid because it's missing a ';'. It was treated as an empty field list, and so nothing
     * was written, and the other side then waited for data which never arrived.
     */
    @Test(timeout = 20_000)
    public void serverDescribedClientSimple() throws Exception {
        mismatched(new Described("v1"), Serializer.simple(Foo.class));
    }
 
    @Test(timeout = 20_000)
    public void serverSimpleClientDescribed() throws Exception {
        mismatched(Serializer.simple(Foo.class), new Described("v1"));
    }
 
    /**
     * These were truncated descriptors, which caused a StringIndexOutOfBoundsException.
     */
    @Test(timeout = 20_000)
    public void truncatedDescriptors() throws Exception {
        mismatched(new Described("x;"), Serializer.simple(Foo.class));
    }
 
    @Test(timeout = 20_000)
    public void truncatedObjectDescriptor() throws Exception {
        mismatched(new Described("x;Lfoo"), Serializer.simple(Foo.class));
    }
 
    /**
     * Hand-written serializers have a null descriptor by default, which caused a
     * NullPointerException.
     */
    @Test(timeout = 20_000)
    public void serverPlainClientSimple() throws Exception {
        mismatched(new Plain(), Serializer.simple(Foo.class));
    }
 
    @Test(timeout = 20_000)
    public void serverSimpleClientPlain() throws Exception {
        mismatched(Serializer.simple(Foo.class), new Plain());
    }
 
    // ----- Serializer.adapt -----
 
    /**
     * Descriptors which weren't generated by Serializer.simple cannot be adapted to.
     */
    @Test
    public void adaptRejectsUnusableDescriptors() throws Exception {
        for (Serializer ser : new Serializer[] {
                 Serializer.simple(Foo.class), Serializer.simple(R.class)})
        {
            String name = ser.supportedTypes().iterator().next().getSimpleName();
 
            Object[] descriptors = {
                null,               // the default descriptor of a hand-written serializer
                5,                  // not even a string
                "v1",               // no ';'
                "a",                // no ';'
                "a;",               // no type
                "a;[",              // array without a type
                "a;Lfoo",           // unterminated object type
                "a;Q",              // unknown type
                "a;Ib",             // trailing text
                ";",                // empty name and no type
            };
 
            for (Object descriptor : descriptors) {
                assertNull(name + " " + descriptor, ser.adapt(descriptor));
            }
        }
    }
 
    /**
     * Valid descriptors must still be adapted to. The format of a descriptor is a sequence of
     * field names, each followed by a ';' and then a type descriptor.
     */
    @Test
    public void adaptAcceptsValidDescriptors() throws Exception {
        Serializer ser = Serializer.simple(Foo.class);
 
        // The same descriptor requires no adaptation.
        assertSame(ser, ser.adapt(ser.descriptor()));
 
        // Fewer fields, no fields, different field types, and unknown fields.
        for (String descriptor : new String[] {
                 "a;I", "b;Ljava/lang/String;", "", "a;Jb;Ljava/lang/String;",
                 "a;Ix;[[Ljava/lang/Object;y;[Iz;Z" })
        {
            assertNotNull(descriptor, ser.adapt(descriptor));
        }
    }
 
    /**
     * The descriptor of an enum is a list of constant names, which isn't the same format as
     * the descriptor of other types.
     */
    @Test
    public void adaptEnum() throws Exception {
        Serializer ser = Serializer.simple(E.class);
 
        assertSame(ser, ser.adapt(ser.descriptor()));
 
        // The remote side has a different set of constants.
        Serializer adapted = ser.adapt("A;C;X");
        assertNotNull(adapted);
        assertEquals("A;C", adapted.descriptor());
 
        // No constants in common.
        assertNotNull(ser.adapt("X;Y"));
 
        // The remote side has no constants at all.
        assertNotNull(ser.adapt(""));
    }
}
