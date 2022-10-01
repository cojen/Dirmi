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

import org.cojen.dirmi.NoSuchObjectException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ItemMapTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ItemMapTest.class.getName());
    }

    @Test
    public void mix() throws Exception {
        var map = new ItemMap<Item>();

        var items = new Item[1000];
        for (int i=0; i<items.length; i++) {
            items[i] = new Item(IdGenerator.nextPositive());

            try {
                assertNull(map.get(items[i].id));
                fail();
            } catch (NoSuchObjectException e) {
            }

            assertNull(map.put(items[i]));
        }

        // Put again is harmless.
        assertTrue(items[0] == map.put(items[0]));

        for (int i=0; i<items.length; i++) {
            assertEquals(items[i], map.get(items[i].id));
        }

        for (int i=0; i<items.length; i+=2) {
            map.remove(items[i]);
            assertNull(items[i].mNext);
        }

        assertEquals(items.length / 2, map.size());

        for (int i=0; i<items.length; i++) {
            if ((i & 1) == 0) {
                try {
                    assertNull(map.get(items[i].id));
                    fail();
                } catch (NoSuchObjectException e) {
                }
            } else {
                assertEquals(items[i], map.get(items[i].id));
            }
        }

        for (int i=0; i<items.length; i++) {
            Item removed = map.remove(items[i].id);
            if ((i & 1) == 0) {
                assertNull(removed);
            } else {
                assertEquals(items[i], removed);
            }
        }

        assertEquals(0, map.size());

        map.clear();

        assertEquals(0, map.size());
    }

    @Test
    public void clear() throws Exception {
        var map = new ItemMap<Item>();

        var item = new Item(IdGenerator.nextPositive());
        assertNull(map.put(item));
        assertEquals(item, map.get(item.id));
        assertEquals(1, map.size());

        map.clear();
        assertEquals(0, map.size());

        try {
            assertNull(map.get(item.id));
            fail();
        } catch (NoSuchObjectException e) {
        }

        assertNull(map.put(item));
        assertEquals(item, map.get(item.id));
        assertEquals(1, map.size());
    }

    @Test
    public void replace() throws Exception {
        var map = new ItemMap<Item>();

        var items = new Item[1000];
        for (int i=0; i<items.length; i++) {
            items[i] = new Item(IdGenerator.nextPositive());
            assertNull(map.put(items[i]));
        }

        var replacements = new Item[items.length];
        for (int i=0; i<items.length; i++) {
            replacements[i] = new Item(items[i].id);
            assertTrue(items[i] == map.put(replacements[i]));
        }
        
        assertEquals(items.length, map.size());

        for (int i=0; i<items.length; i++) {
            assertEquals(replacements[i], map.get(replacements[i].id));
        }
    }
}
