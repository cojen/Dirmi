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

import java.util.concurrent.Future;

/**
 * Remote interface for testing completions.
 *
 * @author Brian S O'Neill
 */
public interface RemoteCompletions extends Remote {
    @Asynchronous
    Future<String> failCommand(int sleepMillis, String message) throws RemoteException;

    @Asynchronous
    Completion<String> failCommand2(int sleepMillis, String message) throws RemoteException;

    @Asynchronous
    Future<String> runCommand(int sleepMillis, String op) throws RemoteException;

    @Asynchronous
    Completion<String> runCommand2(int sleepMillis, String op) throws RemoteException;

    @Asynchronous(CallMode.EVENTUAL)
    Completion<String> runCommand3(int sleepMillis, String op) throws RemoteException;

    @Asynchronous(CallMode.ACKNOWLEDGED)
    <T> Completion<T> echo(T obj) throws RemoteException;
}
