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

import java.rmi.RemoteException;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cojen.dirmi.Response;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteAsyncTimeoutsServer implements RemoteAsyncTimeouts {
    public void slow(SlowSerializable obj) {
    }

    public Future<String> slow(SlowSerializable obj, String param) {
        return Response.complete(param);
    }

    public Future<Byte> slow(SlowSerializable obj, byte timeout) {
        return Response.complete(timeout);
    }

    public Future<Byte> slow(SlowSerializable obj, Byte timeout) {
        return Response.complete(timeout);
    }

    public Future<Short> slow(SlowSerializable obj, short timeout) {
        return Response.complete(timeout);
    }

    public Future<Short> slow(SlowSerializable obj, Short timeout) {
        return Response.complete(timeout);
    }

    public Future<Integer> slow(SlowSerializable obj, int timeout) {
        return Response.complete(timeout);
    }

    public Future<Integer> slow(SlowSerializable obj, Integer timeout) {
        return Response.complete(timeout);
    }

    public Future<Long> slow(SlowSerializable obj, long timeout) {
        return Response.complete(timeout);
    }

    public Future<Long> slow(SlowSerializable obj, Long timeout) {
        return Response.complete(timeout);
    }

    public Future<Double> slow(SlowSerializable obj, double timeout) {
        return Response.complete(timeout);
    }

    public Future<Double> slow(SlowSerializable obj, Double timeout) {
        return Response.complete(timeout);
    }

    public Future<Float> slow(SlowSerializable obj, float timeout) {
        return Response.complete(timeout);
    }

    public Future<Float> slow(SlowSerializable obj, Float timeout) {
        return Response.complete(timeout);
    }

    public Future<Float> slow(Float timeout, SlowSerializable obj) {
        return Response.complete(timeout);
    }

    public Future<TimeUnit> slow(SlowSerializable obj, int timeout, TimeUnit unit) {
        return Response.complete(unit);
    }

    public Future<TimeUnit> slow(double timeout, TimeUnit unit, SlowSerializable obj) {
        return Response.complete(unit);
    }

    public Future<TimeUnit> slow(SlowSerializable obj, TimeUnit unit, int timeout) {
        return Response.complete(unit);
    }
}
