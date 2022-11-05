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

import java.lang.ref.Reference;

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SoftCacheTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SoftCacheTest.class.getName());
    }

    @Test
    public void replace() throws Exception {
        record Key(int id) { }

        var cache = new SoftCache<Key, String>();
        var rnd = new Random(8675309);

        var values = new String[1000];
        for (int i=0; i<values.length; i++) {
            int id = rnd.nextInt();
            values[i] = "v" + id;
            assertNull(cache.put(new Key(id), values[i]));
        }

        rnd = new Random(8675309);

        var replacements = new String[values.length];
        for (int i=0; i<values.length; i++) {
            int id = rnd.nextInt();
            replacements[i] = "r" + id;
            assertTrue(values[i] == cache.put(new Key(id), replacements[i]));
        }
        
        rnd = new Random(8675309);

        for (int i=0; i<replacements.length; i++) {
            int id = rnd.nextInt();
            assertEquals(replacements[i], cache.get(new Key(id)));
        }

        assertEquals(replacements.length, cache.size());

        Reference.reachabilityFence(replacements);
    }
}
