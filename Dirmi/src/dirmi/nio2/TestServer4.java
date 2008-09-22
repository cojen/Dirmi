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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import dirmi.core.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestServer4 implements StreamListener {
    public static void main(String[] args) throws Exception {
        SocketAddress address = new InetSocketAddress(Integer.parseInt(args[0]));
        ThreadPool pool = new ThreadPool(100, false, "dirmi");
        SocketStreamProcessor processor = new SocketStreamProcessor(pool);
        StreamAcceptor acceptor = processor.newAcceptor(address);
        System.out.println(acceptor);

        new TestServer4(acceptor);
    }

    private final StreamAcceptor mAcceptor;

    private TestServer4(StreamAcceptor acceptor) {
        mAcceptor = acceptor;
        acceptor.accept(this);
    }

    public void established(final StreamChannel channel) {
        System.out.println("Accepted: " + channel);
        mAcceptor.accept(new TestServer4(mAcceptor));

        class Task implements StreamTask {
            public void closed() {
                System.out.println("Closed");
            }

            public void closed(IOException e) {
                System.out.println("Closed");
                e.printStackTrace(System.out);
            }

            public void run() {
                try {
                    InputStream in = channel.getInputStream();
                    while (true) {
                        int c = in.read();
                        //System.out.print((char) c);
                        if (c == '\n') {
                            if (in.available() <= 0) {
                                channel.executeWhenReadable(this);
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                }
            }
        };

        new Task().run();
    }

    public void failed(IOException e) {
        System.out.println("Failed");
        e.printStackTrace(System.out);
    }
}
