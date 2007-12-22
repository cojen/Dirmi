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

import java.util.Collection;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteCollection<E> extends RemoteIterable<E>, Remote {
    int size() throws RemoteException;

    boolean isEmpty() throws RemoteException;

    boolean contains(Object o) throws RemoteException;

    Object[] toArray() throws RemoteException;

    <T> T[] toArray(T[] a) throws RemoteException;

    boolean add(E o) throws RemoteException;

    boolean remove(Object o) throws RemoteException;

    boolean containsAll(Collection<?> c) throws RemoteException;

    boolean containsAll(RemoteCollection<?> c) throws RemoteException;

    boolean addAll(Collection<? extends E> c) throws RemoteException;

    boolean addAll(RemoteCollection<? extends E> c) throws RemoteException;

    boolean removeAll(Collection<?> c) throws RemoteException;

    boolean removeAll(RemoteCollection<?> c) throws RemoteException;

    boolean retainAll(Collection<?> c) throws RemoteException;

    boolean retainAll(RemoteCollection<?> c) throws RemoteException;

    void clear() throws RemoteException;
}
