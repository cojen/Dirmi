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

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class SessionStrategy {
    protected final Environment env;

    protected volatile Session localSession;
    protected volatile Session remoteSession;

    protected volatile Object localServer;
    protected volatile Object remoteServer;

    protected SessionStrategy(final Environment env) throws Exception {
        this.env = env;
    }

    public void tearDown() throws Exception {
        if (env != null) {
            env.executor().execute(new Runnable() {
                public void run() {
                    if (remoteSession != null) {
                        try {
                            remoteSession.close();
                        } catch (IOException e) {
                        }
                        remoteSession = null;
                    }
                }
            });
        }

        if (localSession != null) {
            localSession.close();
            localSession = null;
        }
    }
}
