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
public class TestDisposer extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestDisposer.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteDisposerServer());
    }

    @Test
    public void localDispose() throws Exception {
        RemoteDisposer server = (RemoteDisposer) sessionStrategy.remoteServer;
        assertFalse(server instanceof RemoteDisposerServer);

        server.doIt();
        server.doItAsync();
        server.doItAsync2();

        server.close();

        try {
            server.doIt();
            fail();
        } catch (NoSuchObjectException e) {
        }
        try {
            server.doItAsync();
            fail();
        } catch (NoSuchObjectException e) {
        }
        try {
            server.doItAsync2();
            fail();
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof NoSuchObjectException);
        }
    }

    @Test
    public void remoteDispose() throws Exception {
        RemoteDisposer server = (RemoteDisposer) sessionStrategy.remoteServer;

        class CB implements RemoteDisposer.Callback {
            volatile boolean unref;

            public void unref() {
                this.unref = true;
            }
        };

        CB cb = new CB();
        server.close(cb, 3000);

        try {
            server.doIt();
            fail();
        } catch (NoSuchObjectException e) {
        }

        sleep(1000);
        assertFalse(cb.unref);
        sleep(3000);
        assertTrue(cb.unref);
    }
}
