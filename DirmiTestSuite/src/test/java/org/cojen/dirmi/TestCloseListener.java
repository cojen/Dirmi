/*
 *  Copyright 2011 Brian S O'Neill
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
public class TestCloseListener extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestCloseListener.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteFaceServer());
    }

    @Test
    public void testExplicitClose() throws Exception {
        RemoteFace face = (RemoteFace) sessionStrategy.remoteServer;

        Listener listener1 = new Listener();
        Listener listener2 = new Listener();

        sessionStrategy.localSession.addCloseListener(listener1);
        sessionStrategy.remoteSession.addCloseListener(listener2);

        sessionStrategy.localSession.close();

        sleep(1000);

        assertNotNull(listener1.mLink);
        assertFalse(listener1.mLink instanceof Session);
        assertNotNull(listener2.mLink);
        assertFalse(listener2.mLink instanceof Session);

        assertEquals(SessionCloseListener.Cause.LOCAL_CLOSE, listener1.mCause);
        assertEquals(SessionCloseListener.Cause.REMOTE_CLOSE, listener2.mCause);

        assertEquals(listener1.mLink.getLocalAddress(),
                     sessionStrategy.localSession.getLocalAddress());
        assertEquals(listener1.mLink.getRemoteAddress(),
                     sessionStrategy.localSession.getRemoteAddress());

        assertEquals(listener2.mLink.getLocalAddress(),
                     sessionStrategy.remoteSession.getLocalAddress());
        assertEquals(listener2.mLink.getRemoteAddress(),
                     sessionStrategy.remoteSession.getRemoteAddress());

        assertNotNull(listener1.mThread);
        assertTrue(Thread.currentThread().equals(listener1.mThread));
        assertNotNull(listener2.mThread);
        assertFalse(Thread.currentThread().equals(listener2.mThread));
    }

    @Test
    public void testMultipleListeners() throws Exception {
        RemoteFace face = (RemoteFace) sessionStrategy.remoteServer;

        Listener listener1 = new Listener();
        Listener listener2 = new Listener();

        sessionStrategy.localSession.addCloseListener(listener1);
        sessionStrategy.localSession.addCloseListener(listener2);

        sessionStrategy.localSession.close();

        sleep(1000);

        assertNotNull(listener1.mLink);
        assertFalse(listener1.mLink instanceof Session);
        assertNotNull(listener2.mLink);
        assertFalse(listener2.mLink instanceof Session);

        assertEquals(SessionCloseListener.Cause.LOCAL_CLOSE, listener1.mCause);
        assertEquals(SessionCloseListener.Cause.LOCAL_CLOSE, listener2.mCause);

        assertEquals(listener1.mLink.getLocalAddress(),
                     sessionStrategy.localSession.getLocalAddress());
        assertEquals(listener1.mLink.getRemoteAddress(),
                     sessionStrategy.localSession.getRemoteAddress());

        assertEquals(listener2.mLink.getLocalAddress(),
                     sessionStrategy.localSession.getLocalAddress());
        assertEquals(listener2.mLink.getRemoteAddress(),
                     sessionStrategy.localSession.getRemoteAddress());

        assertNotNull(listener1.mThread);
        assertTrue(Thread.currentThread().equals(listener1.mThread));
        assertNotNull(listener2.mThread);
        assertTrue(Thread.currentThread().equals(listener2.mThread));
    }

    @Test
    public void testAlreadyClosed() throws Exception {
        RemoteFace face = (RemoteFace) sessionStrategy.remoteServer;

        sessionStrategy.localSession.close();

        Listener listener = new Listener();
        sessionStrategy.localSession.addCloseListener(listener);

        sleep(1000);

        assertNotNull(listener.mLink);
        assertFalse(listener.mLink instanceof Session);

        assertEquals(SessionCloseListener.Cause.LOCAL_CLOSE, listener.mCause);

        assertEquals(listener.mLink.getLocalAddress(),
                     sessionStrategy.localSession.getLocalAddress());
        assertEquals(listener.mLink.getRemoteAddress(),
                     sessionStrategy.localSession.getRemoteAddress());

        assertNotNull(listener.mThread);
        assertFalse(Thread.currentThread().equals(listener.mThread));
    }

    static class Listener implements SessionCloseListener {
        volatile Link mLink;
        volatile SessionCloseListener.Cause mCause;
        volatile Thread mThread;

        @Override
        public void closed(Link sessionLink, Cause cause) {
            mLink = sessionLink;
            mCause = cause;
            mThread = Thread.currentThread();
        }
    }
}
