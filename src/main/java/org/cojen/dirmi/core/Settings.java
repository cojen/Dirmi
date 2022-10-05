/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class Settings implements Cloneable {
    int reconnectDelayMillis;
    int pingTimeoutMillis;
    int idleConnectionMillis;

    Settings() {
        reconnectDelayMillis =  1_000;
        pingTimeoutMillis    =  2_000;
        idleConnectionMillis = 60_000;
    }

    Settings withReconnectDelayMillis(int millis) {
        var settings = copy();
        settings.reconnectDelayMillis = millis;
        return settings;
    }

    Settings withPingTimeoutMillis(int millis) {
        var settings = copy();
        settings.pingTimeoutMillis = millis;
        return settings;
    }

    Settings withIdleConnectionMillis(int millis) {
        var settings = copy();
        settings.idleConnectionMillis = millis;
        return settings;
    }

    Settings copy() {
        try {
            return (Settings) clone();
        } catch (CloneNotSupportedException e) {
            throw CoreUtils.rethrow(e);
        }
    }
}
