/*
 *  Copyright 2025 Cojen.org
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

/**
 * Base class for Skeletons which support AutoDispose.
 *
 * @author Brian S. O'Neill
 */
public abstract class AutoSkeleton<R> extends CoreSkeleton<R> {
    private static final VarHandle cRefCountHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cRefCountHandle = lookup.findVarHandle(AutoSkeleton.class, "mRefCount", long.class);
        } catch (Throwable e) {
            throw CoreUtils.rethrow(e);
        }
    }

    private volatile long mRefCount;

    public AutoSkeleton(long id, SkeletonSupport support) {
        super(id, support);
    }

    public AutoSkeleton(Throwable exception, long id, SkeletonSupport support) {
        super(exception, id, support);
    }

    @Override
    public void disposed() {
        mRefCount = Long.MIN_VALUE;
    }

    @Override
    public Skeleton transport() {
        return transport(this);
    }

    private static Skeleton transport(AutoSkeleton skeleton) {
        while (true) {
            long count = skeleton.mRefCount;

            if (count < -1) {
                // Disposed and cannot be resurrected.
                return skeleton;
            }

            if (count != -1) {
                if (cRefCountHandle.compareAndSet(skeleton, count, count + 1)) {
                    return skeleton;
                }
            } else {
                // The skeleton was auto disposed prematurely, so try to resurrect it.
                var replacement = (AutoSkeleton) skeleton.support.skeletonFor(skeleton.server());
                if (replacement == skeleton && skeleton.mRefCount < 0) {
                    // This case isn't expected, but return the disposed skeleton instead of
                    // looping forever.
                    return skeleton;
                }
                skeleton = replacement;
            }
        }
    }

    @Override
    public boolean shouldDispose(long transportCount) {
        while (true) {
            long count = mRefCount;

            if (count < 0) {
                // Should already be disposed.
                return true;
            }

            long newCount = count - transportCount;

            if (newCount > 0) {
                if (cRefCountHandle.compareAndSet(this, count, newCount)) {
                    return false;
                }
            } else if (newCount == 0) {
                if (cRefCountHandle.compareAndSet(this, count, -1)) {
                    return true;
                }
            } else {
                // Transport count is too big, which isn't expected. Go ahead and dispose.
                return true;
            }
        }
    }
}
