/*
 *  Copyright 2009 Brian S O'Neill
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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractChannel implements Channel {
    private static final AtomicReferenceFieldUpdater<AbstractChannel, CloseListener>
        cListenerUpdater = AtomicReferenceFieldUpdater.newUpdater
        (AbstractChannel.class, CloseListener.class, "mListener");

    private volatile CloseListener mListener;

    public void addCloseListener(final CloseListener listener) {
        if (listener != null) {
            while (!cListenerUpdater.compareAndSet(this, null, listener)) {
                CloseListener existing = mListener;
                if (existing != null) {
                    Chain chain = new Chain(existing, listener);
                    if (cListenerUpdater.compareAndSet(this, existing, chain)) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns a listener that must be invoked after channel is closed. Returns
     * null if none registered.
     */
    protected CloseListener removeCloseListener() {
        return cListenerUpdater.getAndSet(this, null);
    }

    /**
     * Removes the close listener and calls it.
     */
    protected void closed() {
        CloseListener listener = removeCloseListener();
        if (listener != null) {
            listener.closed();
        }
    }

    private static class Chain implements CloseListener {
        final CloseListener mPrev;
        final CloseListener mNext;

        Chain(CloseListener prev, CloseListener next) {
            mPrev = prev;
            mNext = next;
        }

        public void closed() {
            mPrev.closed();
            mNext.closed();
        }
    }
}
