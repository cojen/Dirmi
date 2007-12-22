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
public interface RemoteDeque<E> extends RemoteQueue<E>, Remote {
    void addFirst(E e) throws RemoteException;

    void addLast(E e) throws RemoteException;

    boolean offerFirst(E e) throws RemoteException;

    boolean offerLast(E e) throws RemoteException;

    E removeFirst() throws RemoteException;

    E removeLast() throws RemoteException;

    E pollFirst() throws RemoteException;

    E pollLast() throws RemoteException;

    E getFirst() throws RemoteException;

    E getLast() throws RemoteException;

    E peekFirst() throws RemoteException;

    E peekLast() throws RemoteException;

    boolean removeFirstOccurrence(Object o) throws RemoteException;

    boolean removeLastOccurrence(Object o) throws RemoteException;

    void push(E e) throws RemoteException;

    E pop() throws RemoteException;

    RemoteIterator<E> descendingIterator() throws RemoteException;
}
