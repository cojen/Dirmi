/*
 *  Copyright 2010 Brian S O'Neill
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

package rpcPerf.tcp;

import java.io.*;
import java.net.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TcpServer implements rpcPerf.Server {
    public static void main(String[] args) throws Exception {
        new TcpServer(Integer.parseInt(args[0])).start();
    }

    private final int mPort;

    volatile ServerSocket mSS;

    public TcpServer(int port) {
        mPort = port;
    }

    public void start() throws Exception {
        mSS = new ServerSocket(mPort);

        new Thread() {
            public void run() {
                ServerSocket ss = mSS;

                try {
                    while (true) {
                        final Socket s = ss.accept();
                        new Thread() {
                            public void run() {
                                try {
                                    InputStream in = s.getInputStream();
                                    OutputStream out = s.getOutputStream();
                                    while (true) {
                                        int b = in.read();
                                        if (b == 0) {
                                            break;
                                        }
                                        out.write(1);
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                try {
                                    s.close();
                                } catch (Exception e2) {
                                }
                            }
                        }.start();
                    }
                } catch (IOException e) {
                    if (mSS != null) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();
    }

    public void stop() throws Exception {
        ServerSocket ss = mSS;
        mSS = null;
        if (ss != null) {
            ss.close();
        }
    }
}
