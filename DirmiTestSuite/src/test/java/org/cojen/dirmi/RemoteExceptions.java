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
public interface RemoteExceptions extends Remote {
    void stuff() throws RemoteException, NonSerializableException;

    void stuff2(String message) throws RemoteException, NonSerializableException;

    void stuff3(String message, String message2) throws RemoteException, NonSerializableException;

    void stuff4() throws RemoteException, NonSerializableException2;

    void stuff5(String message) throws RemoteException, NonSerializableException2;

    void stuff6(String message, String message2) throws RemoteException, NonSerializableException2;

    void stuff7() throws RemoteException, NonSerializableException3;

    void stuff8(String message) throws RemoteException, NonSerializableException3;

    void stuff9(String message, String message2) throws RemoteException, NonSerializableException3;

    void stuffa() throws RemoteException, NonSerializableException4;

    void stuffb(String message) throws RemoteException, NonSerializableException4;

    void stuffc(String message, String message2) throws RemoteException, NonSerializableException4;
}
