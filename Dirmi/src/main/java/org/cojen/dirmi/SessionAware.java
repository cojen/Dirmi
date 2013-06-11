/*
 *  Copyright 2011 Brian S O'Neill
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

package org.cojen.dirmi;

/**
 * Remote objects implementing this interface can access the current session {@link Link
 * link} which is making a remote method invocation. To access the link, call {@link
 * SessionAccess#current} from within a remote method implementation. Because extra
 * overhead is required for supporting this feature, it is not enabled by default.
 *
 * <p><pre>
 * public class RemoteLoginServer implements RemoteLogin, <b>SessionAware</b> {
 *     RemoteApp login(String username, char[] password) throws AuthException {
 *         Link session = <b>SessionAccess.current();</b>
 *         authCheck(session.getRemoteAddress(), username, password);
 *         ...
 *     }
 *
 *     ...
 * }
 * </pre>
 *
 * @author Brian S O'Neill
 * @see SessionAccess
 */
public interface SessionAware {
}
