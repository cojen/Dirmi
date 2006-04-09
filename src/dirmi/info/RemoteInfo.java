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

package dirmi.info;

import java.io.Serializable;
import java.util.Set;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteInfo extends Serializable {
    /**
     * Returns the name of the remote interface described by this RemoteInfo,
     * which is the same as the interface name.
     */
    String getName();

    /**
     * Returns a number which uniquely identifies the remote interface.
     */
    int getRemoteID();

    /**
     * Returns all remote methods in an unmodifiable set.
     */
    Set<? extends RemoteMethod> getRemoteMethods();

    /**
     * Returns all remote methods by the given name in an unmodifiable set. If
     * no matches, set is empty.
     *
     * @param name method name to query
     */
    Set<? extends RemoteMethod> getRemoteMethods(String name);

    RemoteMethod getRemoteMethod(String name, RemoteParameter... params)
        throws NoSuchMethodException;

    RemoteMethod getRemoteMethod(short methodID) throws NoSuchMethodException;
}
