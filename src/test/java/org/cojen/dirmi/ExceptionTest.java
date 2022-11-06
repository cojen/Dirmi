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

import java.lang.reflect.UndeclaredThrowableException;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ExceptionTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ExceptionTest.class.getName());
    }

    @Test
    public void reconstruct() throws Exception {
        var env = Environment.create();
        env.export("main", new ControlServer());
        env.connector(Connector.local(env));
        var session = env.connect(Control.class, "main", null);
        Control c = session.root();

        try {
            c.broken();
            fail();
        } catch (Exception e) {
            // Caught a generic exception.
            assertFalse(e instanceof BrokenEx);
            assertEquals(BrokenEx.class.getName() + ": " + 123, e.getMessage());
        }

        try {
            c.broken2();
            fail();
        } catch (UndeclaredThrowableException e) {
            String expect = BrokenEx.class.getName() + ": " + 456;
            assertEquals(expect, e.getMessage());
            Throwable cause = e.getCause();
            assertFalse(cause instanceof BrokenEx);
            assertEquals(expect, cause.getMessage());
        }
    }
 
    public static class BrokenEx extends Exception {
        // Cannot be reconstructed properly with an atypical constructor pattern.
        BrokenEx(int x) {
            super("" + x);
        }
    }

    public static interface Control extends Remote {
        void broken() throws Exception;

        void broken2() throws RemoteException, BrokenEx;
    }

    private static class ControlServer implements Control {
        @Override
        public void broken() throws Exception {
            throw new BrokenEx(123);
        }

        @Override
        public void broken2() throws BrokenEx {
            throw new BrokenEx(456);
        }
    }
 }
