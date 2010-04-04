/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestAsyncTimeouts extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestAsyncTimeouts.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteAsyncTimeoutsServer());
    }

    // Note: Because the SlowSerializable class simulates transport via a local
    // sleep call, closing the channel doesn't interrupt it. Timeout exceptions
    // report the correct values, but only after the sleep elapses.

    @Test
    public void slow1() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        remote.slow(new SlowSerializable(1));

        try {
            remote.slow(new SlowSerializable(2000));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1000 milliseconds", e.getMessage());
        }

        remote.slow(new SlowSerializable(1));
    }

    @Test
    public void slow2() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals("foo", remote.slow(new SlowSerializable(1), "foo").get());

        try {
            remote.slow(new SlowSerializable(3000), "foo");
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }
    }

    @Test
    public void slow3() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Byte((byte) 1), remote.slow(new SlowSerializable(1), (byte) 1).get());

        try {
            remote.slow(new SlowSerializable(3000), (byte) 1);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }
    }

    @Test
    public void slow4() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Byte((byte) 1),
                     remote.slow(new SlowSerializable(1), new Byte((byte) 1)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Byte((byte) 2));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }

        assertEquals(new Byte((byte) -1),
                     remote.slow(new SlowSerializable(100), (Byte) null).get());
    }

    @Test
    public void slow5() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Short((short) 30000),
                     remote.slow(new SlowSerializable(1), (short) 30000).get());

        try {
            remote.slow(new SlowSerializable(3000), (short) 1);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }
    }

    @Test
    public void slow6() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Short((short) -100),
                     remote.slow(new SlowSerializable(1), new Short((short) -100)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Short((short) 1));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }

        assertEquals(new Short((short) 2),
                     remote.slow(new SlowSerializable(100), (Short) null).get());

        try {
            remote.slow(new SlowSerializable(3000), (Short) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 seconds", e.getMessage());
        }
    }

    @Test
    public void slow7() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        try {
            remote.slow(new SlowSerializable(1), 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0 milliseconds", e.getMessage());
        }

        assertEquals(new Integer(2000000000),
                     remote.slow(new SlowSerializable(1), 2000000000).get());

        try {
            remote.slow(new SlowSerializable(3000), 100);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 100 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow8() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Integer(-100),
                     remote.slow(new SlowSerializable(1), new Integer(-100)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Integer(1));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 millisecond", e.getMessage());
        }

        assertEquals(new Integer(1500),
                     remote.slow(new SlowSerializable(100), (Integer) null).get());

        try {
            remote.slow(new SlowSerializable(3000), (Integer) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1500 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow9() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        try {
            remote.slow(new SlowSerializable(1), (long) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0 milliseconds", e.getMessage());
        }

        assertEquals(new Long(Long.MAX_VALUE),
                     remote.slow(new SlowSerializable(1), Long.MAX_VALUE).get());

        try {
            remote.slow(new SlowSerializable(3000), (long) 100);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 100 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow10() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Long(-100), remote.slow(new SlowSerializable(1), new Long(-100)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Long(2));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 2 milliseconds", e.getMessage());
        }

        assertEquals(new Long(-1), remote.slow(new SlowSerializable(100), (Long) null).get());
    }

    @Test
    public void slow11() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        try {
            remote.slow(new SlowSerializable(1), (double) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0.0 milliseconds", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1000), (double) 1.0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1000), (double) 1.25);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.25 milliseconds", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1), Double.NaN);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after NaN milliseconds", e.getMessage());
        }

        assertEquals(Double.POSITIVE_INFINITY,
                     remote.slow(new SlowSerializable(1), Double.POSITIVE_INFINITY).get(), 0);
    }

    @Test
    public void slow12() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Double(-100),
                     remote.slow(new SlowSerializable(1), new Double(-100)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Double(1.0));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Double(1500),
                     remote.slow(new SlowSerializable(100), (Double) null).get());

        try {
            remote.slow(new SlowSerializable(3000), (Double) null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1500.0 milliseconds", e.getMessage());
        }
    }

    @Test
    public void slow13() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        try {
            remote.slow(new SlowSerializable(1), (float) 0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0.0 milliseconds", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1000), (float) 1.0);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1000), (float) 1.25);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.25 milliseconds", e.getMessage());
        }

        try {
            remote.slow(new SlowSerializable(1), Float.NaN);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after NaN milliseconds", e.getMessage());
        }

        assertEquals(Float.POSITIVE_INFINITY,
                     remote.slow(new SlowSerializable(1), Float.POSITIVE_INFINITY).get(), 0);
    }

    @Test
    public void slow14() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Float(-100), remote.slow(new SlowSerializable(1), new Float(-100)).get());

        try {
            remote.slow(new SlowSerializable(3000), new Float(1.0));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Float(-1), remote.slow(new SlowSerializable(100), (Float) null).get());
    }

    @Test
    public void slow15() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        assertEquals(new Float(-100), remote.slow(new Float(-100), new SlowSerializable(1)).get());

        try {
            remote.slow(new Float(1.0), new SlowSerializable(3000));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1.0 millisecond", e.getMessage());
        }

        assertEquals(new Float(-1), remote.slow((Float) null, new SlowSerializable(100)).get());
    }

    @Test
    public void unitParam() throws Exception {
        RemoteAsyncTimeouts remote = (RemoteAsyncTimeouts) sessionStrategy.remoteServer;

        try {
            remote.slow(new SlowSerializable(6000), 1, TimeUnit.SECONDS);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 1 second", e.getMessage());
        }

        TimeUnit unit = remote.slow(new SlowSerializable(1), 1000, null).get();
        assertEquals(TimeUnit.MILLISECONDS, unit);

        try {
            remote.slow(new SlowSerializable(6000), 60, null);
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 60 milliseconds", e.getMessage());
        }

        try {
            remote.slow(0.03125, TimeUnit.MINUTES, new SlowSerializable(6000));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 0.03125 minutes", e.getMessage());
        }

        unit = remote.slow(1000.0, null, new SlowSerializable(1)).get();
        assertEquals(TimeUnit.MILLISECONDS, unit);

        try {
            remote.slow(60.0, null, new SlowSerializable(6000));
            fail();
        } catch (RemoteTimeoutException e) {
            assertEquals("Timed out after 60.0 milliseconds", e.getMessage());
        }

        unit = remote.slow(new SlowSerializable(1), null, 1).get();
        assertEquals(TimeUnit.MINUTES, unit);
    }
}
