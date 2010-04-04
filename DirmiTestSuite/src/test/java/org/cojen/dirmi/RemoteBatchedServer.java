/*
 *  Copyright 2009-2010 Brian S O'Neill
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

package org.cojen.dirmi;

import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteBatchedServer implements RemoteBatched {
    private final String mName;

    private final List<TaskListener> mListeners;

    public RemoteBatchedServer() {
        this("root");
    }

    public RemoteBatchedServer(String name) {
        this(name, new ArrayList<TaskListener>());
    }

    public RemoteBatchedServer(String name, List<TaskListener> listeners) {
        mName = name;
        mListeners = listeners;
    }

    public synchronized void register(TaskListener listener) {
        mListeners.add(listener);
    }

    public synchronized void startTask(String op) {
        for (TaskListener listener : mListeners) {
            try {
                listener.started(mName, op);
            } catch (RemoteException e) {
                // Ignore.
            }
        }
    }

    public void unbatchedTask(String op) {
        startTask(op);
    }

    public RemoteBatched chain(String name) {
        return new RemoteBatchedServer(name, mListeners);
    }

    public String getName() {
        return mName;
    }

    public void eventualNop() {
        // Do nothing.
    }

    public void asyncNop() {
        // Do nothing.
    }

    public void syncNop() {
        // Do nothing.
    }

    public Completion<String> echo(String name) {
        return Response.complete(name);
    }

    public void manyExceptions() throws InterruptedException {
        throw new InterruptedException("interrupted");
    }

    public void syncNop2() {
        // Do nothing.
    }

    public void syncNop3() {
        // Do nothing.
    }

    private final ThreadLocal<Object> mLast = new ThreadLocal<Object>();

    public Completion<Boolean> testStreamReset(Object a) {
        boolean b = mLast.get() == a;
        mLast.set(a);
        return Response.complete(b);
    }

    public Completion<Boolean> testSharedRef(Object a, Object b) {
        return Response.complete(a == b);
    }
}
