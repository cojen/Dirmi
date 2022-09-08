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

package org.cojen.dirmi.core;

import java.io.EOFException;
import java.io.IOException;

import java.net.SocketException;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.ClosedException;
import org.cojen.dirmi.RemoteException;

import static org.cojen.dirmi.core.CoreUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class CoreUtilsTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CoreUtilsTest.class.getName());
    }

    @Test
    public void remoteEx() throws Exception {
        Throwable e = remoteException(RemoteException.class, null);
        assertTrue(e instanceof RemoteException);
        assertNull(e.getCause());

        // MyEx1 doesn't have an appropriate constructor.
        e = remoteException(MyEx1.class, null);
        assertTrue(e instanceof RemoteException);
        assertNull(e.getCause());

        // Exception is a super class of RemoteException.
        e = remoteException(Exception.class, null);
        assertTrue(e instanceof RemoteException);
        assertNull(e.getCause());

        e = remoteException(MyEx2.class, null);
        assertTrue(e instanceof MyEx2);
        assertTrue(e.getCause() instanceof RemoteException);

        e = remoteException(java.rmi.RemoteException.class, null);
        assertTrue(e instanceof java.rmi.RemoteException);
        assertTrue(e.getCause() instanceof RemoteException);

        e = remoteException(RemoteException.class, new EOFException());
        assertTrue(e instanceof ClosedException);
        assertNull(e.getCause());
        assertEquals("Pipe input is closed", e.getMessage());

        Exception cause = new RemoteException();
        e = remoteException(IOException.class, cause);
        assertEquals(cause, e);

        cause = new SocketException();
        e = remoteException(IOException.class, cause);
        assertEquals(cause, e);

        e = remoteException(RemoteException.class, new SocketException());
        assertTrue(e instanceof RemoteException);
        assertTrue(e.getCause() instanceof SocketException);

        e = remoteException(MyEx2.class, new SocketException());
        assertTrue(e instanceof MyEx2);
        assertTrue(e.getCause() instanceof SocketException);

        e = remoteException(MyEx3.class, new SocketException());
        assertTrue(e instanceof MyEx3);
        assertTrue(e.getCause() instanceof SocketException);
        assertEquals(SocketException.class.getName(), e.getMessage());

        e = remoteException(MyEx3.class, new SocketException("fail"));
        assertTrue(e instanceof MyEx3);
        assertTrue(e.getCause() instanceof SocketException);
        assertEquals(SocketException.class.getName() + ": fail", e.getMessage());
    }

    public static class MyEx1 extends Exception {
        private MyEx1() {
        }
    }

    public static class MyEx2 extends Exception {
        public MyEx2(Throwable cause) {
            super(cause);
        }
    }

    public static class MyEx3 extends Exception {
        public MyEx3(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
