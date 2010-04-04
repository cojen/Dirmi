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

import java.io.IOException;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.io.Channel;
import org.cojen.dirmi.io.ChannelBroker;
import org.cojen.dirmi.io.CountingBroker;
import org.cojen.dirmi.io.IOExecutor;
import org.cojen.dirmi.io.PipedChannelBroker;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestAsyncMethods extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestAsyncMethods.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteAsyncServer()) {
            @Override
            protected ChannelBroker[] createBrokers(IOExecutor executor) throws IOException {
                // Use a larger buffer for testing eventual methods.
                ChannelBroker[] brokers = PipedChannelBroker.newPair(executor, 1000);
                return new ChannelBroker[] {new CountingBroker(brokers[0]),
                                            new CountingBroker(brokers[1])};
            }
        };
    }

    @Test
    public void runCommandNoCallback() throws Exception {
        RemoteAsync server = (RemoteAsync) sessionStrategy.remoteServer;
        assertFalse(server instanceof RemoteAsyncServer);

        server.runCommand(1);

        long start = System.nanoTime();
        server.runCommand(10000);
        long end = System.nanoTime();

        assertTrue((end - start) < 100L * 1000000);
    }

    @Test
    public void runCommandWithCallback() throws Exception {
        RemoteAsync server = (RemoteAsync) sessionStrategy.remoteServer;

        server.runCommand2(1, new CallbackImpl());

        CallbackImpl callback = new CallbackImpl();

        long start = System.nanoTime();
        server.runCommand2(2000, callback);
        long end = System.nanoTime();

        assertTrue((end - start) < 100L * 1000000);

        while (callback.value == 0) {
            sleep(100);
        }

        end = System.nanoTime();

        assertTrue((end - start) > 2000L * 1000000);

        assertEquals(2000, callback.value);
    }

    @Test
    public void runCommandEventual() throws Exception {
        RemoteAsync server = (RemoteAsync) sessionStrategy.remoteServer;

        // Execute command that flushes immediately just to ensure callback
        // interface is transported. Otherwise first use might put too much
        // into the output buffer.
        server.runCommand2(1, new CallbackImpl());

        // Sleep to allow ClassDescriptorCache to finish exchanges. Otherwise,
        // it might cause the channel with the eventual method to be flushed
        // and ruin the test.
        sleep(1000);

        CallbackImpl callback = new CallbackImpl();
        server.runCommand3(1, callback);

        sleep(1000);

        assertEquals(0, callback.value);

        CallbackImpl callback2 = new CallbackImpl();
        server.runCommand3(2, callback2);

        sleep(1000);

        assertEquals(0, callback.value);
        assertEquals(0, callback2.value);

        // Can only guarantee flush by flushing session.
        sessionStrategy.localSession.flush();

        sleep(1000);

        assertEquals(1, callback.value);
        assertEquals(2, callback2.value);

        // Also test auto-flush by flooding with commands.
        callback = new CallbackImpl();
        server.runCommand3(1, callback);

        sleep(1000);

        assertEquals(0, callback.value);

        for (int i=0; i<2000; i++) {
            // Use acknowledged methods to prevent thread growth.
            server.ack();
        }

        sleep(1000);

        assertEquals(1, callback.value);

        // Test again with asynchronous method.

        callback = new CallbackImpl();
        server.runCommand3(1, callback);

        sleep(1000);

        assertEquals(0, callback.value);

        for (int i=0; i<2000; i++) {
            server.sync();
        }

        sleep(1000);

        assertEquals(1, callback.value);

        // Finally, test with eventual method that floods stream.

        callback = new CallbackImpl();
        server.runCommand3(1, callback);

        sleep(1000);

        assertEquals(0, callback.value);

        int[] data = new int[2000];

        for (int i=0; i<20; i++) {
            server.data(data);
        }

        sleep(1000);

        assertEquals(1, callback.value);
    }

    @Test
    public void runCommandAcknowledged() throws Exception {
        RemoteAsync server = (RemoteAsync) sessionStrategy.remoteServer;

        // Verify that command was acknowledged by checking that bytes were
        // read by channel.

        CountingBroker broker =
            (CountingBroker) ((PipedSessionStrategy) sessionStrategy).localBroker;

        // Prime pump.
        server.runCommand2(1, new CallbackImpl());

        long startRead = broker.getBytesRead();
        long startWritten = broker.getBytesWritten();

        server.runCommand2(1, new CallbackImpl());

        long endRead = broker.getBytesRead();
        long endWritten = broker.getBytesWritten();

        // TODO: Possible concurrent activity problem with class descriptors.
        assertEquals(startRead, endRead);
        assertTrue(startWritten < endWritten);

        // Now do the real test. Make sure bytes are read.

        startRead = broker.getBytesRead();
        startWritten = broker.getBytesWritten();

        server.runCommand4(1000, new CallbackImpl());

        endRead = broker.getBytesRead();
        endWritten = broker.getBytesWritten();

        assertTrue(startRead < endRead);
        assertTrue(startWritten < endWritten);
    }

    private static class CallbackImpl implements RemoteAsync.Callback {
        public volatile int value;

        public void done(int value) {
            this.value = value;
        }
    }
}
