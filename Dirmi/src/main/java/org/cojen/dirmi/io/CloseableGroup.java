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

package org.cojen.dirmi.io;

import java.io.Closeable;
import java.io.IOException;

import java.util.HashMap;
import java.util.Map;

import org.cojen.dirmi.ClosedException;

/**
 * Set of Closeable objects which can be closed as a unit. Once the group is
 * closed, adding more members immediately closes them.
 *
 * @author Brian S O'Neill
 */
public class CloseableGroup<C extends Closeable> implements Closeable {
    private final Map<C, Object> mGroup;

    private boolean mClosed;

    public CloseableGroup() {
        mGroup = new HashMap<C, Object>();
    }

    public synchronized boolean isClosed() {
        return mClosed;
    }

    /**
     * @throws ClosedException if group is closed
     */
    public synchronized void checkClosed() throws ClosedException {
        if (mClosed) {
            throw new ClosedException();
        }
    }

    /**
     * Add a Closeable member to the group, which is automatically closed when
     * group is closed.
     *
     * @param member group member to add
     * @return false if group is closed and member was immediately closed as a result
     */
    public boolean add(C member) {
        synchronized (this) {
            if (!mClosed) {
                mGroup.put(member, "");
                return true;
            }
        }

        try {
            member.close();
        } catch (IOException e) {
            // Ignore.
        }

        return false;
    }

    /**
     * Removes a member from the group, but does not close it.
     */
    public synchronized void remove(C member) {
        mGroup.remove(member);
    }

    /**
     * Closes the group and all group members.
     */
    @Override
    public void close() {
        Map<C, Object> copy;
        synchronized (this) {
            mClosed = true;
            copy = new HashMap<C, Object>(mGroup);
            mGroup.clear();
        }

        for (C c : copy.keySet()) {
            try {
                c.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
    }

    /**
     * Disconnects the group and all group members, which must be Channels.
     */
    void disconnect() {
        Map<C, Object> copy;
        synchronized (this) {
            mClosed = true;
            copy = new HashMap<C, Object>(mGroup);
            mGroup.clear();
        }

        for (C c : copy.keySet()) {
            ((Channel) c).disconnect();
        }
    }

    @Override
    public synchronized String toString() {
        return "CloseableGroup: " + mGroup.keySet();
    }
}
