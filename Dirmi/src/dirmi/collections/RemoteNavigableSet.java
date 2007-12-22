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
public interface RemoteNavigableSet<E> extends RemoteSortedSet<E>, Remote {
    E lower(E e) throws RemoteException;

    E floor(E e) throws RemoteException;

    E ceiling(E e) throws RemoteException;

    E higher(E e) throws RemoteException;

    E pollFirst() throws RemoteException;

    E pollLast() throws RemoteException;

    RemoteIterator<E> iterator() throws RemoteException;

    RemoteIterator<E> descendingIterator() throws RemoteException;

    RemoteNavigableSet<E> navigableSubSet(E fromElement, E toElement) throws RemoteException;

    RemoteNavigableSet<E> navigableHeadSet(E toElement) throws RemoteException;

    RemoteNavigableSet<E> navigableTailSet(E fromElement) throws RemoteException;
}
