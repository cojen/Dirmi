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

import java.io.*;

import java.nio.ByteBuffer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.joda.time.DateTime;

import dirmi.core.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestClient2 {
    public static void main(String[] args) throws Exception {
        SocketAddress address = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
        ThreadPool pool = new ThreadPool(100, true, "dirmi");
        SocketMessageProcessor processor = new SocketMessageProcessor(pool);
        MessageConnector connector = processor.newConnector(address);
        System.out.println(connector);

        MessageConnection messCon = connector.connect();
        System.out.println(messCon);

        StreamBroker broker = new MultiplexedStreamBroker(messCon);

        StreamConnection con = broker.connect();
        System.out.println(con);
        con.getOutputStream().write("hello world".getBytes());
        con.getOutputStream().close();

        if (args.length > 2) {
            while (true) {
                System.out.println("Sleeping");
                Thread.sleep(1000);

                InputStream in = new FileInputStream(args[2]);
                byte[] buf = new byte[8192];
                con = broker.connect();
                System.out.println(con);
                OutputStream out = con.getOutputStream();
                int amt;
                while ((amt = in.read(buf)) > 0) {
                    out.write(buf, 0, amt);
                }
                out.close();
                in.close();
            }
        }

        System.out.println("Sleeping");
        Thread.sleep(10000);
    }

    private TestClient2() {
    }
}
