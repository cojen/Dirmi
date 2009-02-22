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

package org.cojen.dirmi.core;

import java.io.IOException;

import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Session;

/**
 * Wrapper around StandardSession which supports automatic garbage collection
 * of unused sessions.
 *
 * @author Brian S O'Neill
 */
class SessionRef implements Session {
    private final StandardSession mSession;

    SessionRef(StandardSession session) {
        mSession = session;
    }

    public Object getLocalAddress() {
        return mSession.getLocalAddress();
    }

    public Object getRemoteAddress() {
        return mSession.getRemoteAddress();
    }

    public void send(Object obj) throws RemoteException {
        mSession.send(obj);
    }

    public void send(Object obj, long timeout, TimeUnit unit) throws RemoteException {
        mSession.send(obj, timeout, unit);
    }

    public Object receive() throws RemoteException {
        return mSession.receive();
    }

    public Object receive(long timeout, TimeUnit unit) throws RemoteException {
        return mSession.receive(timeout, unit);
    }

    public void flush() throws IOException {
        mSession.flush();
    }

    public void close() throws IOException {
        mSession.close();
    }

    @Override
    public String toString() {
        return mSession.toString();
    }

    @Override
    protected void finalize() {
        mSession.sessionUnreferenced();
    }
}
