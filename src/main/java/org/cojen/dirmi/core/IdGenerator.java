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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.security.SecureRandom;

/**
 * Produces a pseudo random sequence of 64-bit values which are unique until all 64-bit values
 * are depleted, and then the same sequence repeats.
 *
 * @author Brian S O'Neill
 */
final class IdGenerator {
    private static final long sequenceMask;
    private static long sequence;
    private static final VarHandle sequenceHandle;

    static {
        var rnd = new SecureRandom();
        sequenceMask = rnd.nextLong();
        sequence = rnd.nextLong();

        try {
            var lookup = MethodHandles.lookup();
            sequenceHandle = lookup.findStaticVarHandle(IdGenerator.class, "sequence", long.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    /**
     * Returns any 64-bit value other than zero.
     */
    static long next() {
        while (true) {
            long id = scramble((long) sequenceHandle.getAndAdd(1)) ^ sequenceMask;
            if (id != 0) {
                return id;
            }
        }
    }

    /**
     * Returns a positive non-zero identifier. Calling this method depletes the sequence at a
     * higher rate.
     */
    static long nextPositive() {
        while (true) {
            long id = next();
            if (id > 0) {
                return id;
            }
        }
    }

    /**
     * Returns a negative identifier. Calling this method depletes the sequence at a higher
     * rate.
     */
    static long nextNegative() {
        while (true) {
            long id = next();
            if (id < 0) {
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
