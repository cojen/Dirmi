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

package dirmi.nio2;

import java.io.IOException;

import java.nio.ByteBuffer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import dirmi.core.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestClient implements MessageReceiver<byte[]> {
    public static void main(String[] args) throws Exception {
        SocketAddress address = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
        ThreadPool pool = new ThreadPool(100, false, "dirmi");
        SocketMessageProcessor processor = new SocketMessageProcessor(pool);
        MessageConnector connector = processor.newConnector(address);
        System.out.println(connector);

        MessageConnection con = connector.connect(new TestClient());
        System.out.println(con);

        int count = 0;
        while (true) {
            byte[] message = ("" + count + "/hello " + new org.joda.time.DateTime() + "@" + count).getBytes();
            con.send(ByteBuffer.wrap(message));
            count++;
            System.out.println("sent message " + count);
            Thread.sleep(10);
            /*
            if (count > 2) {
                con.close();
            }
            */
        }
    }

    private TestClient() {
    }

    public void established(MessageConnection con) {
        System.out.println("Connected: " + con);
    }

    public byte[] receive(byte[] message, int totalSize, int offset, ByteBuffer buffer) {
        if (message == null) {
            message = new byte[totalSize];
        }
        buffer.get(message, offset, buffer.remaining());
        return message;
    }

    public void process(byte[] message, MessageConnection con) {
        System.out.println("Received: " + new String(message));
    }

    public void closed() {
        System.out.println("Closed");
    }

    public void closed(IOException e) {
        System.out.println("Closed");
        e.printStackTrace(System.out);
    }
}
