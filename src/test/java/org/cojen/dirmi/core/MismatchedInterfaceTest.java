/*
 *  Copyright 2009-2022 Cojen.org
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.net.ServerSocket;

import java.util.Iterator;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MismatchedInterfaceTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(MismatchedInterfaceTest.class.getName());
    }

    private Environment mEnv;

    @Before
    public void setup() {
        mEnv = Environment.create();
    }

    @After
    public void tearDown() throws Exception {
        mEnv.close();
    }

    @Test
    public void interfaceMismatch() throws Exception {
        // Define two remote interfaces with same name, but with different methods. This
        // requires that they are defined with separate ClassLoaders.

        Class<?> iface0, server0;
        {
            ClassMaker cm = ClassMaker.beginExplicit("org.cojen.dirmi.MIT", new Loader(), null)
                .public_().interface_().implement(Remote.class);
            cm.addMethod(String.class, "a").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(String.class, "b").public_().abstract_().throws_(RemoteException.class);
            iface0 = cm.finish();

            cm = ClassMaker.begin(null, iface0.getClassLoader()).implement(iface0).public_();
            cm.addConstructor().public_();
            cm.addMethod(String.class, "a").public_().return_("a");
            cm.addMethod(String.class, "b").public_().return_("b");
            server0 = cm.finish();
        }

        Class<?> iface1, server1;
        {
            ClassMaker cm = ClassMaker.beginExplicit("org.cojen.dirmi.MIT", new Loader(), null)
                .public_().interface_().implement(Remote.class);
            cm.addMethod(String.class, "b").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(String.class, "c").public_().abstract_().throws_(RemoteException.class);
            iface1 = cm.finish();

            cm = ClassMaker.begin(null, iface1.getClassLoader()).implement(iface1).public_();
            cm.addConstructor().public_();
            cm.addMethod(String.class, "b").public_().return_("b");
            cm.addMethod(String.class, "c").public_().return_("c");
            server1 = cm.finish();
        }

        Object obj0 = server0.getConstructor().newInstance();
        Object obj1 = server1.getConstructor().newInstance();

        mEnv.export("obj0", obj0);
        mEnv.export("obj1", obj1);

        var ss = new ServerSocket(0);
        mEnv.acceptAll(ss);

        // Note that the requested interface number is different than the object number.
        Session session0 = mEnv.connect(iface0, "obj1", "localhost", ss.getLocalPort());
        Session session1 = mEnv.connect(iface1, "obj0", "localhost", ss.getLocalPort());

        Object remote0 = session0.root();
        Object remote1 = session1.root();

        assertTrue(iface0.isInstance(remote0));

        Method a0 = iface0.getMethod("a");
        try {
            a0.invoke(remote0);
            fail();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof NoSuchMethodError);
            assertTrue(cause.getMessage().contains("Unimplemented"));
        }

        Method b0 = iface0.getMethod("b");
        assertEquals("b", b0.invoke(remote0));

        // Not part of the interface, but available via reflection.
        Method c0 = remote0.getClass().getMethod("c");
        assertEquals("c", c0.invoke(remote0));

        // Try again with other endpoint.
        assertTrue(iface1.isInstance(remote1));

        // Not part of the interface, but available via reflection.
        Method a1 = remote1.getClass().getMethod("a");
        assertEquals("a", a1.invoke(remote1));

        Method b1 = remote1.getClass().getMethod("b");
        assertEquals("b", b1.invoke(remote1));

        Method ib1 = iface1.getMethod("b");
        assertEquals("b", ib1.invoke(remote1));

        Method c1 = iface1.getMethod("c");
        try {
            c1.invoke(remote1);
            fail();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof NoSuchMethodError);
            assertTrue(cause.getMessage().contains("Unimplemented"));
        }
    }

    @Test
    public void missingInterface() throws Exception {
        // Client doesn't have the interface provided by the server, so it just uses the plain
        // Remote interface.

        Class<?> iface, server;
        {
            ClassMaker cm = ClassMaker.beginExplicit("org.cojen.dirmi.MIT2", new Loader(), null)
                .public_().interface_().implement(Remote.class);
            cm.addMethod(String.class, "foo").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(cm, "me").public_().abstract_().throws_(RemoteException.class);
            cm.addMethod(Object.class, "me2").public_().abstract_().throws_(RemoteException.class);
            iface = cm.finish();

            cm = ClassMaker.begin(null, iface.getClassLoader()).implement(iface).public_();
            cm.addConstructor().public_();
            cm.addMethod(String.class, "foo").public_().return_("foo");
            MethodMaker mm = cm.addMethod(iface, "me").public_();
            mm.return_(mm.this_());
            mm = cm.addMethod(Object.class, "me2").public_();
            mm.return_(mm.this_());
            server = cm.finish();
        }

        Object obj = server.getConstructor().newInstance();

        mEnv.export("obj", obj);

        var ss = new ServerSocket(0);
        mEnv.acceptAll(ss);

        Session session = mEnv.connect(Remote.class, "obj", "localhost", ss.getLocalPort());

        Object remote = session.root();

        assertTrue(remote instanceof Remote);
        assertFalse(iface.isInstance(remote));

        Method foo = remote.getClass().getMethod("foo");
        assertEquals("foo", foo.invoke(remote));

        try {
            remote.getClass().getMethod("me");
            fail();
        } catch (NoSuchMethodException e) {
        }

        Method me2 = remote.getClass().getMethod("me2");
        assertEquals(remote, me2.invoke(remote));
    }

    @Test
    public void commonParent() throws Exception {
        // Client doesn't have the interface provided by the server, but it does have the
        // parent interface.

        Class<?> iface, server;
        {
            ClassMaker cm = ClassMaker.beginExplicit("org.cojen.dirmi.MIT3", new Loader(), null)
                .public_().interface_().implement(Parent.class);
            MethodMaker mm = cm.addMethod(Parent.class, "option", int.class)
                .public_().abstract_().throws_(RemoteException.class);
            mm.addAnnotation(Batched.class, true);
            mm.addAnnotation(RemoteFailure.class, true).put("declared", false);
            mm = cm.addMethod(null, "option2", int.class).public_().abstract_();
            mm.addAnnotation(Batched.class, true);
            mm.addAnnotation(RemoteFailure.class, true).put("exception", RuntimeException.class);
            cm.addMethod(String.class, "extraName", int.class)
                .public_().abstract_().throws_(RemoteException.class);
            iface = cm.finish();

            cm = ClassMaker.begin(null, iface.getClassLoader()).implement(iface).public_();
            cm.addConstructor().public_();
            cm.addMethod(String.class, "name").public_().return_("bob");
            mm = cm.addMethod(Parent.class, "option", int.class).public_();
            mm.return_(mm.this_());
            cm.addMethod(null, "option2").public_();
            cm.addMethod(String.class, "extraName", int.class).public_().return_("extra");
            server = cm.finish();
        }

        Object obj = server.getConstructor().newInstance();

        mEnv.export("obj", obj);

        var ss = new ServerSocket(0);
        mEnv.acceptAll(ss);

        Session session = mEnv.connect(Parent.class, "obj", "localhost", ss.getLocalPort());

        Object remote = session.root();

        assertTrue(remote instanceof Remote);
        assertTrue(remote instanceof Parent);
        assertFalse(iface.isInstance(remote));

        Method extra = remote.getClass().getMethod("extraName", int.class);
        assertEquals("extra", extra.invoke(remote, 123));

        assertEquals("bob", ((Parent) remote).name());

        RemoteInfo info1 = RemoteInfo.examine(iface);
        RemoteInfo info2 = RemoteInfo.examineStub(remote);

        Iterator<RemoteMethod> it1 = info1.remoteMethods().iterator();
        Iterator<RemoteMethod> it2 = info2.remoteMethods().iterator();

        while (it1.hasNext()) {
            RemoteMethod rm1 = it1.next();
            RemoteMethod rm2 = it2.next();
            assertEquals(0, rm1.compareTo(rm2));
        }

        assertFalse(it2.hasNext());
    }

    @Test
    public void commonParentMismatch() throws Exception {
        // Client doesn't have the interface provided by the server, but it does have a
        // mismatched parent interface.

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

        Class<?> iface, server;
        {
            ClassMaker cm = ClassMaker.beginExplicit(parentName, new CL(), null)
                .public_().interface_().implement(Remote.class);
            cm.addMethod(int.class, "name").public_().abstract_().throws_(RemoteException.class);
            Class<?> parent = cm.finish();

            cm = ClassMaker.beginExplicit("org.cojen.dirmi.MIT4", parent.getClassLoader(), null)
                .public_().interface_().implement(parent);
            cm.addMethod(String.class, "extraName")
                .public_().abstract_().throws_(RemoteException.class);
            iface = cm.finish();

            cm = ClassMaker.begin(null, iface.getClassLoader()).implement(iface).public_();
            cm.addConstructor().public_();
            cm.addMethod(String.class, "name").public_().return_("bob");
            cm.addMethod(String.class, "extraName").public_().return_("extra");
            server = cm.finish();
        }

        Object obj = server.getConstructor().newInstance();

        mEnv.export("obj", obj);

        var ss = new ServerSocket(0);
        mEnv.acceptAll(ss);

        Session session = mEnv.connect(Parent.class, "obj", "localhost", ss.getLocalPort());

        Object remote = session.root();

        assertTrue(remote instanceof Remote);
        assertTrue(remote instanceof Parent);
        assertFalse(iface.isInstance(remote));

        Method extra = remote.getClass().getMethod("extraName");
        assertEquals("extra", extra.invoke(remote));

        try {
            // Return type differs.
            assertEquals("bob", ((Parent) remote).name());
            fail();
        } catch (NoSuchMethodError e) {
            assertTrue(e.getMessage().contains("Unimplemented"));
        }
    }

    private static class Loader extends ClassLoader {
        Loader() {
            super(MismatchedInterfaceTest.class.getClassLoader());
        }
    }

    public static interface Parent extends Remote {
        String name() throws Exception;
    }
}
