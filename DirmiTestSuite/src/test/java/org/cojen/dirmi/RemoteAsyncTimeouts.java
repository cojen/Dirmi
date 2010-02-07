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

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteAsyncTimeouts extends Remote {
    @Asynchronous
    @Timeout(1000)
    void slow(SlowSerializable obj) throws RemoteException;

    @Asynchronous
    @Timeout(2)
    @TimeoutUnit(TimeUnit.SECONDS)
    Future<String> slow(SlowSerializable obj, String param) throws RemoteException;

    @Asynchronous
    @TimeoutUnit(TimeUnit.SECONDS)
    Future<Byte> slow(SlowSerializable obj, @TimeoutParam byte timeout) throws RemoteException;

    @Asynchronous
    @TimeoutUnit(TimeUnit.SECONDS)
    Future<Byte> slow(SlowSerializable obj, @TimeoutParam Byte timeout) throws RemoteException;

    @Asynchronous
    @TimeoutUnit(TimeUnit.SECONDS)
    Future<Short> slow(SlowSerializable obj, @TimeoutParam short timeout) throws RemoteException;

    @Asynchronous
    @Timeout(2)
    @TimeoutUnit(TimeUnit.SECONDS)
    Future<Short> slow(SlowSerializable obj, @TimeoutParam Short timeout) throws RemoteException;

    @Asynchronous
    Future<Integer> slow(SlowSerializable obj, @TimeoutParam int timeout) throws RemoteException;

    @Asynchronous
    @Timeout(1500)
    Future<Integer> slow(SlowSerializable obj, @TimeoutParam Integer timeout)
                         throws RemoteException;

    @Asynchronous
    Future<Long> slow(SlowSerializable obj, @TimeoutParam long timeout) throws RemoteException;

    @Asynchronous
    Future<Long> slow(SlowSerializable obj, @TimeoutParam Long timeout) throws RemoteException;

    @Asynchronous
    Future<Double> slow(SlowSerializable obj, @TimeoutParam double timeout) throws RemoteException;

    @Asynchronous
    @Timeout(1500)
    Future<Double> slow(SlowSerializable obj, @TimeoutParam Double timeout) throws RemoteException;

    @Asynchronous
    Future<Float> slow(SlowSerializable obj, @TimeoutParam float timeout) throws RemoteException;

    @Asynchronous
    Future<Float> slow(SlowSerializable obj, @TimeoutParam Float timeout) throws RemoteException;

    @Asynchronous
    Future<Float> slow(@TimeoutParam Float timeout, SlowSerializable obj) throws RemoteException;

    // With unit parameters.

    @Asynchronous
    Future<TimeUnit> slow(SlowSerializable obj, @TimeoutParam int timeout, TimeUnit unit)
        throws RemoteException;

    @Asynchronous
    Future<TimeUnit> slow(@TimeoutParam double timeout, @TimeoutParam TimeUnit unit,
                          SlowSerializable obj)
        throws RemoteException;

    @Asynchronous
    @TimeoutUnit(TimeUnit.MINUTES)
    Future<TimeUnit> slow(SlowSerializable obj,
                          @TimeoutParam TimeUnit unit, @TimeoutParam int timeout)
        throws RemoteException;
}
