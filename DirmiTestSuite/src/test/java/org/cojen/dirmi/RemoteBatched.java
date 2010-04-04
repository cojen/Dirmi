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

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for testing batched methods.
 *
 * @author Brian S O'Neill
 */
public interface RemoteBatched extends Remote {
    void register(TaskListener listener) throws RemoteException;

    @Batched
    void startTask(String op) throws RemoteException;

    @Unbatched
    void unbatchedTask(String op) throws RemoteException;

    @Batched
    RemoteBatched chain(String name) throws RemoteException;

    String getName() throws RemoteException;

    @Asynchronous(CallMode.EVENTUAL)
    void eventualNop() throws RemoteException;

    @Asynchronous(CallMode.IMMEDIATE)
    void asyncNop() throws RemoteException;

    void syncNop() throws RemoteException;

    @Batched
    Completion<String> echo(String name) throws RemoteException;

    @Batched
    void manyExceptions() throws RemoteException, java.sql.SQLException, InterruptedException;

    void syncNop2() throws RemoteException, java.sql.SQLException;

    void syncNop3() throws RemoteException, java.sql.SQLException, InterruptedException;

    @Batched
    Completion<Boolean> testStreamReset(Object a) throws RemoteException;

    @Batched
    Completion<Boolean> testSharedRef(Object a, Object b) throws RemoteException;

    public static interface TaskListener extends Remote {
        void started(String name, String op) throws RemoteException;
    }
}
