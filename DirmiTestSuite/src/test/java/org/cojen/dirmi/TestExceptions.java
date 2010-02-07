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

import java.rmi.RemoteException;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestExceptions extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestExceptions.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteExceptionsServer());
    }

    @Test
    public void nonSerializable() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff();
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException.class.getName()));
            assertTrue(e.getCause() instanceof NonSerializableException);
            assertEquals(null, e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable2() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff2("hello");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException);
            assertEquals("hello", e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable3() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff3("hello", "world");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException);
            assertEquals("hello", e.getCause().getMessage());
            Throwable causeCause = e.getCause().getCause();
            assertTrue(causeCause instanceof RuntimeException);
            assertEquals("world", causeCause.getMessage());
        }
    }

    @Test
    public void nonSerializable4() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff4();
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException2.class.getName()));
            assertTrue(e.getCause() instanceof NonSerializableException2);
            assertEquals(null, e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable5() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff5("hello");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException2.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException2);
            assertEquals("hello", e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable6() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff6("hello", "world");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException2.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException2);
            assertEquals("hello", e.getCause().getMessage());
            Throwable causeCause = e.getCause().getCause();
            assertTrue(causeCause instanceof RuntimeException);
            assertEquals("world", causeCause.getMessage());
        }
    }

    @Test
    public void nonSerializable7() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff7();
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException3.class.getName()));
            assertTrue(e.getCause() instanceof NonSerializableException3);
            assertEquals(null, e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable8() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff8("hello");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException3.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException3);
            assertEquals("hello", e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializable9() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuff9("hello", "world");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException3.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof NonSerializableException3);
            assertEquals("hello", e.getCause().getMessage());
            Throwable causeCause = e.getCause().getCause();
            assertTrue(causeCause instanceof RuntimeException);
            assertEquals("world", causeCause.getMessage());
        }
    }

    @Test
    public void nonSerializablea() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuffa();
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException4.class.getName()));
            assertTrue(e.getCause() instanceof NonSerializableException4);
            assertEquals(null, e.getCause().getMessage());
        }
    }

    @Test
    public void nonSerializableb() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuffb("hello");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException4.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertEquals(null, e.getCause());
        }
    }

    @Test
    public void nonSerializablec() throws Exception {
        RemoteExceptions remote = (RemoteExceptions) sessionStrategy.remoteServer;
        assertFalse(remote instanceof RemoteExceptionsServer);

        try {
            remote.stuffc("hello", "world");
            fail();
        } catch (ReconstructedException e) {
            assertTrue(e.toString().contains(NonSerializableException4.class.getName()));
            assertTrue(e.toString().contains("hello"));
            assertTrue(e.getCause() instanceof RuntimeException);
            assertEquals("world", e.getCause().getMessage());
        }
    }
}
