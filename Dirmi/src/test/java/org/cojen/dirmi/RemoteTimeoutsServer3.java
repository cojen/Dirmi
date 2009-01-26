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

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteTimeoutsServer3 implements RemoteTimeouts3 {
    public void slow(long sleepMillis) {
        sleep(sleepMillis);
    }

    public void slow2(long sleepMillis) {
        sleep(sleepMillis);
    }

    public void slow3(long sleepMillis) {
        sleep(sleepMillis);
    }

    public void slow4(long sleepMillis) {
        sleep(sleepMillis);
    }

    public void slow5(long sleepMillis, int timeout) {
        sleep(sleepMillis);
    }

    public void slow6(long sleepMillis, TimeUnit unit) {
        sleep(sleepMillis);
    }

    private void sleep(long millis) {
        AbstractTestLocalBroker.sleep(millis);
    }
}
