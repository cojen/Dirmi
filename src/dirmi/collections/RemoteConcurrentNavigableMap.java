/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.collections;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteConcurrentNavigableMap<K, V>
    extends RemoteConcurrentMap<K, V>, RemoteNavigableMap<K, V>, Remote
{
    RemoteConcurrentNavigableMap<K,V> navigableSubMap(K fromKey, K toKey) throws RemoteException;

    RemoteConcurrentNavigableMap<K,V> navigableHeadMap(K toKey) throws RemoteException;

    RemoteConcurrentNavigableMap<K,V> navigableTailMap(K fromKey) throws RemoteException;

    RemoteConcurrentNavigableMap<K,V> subMap(K fromKey, K toKey) throws RemoteException;

    RemoteConcurrentNavigableMap<K,V> headMap(K toKey) throws RemoteException;

    RemoteConcurrentNavigableMap<K,V> tailMap(K fromKey) throws RemoteException;
}
