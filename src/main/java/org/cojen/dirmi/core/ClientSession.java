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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.net.SocketAddress;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ClientSession<R> extends CoreSession<R> {
    final SocketAddress mAddress;

    private final Support mSupport;
    private final ItemMap<Stub> mStubs;

    // FIXME: Stale connections need to be removed from the pool.

    private long mServerSessionId;
    private R mRoot;

    // FIXME: Note that ClientPipe needs to access the ItemMap for resolving remote objects.

    ClientSession(Engine engine, SocketAddress addr) {
        super(engine);
        mAddress = addr;
        mSupport = new Support();
        mStubs = new ItemMap<>();
    }

    /**
     * Called after the session is created.
     */
    void writeHeader(Pipe pipe, long serverSessionId) throws IOException {
        pipe.writeLong(CoreUtils.PROTOCOL_V2);
        pipe.writeLong(id);
        pipe.writeLong(serverSessionId);
    }

    /**
     * Called to finish initialization of a new client session.
     *
     * @param rootInfo server-side root info
     */
    @SuppressWarnings("unchecked")
    void init(long serverId, Class<R> rootType, RemoteInfo rootInfo, long rootId, long rootTypeId) {
        mServerSessionId = serverId;

        // FIXME: stash rootInfo and rootTypeId in some kind of map

        Stub root = StubMaker.factoryFor(rootType, rootInfo).newStub(rootId, mSupport);
        mStubs.put(root);
        mRoot = (R) root;
    }

    @Override
    public R root() {
        return mRoot;
    }

    @Override
    public void connected(InputStream in, OutputStream out) throws IOException {
        var pipe = new ClientPipe(this, in, out);

        long serverSessionId = mServerSessionId;
        if (serverSessionId != 0) {
            // Established a new connection for an existing session.
            try {
                writeHeader(pipe, serverSessionId);
                pipe.flush();
            } catch (IOException e) {
                CoreUtils.closeQuietly(pipe);
                throw e;
            }
        }

        recycleConnection(pipe);
    }

    @Override
    public void reset() {
        closeAllConnections();

        // FIXME: Calling reset should also close the control connection. Need to then
        // immediately establish a new connection. When the connected method is called, it
        // needs to detect that no connections currently exist and so it becomes the new
        // control connection. A new session needs to be created. So the initial connect code
        // in the Engine class should move to the session. A new client session id is needed
        // each time. This should cause all object ids known by all stubs to become invalid
        // because the server-side session will close too once the control connection is
        // closed. There's a bit of a race here, but that seems okay. Note that stubs which
        // have been disposed of need to remember this so that don't attempt to restore
        // themselves. Only needs to apply to restorable stubs, and so just clear the path?

        // FIXME: There's a still an odd (although unlikely) case in which the new session is
        // established with a restarted server process and the new object ids collide with the
        // old ods. A sweep through the stubs might be safer. Set the object id to zero unless
        // the stub refers to the current client session id.
    }

    @Override
    public void close() {
        // FIXME: disable new connections
        reset();
    }

    /**
     * Returns a new or existing connection. Closing it attempts to recycle it.
     */
    CorePipe connect() throws IOException {
        while (true) {
            CorePipe pipe = tryObtainConnection();
            if (pipe != null) {
                return pipe;
            }
            mEngine.checkClosed().connect(this, mAddress);
        }
    }

    private final class Support implements StubSupport {
        private volatile StackTraceElement mStitch;

        @Override
        public Pipe unbatch() {
            // FIXME: unbatch
            throw null;
        }

        @Override
        public void rebatch(Pipe pipe) {
            // FIXME: rebatch
            throw null;
        }

        @Override
        public <T extends Throwable> Pipe connect(Class<T> remoteFailureException) throws T {
            try {
                return ClientSession.this.connect();
            } catch (Throwable e) {
                throw CoreUtils.remoteException(remoteFailureException, e);
            }
        }

        @Override
        public <T extends Throwable, R> R createBatchedRemote(Class<T> remoteFailureException,
                                                              Pipe pipe, Class<R> type)
            throws T
        {
            // FIXME: createBatchedRemote
            throw null;
        }

        @Override
        public Throwable readResponse(Pipe pipe) throws IOException {
            var ex = (Throwable) pipe.readObject();

            if (ex == null) {
                return null;
            }

            // Augment the stack trace with a local trace.

            StackTraceElement[] trace = ex.getStackTrace();
            StackTraceElement stitch = stitch();
            StackTraceElement[] local = new Throwable().getStackTrace();

            var combined = new StackTraceElement[trace.length + 1 + local.length];
            System.arraycopy(trace, 0, combined, 0, trace.length);
            combined[trace.length] = stitch;
            System.arraycopy(local, 0, combined, trace.length + 1, local.length);

            ex.setStackTrace(combined);

            return ex;
        }

        @Override
        public void finished(Pipe pipe) {
            try {
                pipe.recycle();
            } catch (IOException e) {
                // FIXME: log it
                CoreUtils.uncaughtException(e);
            }
        }

        @Override
        public void batched(Pipe pipe) {
            // FIXME: batched
            throw null;
        }

        @Override
        public void release(Pipe pipe) {
            // Nothing to do.
        }

        @Override
        public <T extends Throwable> T failed(Class<T> remoteFailureException,
                                              Pipe pipe, Throwable cause)
        {
            return CoreUtils.remoteException(remoteFailureException, cause);
        }

        @Override
        public StubSupport dispose(Stub stub) {
            // FIXME: dispose
            throw null;
        }

        private StackTraceElement stitch() {
            StackTraceElement stitch = mStitch;
            if (stitch == null) {
                mStitch = stitch = new StackTraceElement
                    ("...remote method invocation..", "", mAddress.toString(), -1);
            }
            return stitch;
        }
    }
}
