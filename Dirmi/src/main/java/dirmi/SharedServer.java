/*
 *  Copyright 2008 Brian S O'Neill
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

import java.io.IOException;

/**
 * Simple implementation of {@link SessionAcceptor} which always exports the
 * same server object. Any exceptions during session establishment are passed
 * to the thread's uncaught exception handler.
 *
 * @author Brian S O'Neill
 */
public class SharedServer implements SessionAcceptor {
    private final Object mServer;

    public SharedServer(Object server) {
        mServer = server;
    }

    public final Object createServer() {
        return mServer;
    }

    public void established(Session session) {
    }

    public void failed(IOException e) {
        Thread t = Thread.currentThread();
        try {
            t.getUncaughtExceptionHandler().uncaughtException(t, e);
        } catch (Throwable e2) {
            // I give up.
        }
        // Yield just in case exceptions are out of control.
        t.yield();
    }
}
