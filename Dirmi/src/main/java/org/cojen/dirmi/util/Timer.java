/*
 *  Copyright 2010 Brian S O'Neill
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

import java.util.concurrent.TimeUnit;

/**
 * Measures time remaining from an initial duration.
 *
 * @author Brian S O'Neill
 */
public class Timer {
    /**
     * Returns a new nanosecond Timer.
     */
    public static Timer nanos(long duration) {
        return new Timer(duration, TimeUnit.NANOSECONDS);
    }

    /**
     * Returns a new microsecond Timer.
     */
    public static Timer micros(long duration) {
        return new Timer(duration, TimeUnit.MICROSECONDS);
    }

    /**
     * Returns a new millisecond Timer.
     */
    public static Timer millis(long duration) {
        return new Timer(duration, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns a new seconds Timer.
     */
    public static Timer seconds(long duration) {
        return new Timer(duration, TimeUnit.SECONDS);
    }

    /**
     * Returns a new minutes Timer.
     */
    public static Timer minutes(long duration) {
        return new Timer(duration, TimeUnit.MINUTES);
    }

    /**
     * Returns a new hours Timer.
     */
    public static Timer hours(long duration) {
        return new Timer(duration, TimeUnit.HOURS);
    }

    /**
     * Returns a new days Timer.
     */
    public static Timer days(long duration) {
        return new Timer(duration, TimeUnit.DAYS);
    }

    private final long mDuration;
    private final TimeUnit mUnit;
    private final long mStartNanos;

    public Timer(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("Negative timer duration: " + duration);
        }
        if (unit == null) {
            throw new IllegalArgumentException("Null timer unit");
        }
        mDuration = duration;
        mUnit = unit;
        mStartNanos = System.nanoTime();
    }

    /**
     * Returns original fixed duration of this Timer.
     */
    public long duration() {
        return mDuration;
    }

    /**
     * Returns original fixed unit of this Timer.
     */
    public TimeUnit unit() {
        return mUnit;
    }

    /**
     * Returns remaining duration, which is zero or negative if duration has
     * elapsed.
     */
    public long remaining() {
        return mDuration - mUnit.convert(System.nanoTime() - mStartNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public String toString() {
        return "Timer {duration=" + mDuration + ", unit=" + mUnit +
            ", remaining=" + remaining() + '}';
    }
}
