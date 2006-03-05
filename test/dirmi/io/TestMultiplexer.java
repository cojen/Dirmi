/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi.io;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestMultiplexer extends TestCase {
    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public static TestSuite suite() {
        return new TestSuite(TestMultiplexer.class);
    }

    private Connection mServerCon;
    private Connection mClientCon;

    public TestMultiplexer(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        final Connector connector = new PipedConnector();
        Thread t = new Thread() {
            public void run() {
                try {
                    mServerCon = connector.accept();
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                }
            }
        };
        t.start();
        mClientCon = connector.connect();
        t.join();
    }

    protected void tearDown() throws Exception {
        mServerCon.close();
        mServerCon = null;
        mClientCon.close();
        mClientCon = null;
    }

    public void testBasic() throws Exception {
        Thread t = new Thread() {
            public void run() {
                try {
                    Connector connector = new Multiplexer(mServerCon);
                    Connection con = connector.accept();
                    int c = con.getInputStream().read();
                    assertEquals('Q', c);
                    con.getOutputStream().write('A');
                    con.close();
                    connector.accept();
                } catch (InterruptedIOException e) {
                    //
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                }
            }
        };
        t.start();

        Connector connector = new Multiplexer(mClientCon);
        Thread acceptor = new DummyAcceptor(connector);
        acceptor.start();

        Connection con = connector.connect();
        OutputStream out = con.getOutputStream();
        out.write('Q');
        out.flush();
        int c = con.getInputStream().read();
        assertEquals('A', c);

        t.interrupt();
        acceptor.interrupt();
    }

    private class DummyAcceptor extends Thread {
        private final Connector mConnector;

        DummyAcceptor(Connector connector) {
            mConnector = connector;
        }

        public void run() {
            try {
                while (true) {
                    mConnector.accept(0);
                }
            } catch (IOException e) {
                //
            } catch (Exception e) {
                e.printStackTrace();
                fail();
            }
        }
    }
}
