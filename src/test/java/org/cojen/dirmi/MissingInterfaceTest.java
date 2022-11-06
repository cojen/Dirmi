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

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.maker.ClassMaker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MissingInterfaceTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(MissingInterfaceTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        var env = Environment.create();
        env.export("main", new ControlServer());
        env.connector(Connector.local(env));
        var session = env.connect(Control.class, "main", null);
        Control c = session.root();

        // Create a remote object whose interface is unknown to the client.
        Object obj = c.createRemote();

        // Reflection can be used on the remote object.
        assertEquals("hello", obj.getClass().getMethod("a").invoke(obj));

        assertSame(obj, c.echo(obj));

        try {
            c.echo("hello");
            fail();
        } catch (AssertionError e) {
        }
    }

    public static interface Control extends Remote {
        Object createRemote() throws Exception;

        Object echo(Object obj) throws RemoteException;
    }

    private static class ControlServer implements Control {
        private Object mObj;

        @Override
        public Object createRemote() throws Exception {
            // Create a new remote interface and server implementation.

            ClassMaker cm = ClassMaker.begin().public_().interface_().implement(Remote.class);
            cm.addMethod(String.class, "a").public_().abstract_().throws_(RemoteException.class);
            Class iface = cm.finish();

            cm = ClassMaker.begin().public_().implement(iface);
            cm.addConstructor().public_();
            cm.addMethod(String.class, "a").public_().return_("hello");
            Class<?> clazz = cm.finish();

            return mObj = clazz.getConstructor().newInstance();
        }

        @Override
        public Object echo(Object obj) {
            assertSame(mObj, obj);
            return obj;
        }
    }
}
