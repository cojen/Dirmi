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
public class EmptyRemoteTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(EmptyRemoteTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        var env = Environment.create();
        env.export("main", new R1Server());
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();
        assertEquals(session, Session.access(r1));

        env.close();
    }

    public static interface R1 extends Remote {
    }

    private static class R1Server implements R1 {
    }
}
