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

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestServer3 {
    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(Integer.parseInt(args[0]));
        while (true) {
            Socket s = ss.accept();
            System.out.println("established: " + s);
            System.out.println(s.getReceiveBufferSize());
            long start = System.currentTimeMillis();
            long size = 0;
            byte[] buf = new byte[8192];
            try {
                InputStream in = s.getInputStream();
                int amt;
                while ((amt = in.read(buf)) > 0) {
                    size += amt;
                }
                s.close();
            } catch (IOException e) {
                //e.printStackTrace(System.out);
            }
            long end = System.currentTimeMillis();
            System.out.println("Transfer time: " + (end - start) + ", size: " + size);
        }
    }
}
