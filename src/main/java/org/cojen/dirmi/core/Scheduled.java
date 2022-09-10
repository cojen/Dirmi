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

/**
 * Defines a scheduled task. If the task also implements Closeable and an exception is thrown
 * when trying to execute the task, the close method is called.
 *
 * @author Brian S O'Neill
 * @see Engine#scheduleNanos
 */
abstract class Scheduled implements Comparable<Scheduled>, Runnable {
    long mAtNanos;

    @Override
    public int compareTo(Scheduled other) {
        return Long.signum(mAtNanos - other.mAtNanos);
    }
}
