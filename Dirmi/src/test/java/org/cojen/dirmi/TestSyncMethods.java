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

import java.util.ArrayList;
import java.util.List;

import java.rmi.RemoteException;

import java.sql.SQLException;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestSyncMethods extends AbstractTestLocalBroker {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestSyncMethods.class.getName());
    }

    protected Object createLocalServer() {
        return null;
    }

    protected Object createRemoteServer() {
        return new RemoteFaceServer();
    }

    @Test
    public void basicMessagePassing() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        assertFalse(face instanceof RemoteFaceServer);

        assertEquals(null, face.getMessage());

        face.doIt();
        assertEquals("done", face.getMessage());

        face.receive("hello");
        assertEquals("hello", face.getMessage());

        face.receive(null);
        assertEquals(null, face.getMessage());
    }

    @Test
    public void manyParams() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        assertFalse(face instanceof RemoteFaceServer);

        List<String> params = new ArrayList<String>();
        params.add("a");
        params.add("b");

        List<String> result = face.calculate(56, 100, null, params);

        assertEquals("56", result.get(0));
        assertEquals("100", result.get(1));
        assertEquals(null, result.get(2));
        assertEquals("a", result.get(3));
        assertEquals("b", result.get(4));
    }

    @Test
    public void fail() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        assertFalse(face instanceof RemoteFaceServer);

        face.fail(new int[] {1, 2, 3});

        try {
            face.fail(null);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("no params", e.getMessage());
            verifyTrace(e);
        }
    }

    @Test
    public void exceptionTypes() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        assertFalse(face instanceof RemoteFaceServer);

        String[] results = face.executeQuery("select stuff");
        assertEquals(4, results.length);
        assertEquals("select stuff", results[0]);
        assertEquals("row 1", results[1]);
        assertEquals("row 2", results[2]);
        assertEquals("row 3", results[3]);

        try {
            face.executeQuery(null);
            fail();
        } catch (SQLException e) {
            assertEquals("no query", e.getMessage());
            verifyTrace(e);
        }

        try {
            face.executeQuery2(null);
            fail();
        } catch (SQLException e) {
            assertEquals("no query", e.getMessage());
            verifyTrace(e);
        }

        results = face.executeQuery3("select more stuff");
        assertEquals(4, results.length);
        assertEquals("select more stuff", results[0]);
        assertEquals("row 1", results[1]);
        assertEquals("row 2", results[2]);
        assertEquals("row 3", results[3]);

        try {
            face.executeQuery4(null);
            fail();
        } catch (RuntimeException e) {
            assertEquals("no query", e.getMessage());
            verifyTrace(e);
        }
    }

    @Test
    public void remoteFailure() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        remoteSession.close();

        try {
            face.executeQuery("select stuff");
            fail();
        } catch (RemoteException e) {
            verifyMessage(e);
        }
    }

    @Test
    public void remoteFailure2() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        remoteBroker.close();

        try {
            face.executeQuery("select stuff");
            fail();
        } catch (RemoteException e) {
            verifyMessage(e);
        }
    }

    @Test
    public void remoteFailure3() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        remoteSession.close();

        try {
            face.executeQuery2("select stuff");
            fail();
        } catch (SQLException e) {
            assertTrue(e.getCause() instanceof RemoteException);
            verifyMessage(e);
        }
    }

    @Test
    public void remoteFailure4() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        remoteSession.close();

        try {
            face.executeQuery3("select stuff");
            fail();
        } catch (SQLException e) {
            assertTrue(e.getCause() instanceof RemoteException);
            verifyMessage(e);
        }
    }

    @Test
    public void remoteFailure5() throws Exception {
        RemoteFace face = (RemoteFace) remoteServer;
        remoteSession.close();

        try {
            face.executeQuery4("select stuff");
            fail();
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof RemoteException);
            verifyMessage(e);
        }
    }

    private void verifyTrace(Exception e) {
        boolean found = false;
        for (StackTraceElement elem : e.getStackTrace()) {
            if ("--- remote method invocation ---".equals(elem.getClassName())) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    private void verifyMessage(Exception e) {
        String message = e.getMessage();
        assertTrue(message.contains("Session closed") ||
                   message.contains("Broker is closed") ||
                   message.contains("Closed"));
    }
}
