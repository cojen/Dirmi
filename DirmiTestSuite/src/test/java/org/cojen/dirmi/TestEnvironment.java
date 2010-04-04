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

import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestEnvironment {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestEnvironment.class.getName());
    }

    private Environment mEnv;

    @Before
    public void setup() {
        mEnv = new Environment();
    }

    @After
    public void tearDown() throws Exception {
        mEnv.close();
    }

    @Test
    public void sessionPair() throws Exception {
        Session[] pair = mEnv.newSessionPair();
        pair[0].send("hello");
        assertEquals("hello", pair[1].receive());
        pair[1].send("world");
        assertEquals("world", pair[0].receive());
    }

    @Test
    public void sessionTimeout() throws Exception {
        Session[] pair = mEnv.newSessionPair();
        try {
            pair[1].receive(1, TimeUnit.SECONDS);
            fail();
        } catch (RemoteTimeoutException e) {
        }
        try {
            pair[0].send("hello");
            fail();
        } catch (RemoteException e) {
        }
        try {
            pair[1].send("hello");
            fail();
        } catch (RemoteException e) {
        }
        try {
            pair[0].receive();
            fail();
        } catch (RemoteException e) {
        }
        try {
            pair[1].receive();
            fail();
        } catch (RemoteException e) {
        }

        pair = mEnv.newSessionPair();
        pair[0].send("hello");
        try {
            pair[0].send("hello", 1, TimeUnit.SECONDS);
            fail();
        } catch (RemoteTimeoutException e) {
        }
        try {
            System.out.println(pair[1].receive());
            fail();
        } catch (RemoteException e) {
        }
    }
}
