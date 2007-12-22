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
public interface RemoteNavigableMap<K, V> extends RemoteSortedMap<K, V>, Remote {
    RemoteMap.Entry<K,V> lowerEntry(K key) throws RemoteException;

    K lowerKey(K key) throws RemoteException;

    RemoteMap.Entry<K,V> floorEntry(K key) throws RemoteException;

    K floorKey(K key) throws RemoteException;

    RemoteMap.Entry<K,V> ceilingEntry(K key) throws RemoteException;

    K ceilingKey(K key) throws RemoteException;

    RemoteMap.Entry<K,V> higherEntry(K key) throws RemoteException;

    K higherKey(K key) throws RemoteException;

    RemoteMap.Entry<K,V> firstEntry() throws RemoteException;

    RemoteMap.Entry<K,V> lastEntry() throws RemoteException;

    RemoteMap.Entry<K,V> pollFirstEntry() throws RemoteException;

    RemoteMap.Entry<K,V> pollLastEntry() throws RemoteException;

    RemoteSet<K> descendingKeySet() throws RemoteException;

    RemoteSet<RemoteMap.Entry<K, V>> descendingEntrySet() throws RemoteException;

    RemoteNavigableMap<K,V> navigableSubMap(K fromKey, K toKey) throws RemoteException;

    RemoteNavigableMap<K,V> navigableHeadMap(K toKey) throws RemoteException;

    RemoteNavigableMap<K,V> navigableTailMap(K fromKey) throws RemoteException;
}
