/*
 *  Copyright 2009-2010 Brian S O'Neill
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

/**
 * Exception indicating that an I/O operation failed because no threads are
 * available.
 *
 * @author Brian S O'Neill
 */
public class RejectedException extends RemoteException {
    private final boolean mShutdown;

    /**
     * @param isShutdown pass true if casued by executor shutdown
     */
    public RejectedException(boolean isShutdown) {
        this(isShutdown, "Rejected");
    }

    /**
     * @param isShutdown pass true if casued by executor shutdown
     */
    public RejectedException(boolean isShutdown, String message) {
        super(message);
        mShutdown = isShutdown;
    }

    /**
     * @param isShutdown pass true if casued by executor shutdown
     */
    public RejectedException(boolean isShutdown, Throwable cause) {
        super(cause.getMessage(), cause);
        mShutdown = isShutdown;
    }

    /**
     * @param isShutdown pass true if casued by executor shutdown
     */
    public RejectedException(boolean isShutdown, String message, Throwable cause) {
        super(message, cause);
        mShutdown = isShutdown;
    }

    public RejectedException(String message, RejectedException cause) {
        super(message, getCause(cause));
        mShutdown = cause == null ? false : cause.isShutdown();
    }

    /**
     * Returns true if caused by executor shutdown.
     */
    public boolean isShutdown() {
        return mShutdown;
    }

    private static Throwable getCause(RejectedException e) {
        if (e == null) {
            return null;
        }
        Throwable cause = e.getCause();
        return cause == null ? e : cause;
    }
}
