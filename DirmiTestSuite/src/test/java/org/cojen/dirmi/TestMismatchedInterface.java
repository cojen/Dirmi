/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.rmi.Remote;
import java.rmi.RemoteException;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.classfile.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestMismatchedInterface {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestMismatchedInterface.class.getName());
    }

    private Environment mEnv;

    @Before
    public void setup() {
        mEnv = new Environment();
    }

    @After
    public void tearDown() throws Exception {
        mEnv.close();
    }

    @Test
    public void interfaceMismatch() throws Exception {
        // Define two remote interfaces with same name, but with different
        // methods. This requires that they are defined with separate
        // ClassLoaders.

        Class iface0, server0;
        {
            RuntimeClassFile cf = new RuntimeClassFile("IFace", null, new Loader(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(Remote.class);
            TypeDesc exType = TypeDesc.forClass(RemoteException.class);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "a", TypeDesc.STRING, null)
                .addException(exType);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "b", TypeDesc.STRING, null)
                .addException(exType);
            iface0 = cf.defineClass();

            cf = new RuntimeClassFile(null, null, iface0.getClassLoader());
            cf.addInterface(iface0);
            cf.addDefaultConstructor();
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "a", TypeDesc.STRING, null));
            b.loadConstant("a");
            b.returnValue(TypeDesc.STRING);
            b = new CodeBuilder(cf.addMethod(Modifiers.PUBLIC, "b", TypeDesc.STRING, null));
            b.loadConstant("b");
            b.returnValue(TypeDesc.STRING);
            server0 = cf.defineClass();
        }

        Class iface1, server1;
        {
            RuntimeClassFile cf = new RuntimeClassFile("IFace", null, new Loader(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(Remote.class);
            TypeDesc exType = TypeDesc.forClass(RemoteException.class);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "b", TypeDesc.STRING, null)
                .addException(exType);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "c", TypeDesc.STRING, null)
                .addException(exType);
            iface1 = cf.defineClass();

            cf = new RuntimeClassFile(null, null, iface1.getClassLoader());
            cf.addInterface(iface1);
            cf.addDefaultConstructor();
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "b", TypeDesc.STRING, null));
            b.loadConstant("b");
            b.returnValue(TypeDesc.STRING);
            b = new CodeBuilder(cf.addMethod(Modifiers.PUBLIC, "c", TypeDesc.STRING, null));
            b.loadConstant("c");
            b.returnValue(TypeDesc.STRING);
            server1 = cf.defineClass();
        }

        Object obj1 = server0.newInstance();
        Object obj2 = server1.newInstance();

        Session[] pair = mEnv.newSessionPair();
        pair[0].setClassLoader(iface0.getClassLoader());
        pair[1].setClassLoader(iface1.getClassLoader());

        pair[0].send(obj1);
        pair[1].send(obj2);

        Object remote0 = pair[0].receive();
        Object remote1 = pair[1].receive();

        assertTrue(iface0.isInstance(remote0));

        Method a0 = remote0.getClass().getMethod("a", (Class[]) null);
        try {
            a0.invoke(remote0, (Object[]) null);
            fail();
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof UnimplementedMethodException);
        }

        Method b0 = remote0.getClass().getMethod("b", (Class[]) null);
        assertEquals("b", b0.invoke(remote0, (Object[]) null));

        Method ib0 = iface0.getMethod("b", (Class[]) null);
        assertEquals("b", ib0.invoke(remote0, (Object[]) null));

        // Not part of interface, but available via reflection.
        Method c0 = remote0.getClass().getMethod("c", (Class[]) null);
        assertEquals("c", c0.invoke(remote0, (Object[]) null));

        // Try again with other endpoint.
        assertTrue(iface1.isInstance(remote1));

        // Not part of interface, but available via reflection.
        Method a1 = remote1.getClass().getMethod("a", (Class[]) null);
        assertEquals("a", a1.invoke(remote1, (Object[]) null));

        Method b1 = remote1.getClass().getMethod("b", (Class[]) null);
        assertEquals("b", b1.invoke(remote1, (Object[]) null));

        Method ib1 = iface1.getMethod("b", (Class[]) null);
        assertEquals("b", ib1.invoke(remote1, (Object[]) null));

        Method c1 = remote1.getClass().getMethod("c", (Class[]) null);
        try {
            c1.invoke(remote1, (Object[]) null);
            fail();
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof UnimplementedMethodException);
        }
    }

    @Test
    public void missingInterface() throws Exception {
        // Client doesn't have the interface provided by server, so it just
        // uses plain Remote interface.

        Class iface, server;
        {
            RuntimeClassFile cf = new RuntimeClassFile("MyOwn", null, new Loader(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(Remote.class);
            TypeDesc exType = TypeDesc.forClass(RemoteException.class);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "foo", TypeDesc.STRING, null)
                .addException(exType);
            iface = cf.defineClass();

            cf = new RuntimeClassFile(null, null, iface.getClassLoader());
            cf.addInterface(iface);
            cf.addDefaultConstructor();
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "foo", TypeDesc.STRING, null));
            b.loadConstant("foo");
            b.returnValue(TypeDesc.STRING);
            server = cf.defineClass();
        }

        Object obj = server.newInstance();

        Session[] pair = mEnv.newSessionPair();

        pair[0].send(obj);

        Object remote = pair[1].receive();

        assertTrue(remote instanceof Remote);
        assertFalse(iface.isInstance(remote));

        Method foo = remote.getClass().getMethod("foo", (Class[]) null);
        assertEquals("foo", foo.invoke(remote, (Object[]) null));
    }

    @Test
    public void commonParent() throws Exception {
        // Client doesn't have the interface provided by server, but it does
        // have the parent interface.

        Class iface, server;
        {
            RuntimeClassFile cf = new RuntimeClassFile("Extended", null, new Loader(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(Parent.class);
            TypeDesc exType = TypeDesc.forClass(RemoteException.class);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "extraName", TypeDesc.STRING, null)
                .addException(exType);
            iface = cf.defineClass();

            cf = new RuntimeClassFile(null, null, iface.getClassLoader());
            cf.addInterface(iface);
            cf.addDefaultConstructor();
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "name", TypeDesc.STRING, null));
            b.loadConstant("bob");
            b.returnValue(TypeDesc.STRING);
            b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "extraName", TypeDesc.STRING, null));
            b.loadConstant("extra");
            b.returnValue(TypeDesc.STRING);
            server = cf.defineClass();
        }

        Object obj = server.newInstance();

        Session[] pair = mEnv.newSessionPair();

        pair[0].send(obj);

        Object remote = pair[1].receive();

        assertTrue(remote instanceof Remote);
        assertTrue(remote instanceof Parent);
        assertFalse(iface.isInstance(remote));

        Method extra = remote.getClass().getMethod("extraName", (Class[]) null);
        assertEquals("extra", extra.invoke(remote, (Object[]) null));

        assertEquals("bob", ((Parent) remote).name());
    }

    @Test
    public void commonParentMismatch() throws Exception {
        // Client doesn't have the interface provided by server, but it does
        // have a mismatched parent interface.

        final String parentName = Parent.class.getName();

        class CL extends Loader {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException
            {
                if (name.equals(parentName)) {
                    throw new ClassNotFoundException();
                }
                return super.loadClass(name, resolve);
            }
        }

        Class iface, server;
        {
            RuntimeClassFile cf = new RuntimeClassFile(parentName, null, new CL(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(Remote.class);
            TypeDesc exType = TypeDesc.forClass(RemoteException.class);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "name", TypeDesc.INT, null)
                .addException(exType);
            Class parent = cf.defineClass();

            cf = new RuntimeClassFile("Extended", null, parent.getClassLoader(), null, true);
            cf.setModifiers(Modifiers.PUBLIC.toInterface(true));
            cf.addInterface(parent);
            cf.addMethod(Modifiers.PUBLIC_ABSTRACT, "extraName", TypeDesc.STRING, null)
                .addException(exType);
            iface = cf.defineClass();

            cf = new RuntimeClassFile(null, null, iface.getClassLoader());
            cf.addInterface(iface);
            cf.addDefaultConstructor();
            CodeBuilder b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "name", TypeDesc.STRING, null));
            b.loadConstant("bob");
            b.returnValue(TypeDesc.STRING);
            b = new CodeBuilder
                (cf.addMethod(Modifiers.PUBLIC, "extraName", TypeDesc.STRING, null));
            b.loadConstant("extra");
            b.returnValue(TypeDesc.STRING);
            server = cf.defineClass();
        }

        Object obj = server.newInstance();

        Session[] pair = mEnv.newSessionPair();

        pair[0].send(obj);

        Object remote = pair[1].receive();

        assertTrue(remote instanceof Remote);
        assertTrue(remote instanceof Parent);
        assertFalse(iface.isInstance(remote));

        Method extra = remote.getClass().getMethod("extraName", (Class[]) null);
        assertEquals("extra", extra.invoke(remote, (Object[]) null));

        try {
            // Return type differs.
            assertEquals("bob", ((Parent) remote).name());
            fail();
        } catch (UnimplementedMethodException e) {
        }
    }

    private static class Loader extends ClassLoader {
        Loader() {
            super(TestMismatchedInterface.class.getClassLoader());
        }
    }

    public static interface Parent extends Remote {
        String name() throws Exception;
    }
}
