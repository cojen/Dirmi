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

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestSocketTimeouts extends TestTimeouts {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestSocketTimeouts.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new SocketSessionStrategy(env, null, new RemoteTimeoutsServer());
    }

    @Test
    public void recycling() throws Exception {
        RemoteTimeouts remote = (RemoteTimeouts) sessionStrategy.remoteServer;

        // Ensure that socket recycling code gets exercised, but does not prove
        // that it is. Any bugs in the implementation might surface when
        // subsequent remote calls are made.
        for (int i=0; i<10; i++) {
            try {
                remote.slow(2000);
                fail();
            } catch (RemoteTimeoutException e) {
                assertEquals("Timed out after 1000 milliseconds", e.getMessage());
            }
        }
    }
}
