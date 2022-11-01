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
public class ParamTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ParamTest.class.getName());
    }

    @Test
    public void echo() throws Exception {
        var env = Environment.create();
        env.export("main", new R1Server());
        var ss = new ServerSocket(0);
        env.acceptAll(ss);

        var session = env.connect(R1.class, "main", "localhost", ss.getLocalPort());
        R1 r1 = session.root();

        assertTrue(r1.echo(true));
        assertFalse(r1.echo(false));
        assertEquals('a', r1.echo('a'));
        assertTrue(1.23f == r1.echo(1.23f));
        assertTrue(2.34d == r1.echo(2.34d));
        assertEquals(-1, r1.echo((byte) -1));
        assertEquals(12345, r1.echo((short) 12345));
        assertEquals(Integer.MAX_VALUE, r1.echo(Integer.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, r1.echo(Long.MIN_VALUE));
        assertEquals("hello", r1.echo("hello"));
        assertEquals(null, r1.echo((Object) null));

        var result = (Object[]) r1.echo(new R1[] {r1});
        assertEquals(1, result.length);
        assertEquals(r1, result[0]);

        env.close();
    }

    @RemoteFailure(declared=false)
    public static interface R1 extends Remote {
        boolean echo(boolean v);

        char echo(char v);

        float echo(float v);

        double echo(double v);

        byte echo(byte v);

        short echo(short v);

        int echo(int v);

        long echo(long v);

        Object echo(Object v);
    }

    private static class R1Server implements R1 {
        @Override
        public boolean echo(boolean v) {
            return v;
        }

        @Override
        public char echo(char v) {
            return v;
        }

        @Override
        public float echo(float v) {
            return v;
        }

        @Override
        public double echo(double v) {
            return v;
        }

        @Override
        public byte echo(byte v) {
            return v;
        }

        @Override
        public short echo(short v) {
            return v;
        }

        @Override
        public int echo(int v) {
            return v;
        }

        @Override
        public long echo(long v) {
            return v;
        }

        @Override
        public Object echo(Object v) {
            return v;
        }
    }
}
