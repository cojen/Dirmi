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

import java.util.Comparator;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteSortedMap<K, V> extends RemoteMap<K, V>, Remote {
    Comparator<? super K> comparator() throws RemoteException;

    RemoteSortedMap<K,V> subMap(K fromKey, K toKey) throws RemoteException;

    RemoteSortedMap<K,V> headMap(K toKey) throws RemoteException;

    RemoteSortedMap<K,V> tailMap(K fromKey) throws RemoteException;

    K firstKey() throws RemoteException;

    K lastKey() throws RemoteException;
}