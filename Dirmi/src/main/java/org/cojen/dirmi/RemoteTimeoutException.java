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

package org.cojen.dirmi;

import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.util.Timer;

/**
 * Thrown when a remote method has not responded in time.
 *
 * @author Brian S O'Neill
 * @see Timeout
 */
public class RemoteTimeoutException extends RemoteException {
    private static final long serialVersionUID = 1;

    private static String createMessage(double timeout, TimeUnit unit) {
        return createMessage(String.valueOf(timeout), unit, timeout != 1.0);
    }

    private static String createMessage(long timeout, TimeUnit unit) {
        return createMessage(String.valueOf(timeout), unit, timeout != 1L);
    }

    private static String createMessage(String timeout, TimeUnit unit, boolean plural) {
        String unitString;

        if (unit == null) {
            unitString = null;
        } else {
            switch (unit) {
            case NANOSECONDS:
                unitString = "nanosecond";
                break;
            case MICROSECONDS:
                unitString = "microsecond";
                break;
            case MILLISECONDS:
                unitString = "millisecond";
                break;
            case SECONDS:
                unitString = "second";
                break;
            case MINUTES:
                unitString = "minute";
                break;
            case HOURS:
                unitString = "hour";
                break;
            case DAYS:
                unitString = "day";
                break;
            default:
                unitString = null;
                break;
            }
        }

        if (unitString == null) {
            unitString = "(unknown time unit)";
        } else if (plural) {
            unitString += 's';
        }

        return "Timed out after " + timeout + ' ' + unitString;
    }

    public RemoteTimeoutException(long timeout, TimeUnit unit) {
        super(createMessage(timeout, unit));
    }

    public RemoteTimeoutException(double timeout, TimeUnit unit) {
        super(createMessage(timeout, unit));
    }

    public RemoteTimeoutException(Timer timer) {
        this(timer.duration(), timer.unit());
    }

    /**
     * Checks remaining duration and throws a RemoteTimeoutException if none left.
     *
     * @return remaining duration if more than zero
     */
    public static long checkRemaining(Timer timer) throws RemoteTimeoutException {
        long remaining = timer.remaining();
        if (remaining <= 0) {
            throw new RemoteTimeoutException(timer);
        }
        return remaining;
    }
}
