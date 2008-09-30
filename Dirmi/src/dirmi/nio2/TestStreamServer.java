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
import java.net.*;

import dirmi.core2.ThreadPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestStreamServer {
    public static void main(String[] args) throws Exception {
        final ThreadPool pool = new ThreadPool(100, false, "dirmi");
        final SocketStreamProcessor processor = new SocketStreamProcessor(pool);
        final StreamAcceptor acceptor = processor.newAcceptor
            (new InetSocketAddress(Integer.parseInt(args[0])));

        acceptor.accept(new StreamListener() {
            public void established(final StreamChannel channel) {
                acceptor.accept(this);

                pool.execute(new Runnable() {
                    public void run() {
                        try {
                            InputStream in = channel.getInputStream();
                            while (true) {
                                in.read();
                            }
                        } catch (IOException e) {
                            e.printStackTrace(System.out);
                        }
                    }
                });

                try {
                    OutputStream out = channel.getOutputStream();
                    while (true) {
                        out.write('a');
                    }
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                }
            }

            public void failed(IOException e) {
                System.out.println("failed");
                e.printStackTrace(System.out);
            }
        });
    }
}
