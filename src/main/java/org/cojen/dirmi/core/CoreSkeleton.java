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

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public abstract class CoreSkeleton<R> extends Skeleton<R> {
    protected final SkeletonSupport support;

    private final Throwable mException;

    public CoreSkeleton(long id, SkeletonSupport support) {
        super(id);
        this.support = support;
        mException = null;
    }

    /**
     * Constructs a broken skeleton. The associated server should be null.
     * 
     * @see SkeletonFactory
     */
    public CoreSkeleton(Throwable exception, long id, SkeletonSupport support) {
        super(id);
        this.support = support;
        mException = exception;
    }

    @Override
    public final Throwable checkException(Throwable exception) {
        return mException == null ? exception : mException;
    }
}
