/*
 *  Copyright 2024 Cojen.org
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

import java.util.HashMap;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class CounterMapTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CounterMapTest.class.getName());
    }

    @Test
    public void basic() {
        var map = new CounterMap();

        assertNull(map.drain());

        int limit = 100;

        var ids = new long[limit / 2];
        var idToOffset = new HashMap<Long, Integer>();

        for (int i=0; i<ids.length; i++) {
            ids[i] = IdGenerator.next();
            idToOffset.put(ids[i], i);
        }

        var values = new int[ids.length];

        for (int i=0; i<limit; i++) {
            long id = ids[i / 2];
            map.increment(id);
            values[i / 2]++;
            if ((id & 1) == 0) {
                map.increment(id);
                values[i / 2]++;
            }
        }

        CounterMap.Entry head = map.drain();
        assertNull(map.drain());

        int seen = 0;

        do {
            seen++;
            assertEquals(values[idToOffset.get(head.id)], head.counter);
        } while ((head = head.next) != null);

        assertEquals(values.length, seen);
    }
}
