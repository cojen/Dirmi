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
public class TestTimeouts extends AbstractTestLocalBroker {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestTimeouts.class.getName());
    }

    protected Object createLocalServer() {
        return null;
    }

    protected Object createRemoteServer() {
        return new RemoteTimeoutsServer();
    }

    @Test
    public void slow1() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        remote.slow(1);

        try {
            remote.slow(2000);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1000 milliseconds", e.getMessage());
        }

        remote.slow(1);
    }

    @Test
    public void slow2() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals("foo", remote.slow(1, "foo"));

        try {
            remote.slow(3000, "foo");
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }
    }

    @Test
    public void slow3() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(1, remote.slow(1, (byte) 1));

        try {
            remote.slow(3000, (byte) 1);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }
    }

    @Test
    public void slow4() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Byte((byte) 1), remote.slow(1, new Byte((byte) 1)));

        try {
            remote.slow(3000, new Byte((byte) 2));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }

        assertEquals(new Byte((byte) -1), remote.slow(100, (Byte) null));
    }

    @Test
    public void slow5() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(30000, remote.slow(1, (short) 30000));

        try {
            remote.slow(3000, (short) 1);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }
    }

    @Test
    public void slow6() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Short((short) -100), remote.slow(1, new Short((short) -100)));

        try {
            remote.slow(3000, new Short((short) 1));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }

        assertEquals(new Short((short) 2), remote.slow(100, (Short) null));

        try {
            remote.slow(3000, (Short) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }
    }

    @Test
    public void slow7() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        try {
            remote.slow(1, 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0 milliseconds", e.getMessage());
        }

        assertEquals(2000000000, remote.slow(1, 2000000000));

        try {
            remote.slow(3000, 100);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 100 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow8() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Integer(-100), remote.slow(1, new Integer(-100)));

        try {
            remote.slow(3000, new Integer(1));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 millisecond", e.getMessage());
        }

        assertEquals(new Integer(1500), remote.slow(100, (Integer) null));

        try {
            remote.slow(3000, (Integer) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1500 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow9() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        try {
            remote.slow(1, (long) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0 milliseconds", e.getMessage());
        }

        assertEquals(Long.MAX_VALUE, remote.slow(1, Long.MAX_VALUE));

        try {
            remote.slow(3000, (long) 100);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 100 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow10() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Long(-100), remote.slow(1, new Long(-100)));

        try {
            remote.slow(3000, new Long(2));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 milliseconds", e.getMessage());
        }

        assertEquals(new Long(-1), remote.slow(100, (Long) null));
    }

    @Test
    public void slow11() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        try {
            remote.slow(1, (double) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0.0 milliseconds", e.getMessage());
        }

        try {
            remote.slow(1000, (double) 1.0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        try {
            remote.slow(1000, (double) 1.25);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.25 milliseconds", e.getMessage());
        }

        try {
            remote.slow(1, Double.NaN);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after NaN milliseconds", e.getMessage());
        }

        assertEquals(Double.POSITIVE_INFINITY, remote.slow(1, Double.POSITIVE_INFINITY), 0);
    }

    @Test
    public void slow12() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Double(-100), remote.slow(1, new Double(-100)));

        try {
            remote.slow(3000, new Double(1.0));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Double(1500), remote.slow(100, (Double) null));

        try {
            remote.slow(3000, (Double) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1500.0 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow13() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        try {
            remote.slow(1, (float) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0.0 milliseconds", e.getMessage());
        }

        try {
            remote.slow(1000, (float) 1.0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        try {
            remote.slow(1000, (float) 1.25);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.25 milliseconds", e.getMessage());
        }

        try {
            remote.slow(1, Float.NaN);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after NaN milliseconds", e.getMessage());
        }

        assertEquals(Float.POSITIVE_INFINITY, remote.slow(1, Float.POSITIVE_INFINITY), 0);
    }

    @Test
    public void slow14() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Float(-100), remote.slow(1, new Float(-100)));

        try {
            remote.slow(3000, new Float(1.0));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Float(-1), remote.slow(100, (Float) null));
    }

    @Test
    public void slow15() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) localSession.getRemoteServer();

        assertEquals(new Float(-100), remote.slow(new Float(-100), 1));

        try {
            remote.slow(new Float(1.0), 3000);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Float(-1), remote.slow((Float) null, 100));
    }
}
