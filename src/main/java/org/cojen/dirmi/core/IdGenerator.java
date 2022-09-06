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

import java.security.SecureRandom;

/**
 * Produces a pseudo random sequence of 64-bit values which are unique until all 64-bit values
 * are depleted, and then the same sequence repeats.
 *
 * @author Brian S O'Neill
 * @see IdMap
 */
final class IdGenerator {
    static long I_SERVER = 0, I_CLIENT = 0b01L << 62, I_ALIAS = 0b10L << 62;

    private static final long sequenceMask;
    private static long sequence;

    static {
        var rnd = new SecureRandom();
        sequenceMask = rnd.nextLong();
        sequence = rnd.nextLong();
    }

    /**
     * Returns any 64-bit value other than zero.
     */
    static long next() {
        long id;
        do {
            synchronized (IdGenerator.class) {
                id = sequence;
                sequence = id + 1;
            }
            id = scramble(id) ^ sequenceMask;
        } while (id == 0);
        return id;
    }

    /**
     * Returns an identifier in which the upper two bits signify what type it is. Calling this
     * method depletes the sequence at a higher rate.
     *
     * @param type I_SERVER or I_CLIENT optionally combined with I_ALIAS
     */
    static long next(long type) {
        while (true) {
            long id = next();
            if ((id & (0b11L << 62)) == type) {
                return id;
            }
        }
    }

    /**
     * Apply Wang/Jenkins hash function to the given value. Hash is invertible, and so no
     * uniqueness is lost.
     */
    private static long scramble(long v) {
        v = (v << 21) - v - 1;
        v = v ^ (v >>> 24);
        v = (v + (v << 3)) + (v << 8); // v * 265
        v = v ^ (v >>> 14);
        v = (v + (v << 2)) + (v << 4); // v * 21
        v = v ^ (v >>> 28);
        return v + (v << 31);
    }
}
