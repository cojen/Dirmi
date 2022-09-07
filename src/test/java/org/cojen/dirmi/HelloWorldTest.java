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

import java.net.ServerSocket;

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
    public void basic() throws Exception {
        var server = Environment.create();
        server.export("main", new ControlServer());
        var ss = new ServerSocket(0);
        server.acceptAll(ss);

        var client = Environment.create();
        var session = client.connect(Control.class, "main", "localhost", ss.getLocalPort());
        var control = session.root();

        assertEquals("HelloWorld", control.call("Hello"));
        assertEquals("Hello!!! World", control.call("Hello!!! "));

        try {
            System.out.println(control.call(null));
            fail();
        } catch (RuntimeException e) {
            assertEquals("yo", e.getMessage());
        }

        server.close();
        client.close();
        session.close();
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
