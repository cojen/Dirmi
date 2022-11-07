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

import java.io.Closeable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.cojen.dirmi.RemoteException;

/**
 * A delayed task to close a connection.
 *
 * @author Brian S O'Neill
 */
final class CloseTimeout extends Scheduled {
    private static final VarHandle cConHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cConHandle = lookup.findVarHandle(CloseTimeout.class, "mCon", Closeable.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    private volatile Closeable mCon;

    CloseTimeout(Closeable c) {
        mCon = c;
    }

    /**
     * @return false if unable to cancel in time
     */
    boolean cancel() {
        Closeable con = mCon;
        return con != null && cConHandle.compareAndSet(this, con, null);
    }

    /**
     * @throws RemoteException if task isn't null and unable to cancel in time
     */
    static void cancelOrFail(CloseTimeout task) throws RemoteException {
        if (task != null && !task.cancel()) {
            throw new RemoteException("Timed out");
        }
    }

    @Override
    public void run() {
        Closeable con = mCon;
        if (con != null && cConHandle.compareAndSet(this, con, null)) {
            CoreUtils.closeQuietly(con);
        }
    }
}
