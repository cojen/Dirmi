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

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteTimeoutsServer implements RemoteTimeouts {
    public void slow(long sleepMillis) {
        sleep(sleepMillis);
    }

    public String slow(long sleepMillis, String param) {
        sleep(sleepMillis);
        return param;
    }

    public byte slow(long sleepMillis, byte timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Byte slow(long sleepMillis, Byte timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public short slow(long sleepMillis, short timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Short slow(long sleepMillis, Short timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public int slow(long sleepMillis, int timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Integer slow(long sleepMillis, Integer timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public long slow(long sleepMillis, long timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Long slow(long sleepMillis, Long timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public double slow(long sleepMillis, double timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Double slow(long sleepMillis, Double timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public float slow(long sleepMillis, float timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Float slow(long sleepMillis, Float timeout) {
        sleep(sleepMillis);
        return timeout;
    }

    public Float slow(Float timeout, long sleepMillis) {
        sleep(sleepMillis);
        return timeout;
    }

    public TimeUnit slow(long sleepMillis, int timeout, TimeUnit unit) {
        sleep(sleepMillis);
        return unit;
    }

    public TimeUnit slow(double timeout, TimeUnit unit, long sleepMillis) {
        sleep(sleepMillis);
        return unit;
    }

    public TimeUnit slow(long sleepMillis, TimeUnit unit, int timeout) {
        sleep(sleepMillis);
        return unit;
    }

    public RemoteTimeouts2 createRemoteTimeouts2() {
        return new RemoteTimeoutsServer2();
    }

    private void sleep(long millis) {
        AbstractTestLocalBroker.sleep(millis);
    }
}
