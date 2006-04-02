/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi;

import java.rmi.RemoteException;

import dirmi.io.Connection;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface Skeleton {
    /**
     * Invoke method in RemoteServer instance. Method may throw any kind of
     * exception, not just RemoteException.
     *
     * @param methodID ID of method in remote server
     * @param con Connection for reading arguments and writing response.
     */
    void invoke(short methodID, Connection con) throws RemoteException;
}
