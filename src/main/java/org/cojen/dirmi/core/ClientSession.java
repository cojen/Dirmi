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

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ClientSession<R> extends CoreSession<R> {
    private final SocketAddress mRemoteAddress;

    // FIXME: Stale connections need to be removed from the pool.

    private long mServerSessionId;
    private R mRoot;

    ClientSession(Engine engine, SocketAddress remoteAddr) {
        super(engine);
        mRemoteAddress = remoteAddr;
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
    void init(long serverId, Class<R> rootType, long rootTypeId, RemoteInfo rootInfo, long rootId) {
        mServerSessionId = serverId;

        StubFactory factory = StubMaker.factoryFor(rootType, rootTypeId, rootInfo);
        factory = mStubFactories.putIfAbsent(factory);
        Stub root = factory.newStub(rootId, mStubSupport);
        mStubs.put(root);
        mRoot = (R) root;
    }

    @Override
    public void close() {
        super.close();
        mEngine.removeSession(this);
    }

    @Override
    public R root() {
        return mRoot;
    }

    @Override
    public void connected(SocketAddress localAddr, SocketAddress remoteAttr,
                          InputStream in, OutputStream out) throws IOException
    {
        var pipe = new CorePipe(localAddr, remoteAttr, in, out, CorePipe.M_CLIENT);

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

        registerNewAvailableConnection(pipe);
    }

    @Override
    CorePipe connect() throws IOException {
        while (true) {
            CorePipe pipe = tryObtainConnection();
            if (pipe != null) {
                return pipe;
            }
            mEngine.checkClosed().connect(this, mRemoteAddress);
        }
    }

    @Override
    void reverseConnect(long id) {
        try {
            CorePipe pipe = connect();
            pipe.writeLong(id);
            pipe.flush();
            pipe.mMode = CorePipe.M_SERVER;
            recycleConnection(pipe);
        } catch (IOException e) {
            // FIXME: log and close session?
            CoreUtils.uncaughtException(e);
        }
    }
}
