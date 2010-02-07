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

package org.cojen.dirmi.util;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestRandom {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestRandom.class.getName());
    }

    @Test
    public void testIntRange() {
        try {
            Random.randomInt(0);
            fail();
        } catch (IllegalArgumentException e) {
        }

        for (int i=0; i<10; i++) {
            assertEquals(0, Random.randomInt(1));
        }

        for (int i=0; i<10; i++) {
            int r = Random.randomInt(10);
            assertTrue(r >= 0 && r < 10);
        }

        for (int i=0; i<10; i++) {
            int r = Random.randomInt(65536);
            assertTrue(r >= 0 && r < 65536);
        }
    }

    @Test
    public void testLongRange() {
        try {
            Random.randomLong(0);
            fail();
        } catch (IllegalArgumentException e) {
        }

        for (int i=0; i<10; i++) {
            assertEquals(0, Random.randomLong(1));
        }

        for (int i=0; i<10; i++) {
            long r = Random.randomLong(10);
            assertTrue(r >= 0 && r < 10);
        }

        for (int i=0; i<10; i++) {
            long r = Random.randomLong(65536);
            assertTrue(r >= 0 && r < 65536);
        }
    }

    @Test
    public void testBoolean() {
        boolean f = false;
        boolean t = false;
        for (int i=0; i<100; i++) {
            boolean b = Random.randomBoolean();
            f |= b == false;
            t |= b == true;
        }
        assertTrue(f);
        assertTrue(t);
    }

    @Test
    public void testFloat() {
        for (int i=0; i<1000; i++) {
            float f = Random.randomFloat();
            assertTrue(f >= 0.0f && f < 1.0f);
        }
    }

    @Test
    public void testDouble() {
        for (int i=0; i<1000; i++) {
            double d = Random.randomDouble();
            assertTrue(d >= 0.0 && d < 1.0);
        }
    }

    @Test
    public void testBytes() {
        byte[] bytes = new byte[100];
        for (int i=0; i<1000; i++) {
            Random.randomBytes(bytes);
            check: {
                for (byte b : bytes) {
                    if (b == 0) {
                        break check;
                    }
                }
                return;
            }
        }
        fail();
    }
}
