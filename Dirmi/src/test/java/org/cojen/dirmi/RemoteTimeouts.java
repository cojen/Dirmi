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
public interface RemoteTimeouts extends Remote {
    @Timeout(1000)
    void slow(long sleepMillis) throws RemoteException;

    @Timeout(2)
    @TimeoutUnit(TimeUnit.SECONDS)
    String slow(long sleepMillis, String param) throws RemoteException;

    @TimeoutUnit(TimeUnit.SECONDS)
    byte slow(long sleepMillis, @TimeoutParam byte timeout) throws RemoteException;

    @TimeoutUnit(TimeUnit.SECONDS)
    Byte slow(long sleepMillis, @TimeoutParam Byte timeout) throws RemoteException;

    @TimeoutUnit(TimeUnit.SECONDS)
    short slow(long sleepMillis, @TimeoutParam short timeout) throws RemoteException;

    @Timeout(2)
    @TimeoutUnit(TimeUnit.SECONDS)
    Short slow(long sleepMillis, @TimeoutParam Short timeout) throws RemoteException;

    int slow(long sleepMillis, @TimeoutParam int timeout) throws RemoteException;

    @Timeout(1500)
    Integer slow(long sleepMillis, @TimeoutParam Integer timeout) throws RemoteException;

    long slow(long sleepMillis, @TimeoutParam long timeout) throws RemoteException;

    Long slow(long sleepMillis, @TimeoutParam Long timeout) throws RemoteException;

    double slow(long sleepMillis, @TimeoutParam double timeout) throws RemoteException;

    @Timeout(1500)
    Double slow(long sleepMillis, @TimeoutParam Double timeout) throws RemoteException;

    float slow(long sleepMillis, @TimeoutParam float timeout) throws RemoteException;

    Float slow(long sleepMillis, @TimeoutParam Float timeout) throws RemoteException;

    Float slow(@TimeoutParam Float timeout, long sleepMillis) throws RemoteException;

    // With unit parameters.

    TimeUnit slow(long sleepMillis, @TimeoutParam int timeout, TimeUnit unit)
        throws RemoteException;

    TimeUnit slow(@TimeoutParam double timeout, @TimeoutParam TimeUnit unit, long sleepMillis)
        throws RemoteException;

    @TimeoutUnit(TimeUnit.MINUTES)
    TimeUnit slow(long sleepMillis, @TimeoutParam TimeUnit unit, @TimeoutParam int timeout)
        throws RemoteException;
}
