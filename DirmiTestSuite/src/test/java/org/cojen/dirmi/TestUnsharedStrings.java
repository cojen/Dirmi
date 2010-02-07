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

import java.util.Arrays;
import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestUnsharedStrings extends AbstractTestSuite {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestUnsharedStrings.class.getName());
    }

    protected SessionStrategy createSessionStrategy(Environment env) throws Exception {
        return new PipedSessionStrategy(env, null, new RemoteFaceServer());
    }

    @Test
    public void empty() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;
        server.receive("");
        assertEquals("", server.getMessage());
    }

    @Test
    public void mediumSize() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;

        char[] chars = new char[200];
        Arrays.fill(chars, 'a');
        String str = new String(chars);

        server.receive(str);
        assertEquals(str, server.getMessage());
    }

    @Test
    public void largeSize() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;

        char[] chars = new char[20000];
        Arrays.fill(chars, 'a');
        String str = new String(chars);

        server.receive(str);
        assertEquals(str, server.getMessage());
    }

    @Test
    public void extraLargeSize() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;

        char[] chars = new char[2100000];
        Arrays.fill(chars, 'a');
        String str = new String(chars);

        server.receive(str);
        assertEquals(str, server.getMessage());
    }

    /*
    @Test
    public void extraExtraLargeSize() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;

        char[] chars = new char[270000000];
        Arrays.fill(chars, 'a');
        String str = new String(chars);

        server.receive(str);
        assertEquals(str, server.getMessage());
    }
    */

    @Test
    public void randomString() throws Exception {
        RemoteFace server = (RemoteFace) sessionStrategy.remoteServer;

        Random rnd = new Random(35982492384L);
        char[] chars = new char[100000];
        for (int i=0; i<chars.length; i++) {
            chars[i] = (char) rnd.nextInt();
        }
        String str = new String(chars);

        server.receive(str);
        assertEquals(str, server.getMessage());
    }
}
