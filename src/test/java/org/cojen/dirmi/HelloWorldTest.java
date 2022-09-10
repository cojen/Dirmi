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

import java.net.ProtocolFamily;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;

import java.nio.file.Path;

import java.nio.channels.ServerSocketChannel;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class HelloWorldTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(HelloWorldTest.class.getName());
    }

    @Test
    public void inetSocket() throws Exception {
        var serverEnv = Environment.create();
        serverEnv.export("main", new ControlServer());
        var ss = new ServerSocket(0);
        serverEnv.acceptAll(ss);

        var clientEnv = Environment.create();
        var session = clientEnv.connect(Control.class, "main", "localhost", ss.getLocalPort());
        var control = session.root();

        assertEquals("HelloWorld", control.call("Hello"));
        assertEquals("Hello!!! World", control.call("Hello!!! "));

        try {
            System.out.println(control.call(null));
            fail();
        } catch (RuntimeException e) {
            assertEquals("yo", e.getMessage());
        }

        serverEnv.close();
        clientEnv.close();
    }

    @Test
    public void inetSocketChannel() throws Exception {
        var serverEnv = Environment.create();
        serverEnv.export("main", new ControlServer());
        var ss = ServerSocketChannel.open().bind(null);
        serverEnv.acceptAll(ss);

        var clientEnv = Environment.create();
        var session = clientEnv.connect(Control.class, "main", ss.getLocalAddress());
        var control = session.root();

        assertEquals("HelloWorld", control.call("Hello"));
        assertEquals("Hello!!! World", control.call("Hello!!! "));

        try {
            System.out.println(control.call(null));
            fail();
        } catch (RuntimeException e) {
            assertEquals("yo", e.getMessage());
        }

        serverEnv.close();
        clientEnv.close();
    }

    @Test
    public void domainSocketChannel() throws Exception {
        if (Runtime.version().feature() < 16) {
            // Domain socket support isn't available.
            return;
        }

        var family = (ProtocolFamily) StandardProtocolFamily.class.getField("UNIX").get(null);
        var ss = (ServerSocketChannel) ServerSocketChannel.class.getMethod
            ("open", ProtocolFamily.class).invoke(null, family);
        ss.bind(null);
        SocketAddress address = ss.getLocalAddress();

        Path path = (Path) address.getClass().getMethod("getPath").invoke(address);
        path.toFile().deleteOnExit();

        var serverEnv = Environment.create();
        serverEnv.export("main", new ControlServer());
        serverEnv.acceptAll(ss);

        var clientEnv = Environment.create();
        var session = clientEnv.connect(Control.class, "main", address);
        var control = session.root();

        assertEquals("HelloWorld", control.call("Hello"));
        assertEquals("Hello!!! World", control.call("Hello!!! "));

        try {
            System.out.println(control.call(null));
            fail();
        } catch (RuntimeException e) {
            assertEquals("yo", e.getMessage());
        }

        serverEnv.close();
        clientEnv.close();
    }

    public static interface Control extends Remote {
        String call(String in) throws RemoteException;
    }

    private static class ControlServer implements Control {
        @Override
        public String call(String in) {
            if (in == null) {
                throw new RuntimeException("yo");
            }
            return in + "World";
        }
    }
}
