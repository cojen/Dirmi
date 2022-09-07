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
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.cojen.dirmi.NoSuchObjectException;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ServerSession<R> extends Item implements Session<R> {
    final Engine mEngine;

    private final Support mSupport;
    private final SkeletonMap mSkeletons;
    private final Skeleton<R> mRoot;

    /**
     * @param rootInfo client-side root info
     */
    ServerSession(Engine engine, R root, RemoteInfo rootInfo) throws IOException {
        super(IdGenerator.next());
        mEngine = engine;
        mSupport = new Support();
        mSkeletons = new SkeletonMap(this, IdGenerator.I_SERVER);

        // FIXME: stash rootInfo in a string to info map

        mRoot = mSkeletons.skeletonFor(root, rootInfo);
    }

    /**
     * Called after the session is created.
     */
    void writeHeader(Pipe pipe, long clientSessionId) throws IOException {
        pipe.writeLong(CoreUtils.PROTOCOL_V2);
        pipe.writeLong(clientSessionId);
        pipe.writeLong(id);
        pipe.writeLong(mRoot.id);
        pipe.writeLong(mRoot.typeId());
    }

    @Override
    public R root() {
        return mRoot.server();
    }

    @Override
    public void connected(InputStream in, OutputStream out) throws IOException {
        // FIXME: unsupported? what does it mean to call this method?
        throw null;
    }

    @Override
    public void reset() {
        // FIXME: reset
        throw null;
    }

    @Override
    public void close() {
        // FIXME: close
        throw null;
    }

    void accepted(ServerPipe pipe) throws IOException {
        // FIXME: register the pipe/invoker
        var invoker = new Invoker(pipe);
        mEngine.execute(invoker);
    }

    /**
     * Returns the remote info for the given type, as known by the client.
     */
    RemoteInfo clientRemoteInfo(Class<?> type) throws IOException {
        // FIXME: consult the map or make a remote call and cache the results
        throw null;
    }

    SkeletonSupport support() {
        return mSupport;
    }

    private final class Support implements SkeletonSupport {
        @Override
        public Object handleException(Pipe pipe, Throwable ex) {
            // FIXME: handleException
            throw null;
        }

        @Override
        public void dispose(Skeleton<?> skeleton) {
            // FIXME: dispose
            throw null;
        }
    }

    private final class Invoker implements Runnable, Closeable {
        private final ServerPipe mPipe;

        Invoker(ServerPipe pipe) {
            mPipe = pipe;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    long id = mPipe.readLong();

                    Skeleton skeleton;
                    try {
                        skeleton = mSkeletons.get(id);
                    } catch (NoSuchObjectException e) {
                        // FIXME: Try to write back to the client, but all input must be
                        // drained first to avoid deadlocks. Launch a thread to drain input and
                        // let the client close the connection. Launch another task to force
                        // close after a timeout.
                        throw e;
                    }

                    // FIXME: context
                    skeleton.invoke(mPipe, null);
                }
            } catch (IOException e) {
                // Ignore.
            } catch (Throwable e) {
                // FIXME: log it?
                CoreUtils.uncaughtException(e);
            } finally {
                CoreUtils.closeQuietly(this);
            }
        }

        @Override
        public void close() throws IOException {
            // FIXME: unregister
            mPipe.close();
        }
    }
}
