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

import java.io.InputStream;
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
public class TestServer2 implements MessageListener {
    public static void main(String[] args) throws Exception {
        SocketAddress address = new InetSocketAddress(Integer.parseInt(args[0]));
        ThreadPool pool = new ThreadPool(100, false, "dirmi");
        SocketMessageProcessor processor = new SocketMessageProcessor(pool);
        MessageAcceptor acceptor = processor.newAcceptor(address);
        System.out.println(acceptor);

        new TestServer2(acceptor);
    }

    private final MessageAcceptor mAcceptor;

    private TestServer2(MessageAcceptor acceptor) {
        mAcceptor = acceptor;
        acceptor.accept(this);
    }

    public void established(final MessageConnection messCon) {
        System.out.println("Accepted: " + messCon);
        mAcceptor.accept(new TestServer2(mAcceptor));

        final StreamBroker broker;
        try {
            broker = new MultiplexedStreamBroker(messCon);
        } catch (IOException e) {
            e.printStackTrace(System.out);
            return;
        }

        class Listener implements StreamListener {
            public void established(StreamConnection con) {
                broker.accept(new Listener());

                System.out.println("established: " + con);
                try {
                    InputStream in = con.getInputStream();
                    int c;
                    while ((c = in.read()) >= 0) {
                        System.out.print((char) c);
                    }
                    System.out.println();
                    con.close();
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                }
            }

            public void failed(IOException e) {
                System.out.println("Failed");
                e.printStackTrace(System.out);
            }
        }

        broker.accept(new Listener());
    }

    public void failed(IOException e) {
        System.out.println("Failed");
        e.printStackTrace(System.out);
    }
}
