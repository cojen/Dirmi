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
public class TestClient3 {
    public static void main(String[] args) throws Exception {
        while (true) {
            //System.out.println("Sleeping");
            //Thread.sleep(1000);

            Socket s = new Socket(args[0], Integer.parseInt(args[1]));
            InputStream in = new FileInputStream(args[2]);
            byte[] buf = new byte[8192];
            System.out.println(s);
            System.out.println(s.getSendBufferSize());
            OutputStream out = s.getOutputStream();
            int amt;
            while ((amt = in.read(buf)) > 0) {
                out.write(buf, 0, amt);
            }
            out.close();
            in.close();
        }
    }
}
