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

package org.cojen.dirmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for testing basic async methods.
 *
 * @author Brian S O'Neill
 */
public interface RemoteAsync extends Remote {
    @Asynchronous
    void runCommand(int sleepMillis) throws RemoteException;

    @Asynchronous
    void runCommand2(int sleepMillis, Callback callback) throws RemoteException;

    @Asynchronous(CallMode.EVENTUAL)
    void runCommand3(int sleepMillis, Callback callback) throws RemoteException;

    @Asynchronous(CallMode.ACKNOWLEDGED)
    void runCommand4(int sleepMillis, Callback callback) throws RemoteException;

    @Asynchronous(CallMode.ACKNOWLEDGED)
    void ack() throws RemoteException;

    void sync() throws RemoteException;

    @Asynchronous(CallMode.EVENTUAL)
    void data(int[] data) throws RemoteException;

    public static interface Callback extends Remote {
        @Asynchronous
        @RemoteFailure(declared=false)
        void done(int value);
    }
}
