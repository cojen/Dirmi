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
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteOrdered extends Remote {
    @Ordered
    @Asynchronous
    void nop() throws RemoteException;

    @Ordered
    void doIt(int sequence) throws RemoteException;

    @Ordered
    @Asynchronous
    void doItAsync(int sequence) throws RemoteException;

    @Ordered
    @Asynchronous
    Completion<Integer> doItCompletion(int sequence) throws RemoteException;

    @Ordered
    @Asynchronous
    Pipe doItPipe(Pipe pipe) throws RemoteException;

    void resetSequence(int sequence) throws RemoteException;

    boolean checkSequence() throws RemoteException;
}
