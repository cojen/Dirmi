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

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteAsyncServer implements RemoteAsync {
    public void runCommand(int sleepMillis) {
        sleep(sleepMillis);
    }

    public void runCommand2(int sleepMillis, Callback callback) {
        sleep(sleepMillis);
        done(callback, sleepMillis);
    }

    public void runCommand3(int sleepMillis, Callback callback) {
        sleep(sleepMillis);
        done(callback, sleepMillis);
    }

    public void runCommand4(int sleepMillis, Callback callback) {
        sleep(sleepMillis);
        done(callback, sleepMillis);
    }

    public void ack() {
        // Do nothing.
    }

    public void sync() {
        // Do nothing.
    }

    public void data(int[] data) {
        // Do nothing.
    }

    private void done(Callback callback, int value) {
        try {
            callback.done(value);
        } catch (Exception e) {
            // Ignore.
        }
    }

    private void sleep(long millis) {
        AbstractTestSuite.sleep(millis);
    }
}
