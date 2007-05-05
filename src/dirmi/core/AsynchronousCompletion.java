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

package dirmi.core;

import java.rmi.Remote;
import java.rmi.RemoteException;

import dirmi.Asynchronous;

/**
 * Callback for {@link Asynchronous} methods which have a limited number of
 * permits.
 *
 * @author Brian S O'Neill
 */
public interface AsynchronousCompletion extends Remote {
    /**
     * Called remotely by server when method has completed.
     */
    @Asynchronous
    void completed() throws RemoteException;

    /**
     * Called locally when session has closed or if there is a communication error.
     */
    void dispose() throws RemoteException;
}
