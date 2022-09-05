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
public class IdGeneratorTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(IdGeneratorTest.class.getName());
    }

    @Test
    public void next() {
        long last = 0;
        for (int i=0; i<10; i++) {
            long id = IdGenerator.next();
            if (i != 0) {
                assertNotEquals(last, id);
            }
            last = id;
        }
    }

    @Test
    public void nextServerId() {
        long last = 0;
        for (int i=0; i<10; i++) {
            long id = IdGenerator.next(IdGenerator.I_SERVER);
            assertEquals(0b00, id >>> 62);
            if (i != 0) {
                assertNotEquals(last, id);
            }
            last = id;
        }
    }

    @Test
    public void nextClientId() {
        long last = 0;
        for (int i=0; i<10; i++) {
            long id = IdGenerator.next(IdGenerator.I_CLIENT);
            assertEquals(0b01, id >>> 62);
            if (i != 0) {
                assertNotEquals(last, id);
            }
            last = id;
        }
    }

    @Test
    public void nextServerAliasId() {
        long last = 0;
        for (int i=0; i<10; i++) {
            long id = IdGenerator.next(IdGenerator.I_SERVER | IdGenerator.I_ALIAS);
            assertEquals(0b10, id >>> 62);
            if (i != 0) {
                assertNotEquals(last, id);
            }
            last = id;
        }
    }

    @Test
    public void nextClientAliasId() {
        long last = 0;
        for (int i=0; i<10; i++) {
            long id = IdGenerator.next(IdGenerator.I_CLIENT | IdGenerator.I_ALIAS);
            assertEquals(0b11, id >>> 62);
            if (i != 0) {
                assertNotEquals(last, id);
            }
            last = id;
        }
    }
}
