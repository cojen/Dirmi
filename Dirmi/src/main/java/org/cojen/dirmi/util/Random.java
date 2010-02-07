/*
 *  Copyright 2008-2010 Brian S O'Neill
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

/**
 * Collection of thread-safe methods for generating random numbers. The
 * implementation relies on thread-local pseudorandom number generators, each
 * seeded by a secure random number generator. Mersenne twister is used for
 * pseudorandom numbers and SecureRandom for seeding.
 *
 * @author Brian S O'Neill
 */
public class Random {
    public static final int randomInt() {
        return MersenneTwisterFast.localInstance().nextInt();
    }

    /**
     * @param n upper bound, exclusive
     */
    public static final int randomInt(int n) {
        return MersenneTwisterFast.localInstance().nextInt(n);
    }

    public static final long randomLong() {
        return MersenneTwisterFast.localInstance().nextLong();
    }

    /**
     * @param n upper bound, exclusive
     */
    public static final long randomLong(long n) {
        return MersenneTwisterFast.localInstance().nextLong(n);
    }

    public static final boolean randomBoolean() {
        return MersenneTwisterFast.localInstance().nextBoolean();
    }

    public static final float randomFloat() {
        return MersenneTwisterFast.localInstance().nextFloat();
    }

    public static final double randomDouble() {
        return MersenneTwisterFast.localInstance().nextDouble();
    }

    public static final void randomBytes(byte[] bytes) {
        MersenneTwisterFast.localInstance().nextBytes(bytes);
    }
}
