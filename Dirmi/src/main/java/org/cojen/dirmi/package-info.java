/*
 *  Copyright 2009-2010 Brian S O'Neill
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

/**
 * Public API for Dirmi. Start by creating an {@link
 * org.cojen.dirmi.Environment}, and then create a {@link
 * org.cojen.dirmi.SessionAcceptor} or {@link
 * org.cojen.dirmi.SessionConnector}. Here is a very simple example, starting
 * with the remote interface:
 *
 * <pre>
 * import java.rmi.Remote;
 * import java.rmi.RemoteException;
 *
 * public interface HelloDirmi extends Remote {
 *     void greetings(String name) throws RemoteException;
 * }
 * </pre>
 *
 * The server-side implementation looks like:
 *
 * <pre>
 * import org.cojen.dirmi.Environment;
 *
 * public class HelloDirmiServer implements HelloDirmi {
 *     public static void main(String[] args) throws Exception {
 *         Environment env = new Environment();
 *         HelloDirmi server = new HelloDirmiServer();
 *         int port = 1234;
 *         env.newSessionAcceptor(port).acceptAll(server);
 *     }
 *
 *     public void greetings(String name) {
 *         System.out.println("Hello " + name);
 *     }
 * }
 * </pre>
 *
 * The client is:
 *
 * <pre>
 * import org.cojen.dirmi.Environment;
 * import org.cojen.dirmi.Session;
 *
 * public class HelloDirmiClient {
 *     public static void main(String[] args) throws Exception {
 *         Environment env = new Environment();
 *         String host = args[0];
 *         int port = 1234;
 *         Session session = env.newSessionConnector(host, 1234).connect();
 *         HelloDirmi client = (HelloDirmi) session.receive();
 *
 *         client.greetings("Dirmi");
 *
 *         env.close();
 *     }
 * }
 * </pre>
 */
package org.cojen.dirmi;
