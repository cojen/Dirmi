/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * Bidirectional remote method invocation. For launching a server, {@link Environment#create
 * create} an {@code Environment}, {@link Environment#export export} a remote object, and start
 * {@link Environment#acceptAll accepting} connections. For connecting a client, create an
 * {@code Environment}, {@link Environment#connect connect} to the server, and obtain the
 * {@link Session#root root} object. Here is a very simple example, starting with the remote
 * interface:
 *
 * {@snippet lang="java" :
 * import org.cojen.dirmi.Remote;
 * import org.cojen.dirmi.RemoteException;
 *
 * public interface HelloDirmi extends Remote {
 *     void greetings(String name) throws RemoteException;
 * }
 * }
 *
 * The server-side implementation looks like this:
 *
 * {@snippet lang="java" :
 * import java.net.ServerSocket;
 *
 * import org.cojen.dirmi.Environment;
 *
 * public class HelloDirmiServer implements HelloDirmi {
 *     public static void main(String[] args) throws Exception {
 *         Environment env = Environment.create();
 *         env.export("main", new HelloDirmiServer());
 *         env.acceptAll(new ServerSocket(1234));
 *     }
 *
 *     @Override
 *     public void greetings(String name) {
 *         System.out.println("Hello " + name);
 *     }
 * }
 * }
 *
 * And here is the client-side implementation:
 *
 * {@snippet lang="java" :
 * import org.cojen.dirmi.Environment;
 * import org.cojen.dirmi.Session;
 *
 * public class HelloDirmiClient {
 *     public static void main(String[] args) throws Exception {
 *         Environment env = Environment.create();
 *         String host = args[0];
 *         int port = 1234;
 *         Session<HelloDirmi> session = env.connect(HelloDirmi.class, "main", host, port);
 *         HelloDirmi client = session.root();
 *
 *         client.greetings("Dirmi");
 *
 *         env.close();
 *     }
 * }
 * }
 *
 * @see Environment
 */
package org.cojen.dirmi;
