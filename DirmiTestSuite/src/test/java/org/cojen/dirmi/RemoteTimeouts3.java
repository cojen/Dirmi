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

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@Timeout(1)
@TimeoutUnit(TimeUnit.SECONDS)
public interface RemoteTimeouts3 extends Remote {
    void slow(long sleepMillis) throws RemoteException;

    @Timeout(2)
    void slow2(long sleepMillis) throws RemoteException;

    @Timeout(1500)
    @TimeoutUnit(TimeUnit.MILLISECONDS)
    void slow3(long sleepMillis) throws RemoteException;

    @TimeoutUnit(TimeUnit.MILLISECONDS)
    void slow4(long sleepMillis) throws RemoteException;

    void slow5(long sleepMillis, @TimeoutParam int timeout) throws RemoteException;

    void slow6(long sleepMillis, @TimeoutParam TimeUnit unit) throws RemoteException;
}
