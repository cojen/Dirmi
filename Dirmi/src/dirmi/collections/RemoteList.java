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
public interface RemoteList<E> extends RemoteCollection<E>, Remote {
    boolean addAll(int index, Collection<? extends E> c) throws RemoteException;

    boolean addAll(int index, RemoteCollection<? extends E> c) throws RemoteException;

    E get(int index) throws RemoteException;

    E set(int index, E element) throws RemoteException;

    void add(int index, E element) throws RemoteException;

    E remove(int index) throws RemoteException;

    int indexOf(Object o) throws RemoteException;

    int lastIndexOf(Object o) throws RemoteException;

    RemoteListIterator<E> listIterator() throws RemoteException;

    RemoteListIterator<E> listIterator(int index) throws RemoteException;

    RemoteList<E> subList(int fromIndex, int toIndex) throws RemoteException;
}
