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

import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteMap<K, V> extends Remote {
    int size() throws RemoteException;

    boolean isEmpty() throws RemoteException;

    boolean containsKey(Object key) throws RemoteException;

    boolean containsValue(Object value) throws RemoteException;

    V get(Object key) throws RemoteException;

    V put(K key, V value) throws RemoteException;

    V remove(Object key) throws RemoteException;

    void putAll(Map<? extends K, ? extends V> t) throws RemoteException;

    void putAll(RemoteMap<? extends K, ? extends V> t) throws RemoteException;

    void clear() throws RemoteException;

    RemoteSet<K> keySet() throws RemoteException;

    RemoteCollection<V> values() throws RemoteException;

    RemoteSet<RemoteMap.Entry<K, V>> entrySet() throws RemoteException;

    interface Entry<K,V> extends Remote {
        K getKey() throws RemoteException;

        V getValue() throws RemoteException;

        V setValue(V value) throws RemoteException;
    }
}
