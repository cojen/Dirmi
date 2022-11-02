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

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class CanonicalSetTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CanonicalSetTest.class.getName());
    }

    @Test
    public void basic() throws Exception {
        record Rec1(int a) {}
        record Rec2(String b) {}

        var set = new CanonicalSet<Object>();

        Rec1 r1 = (Rec1) set.add(new Rec1(10));
        Rec2 r2 = (Rec2) set.add(new Rec2("hello"));

        assertSame(r1, set.add(new Rec1(10)));
        assertSame(r2, set.add(new Rec2("hello")));

        assertEquals(2, set.size());
    }

    @Test
    public void flood() throws Exception {
        record Rec(int a) {}

        var set = new CanonicalSet<Rec>();

        int lastSize = 0;

        var rnd = new java.util.Random();

        while (true) {
            var rec = new Rec(rnd.nextInt());
            assertEquals(rec, set.add(rec));
            int size = set.size();
            if (size < lastSize) {
                break;
            }
            lastSize = size;
        }

        assertTrue(lastSize > 0);
    }
}
