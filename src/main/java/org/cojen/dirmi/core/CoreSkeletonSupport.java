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

import java.io.IOException;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class CoreSkeletonSupport implements SkeletonSupport {
    private final CoreSession<?> mSession;

    CoreSkeletonSupport(CoreSession<?> session) {
        mSession = session;
    }

    @Override
    public void createSkeletonAlias(Object server, long aliasId) {
        mSession.createSkeletonAlias(server, aliasId);
    }

    @Override
    public void createBrokenSkeletonAlias(Class<?> type, long aliasId, Throwable exception) {
        mSession.createBrokenSkeletonAlias(type, aliasId, exception);
    }

    @Override
    public void writeSkeletonAlias(Pipe pipe, Object server, long aliasId) throws IOException {
        mSession.writeSkeletonAlias((CorePipe) pipe, server, aliasId);
    }

    @Override
    public void writeBrokenSkeletonAlias(Pipe pipe,
                                         Class<?> type, long aliasId, Throwable exception)
        throws IOException
    {
        mSession.writeBrokenSkeletonAlias((CorePipe) pipe, type, aliasId, exception);
    }

    @Override
    public void dispose(Skeleton<?> skeleton) {
        mSession.removeSkeleton(skeleton);
    }

    @Override
    public void uncaughtException(Throwable e) {
        mSession.uncaughtException(e);
    }
}
