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

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteBlockingDeque<E> extends RemoteBlockingQueue<E>, RemoteDeque<E>, Remote {
    void putFirst(E e) throws InterruptedException, RemoteException;

    void putLast(E e) throws InterruptedException, RemoteException;

    boolean offerFirst(E e, long timeout, TimeUnit unit)
        throws InterruptedException, RemoteException;

    boolean offerLast(E e, long timeout, TimeUnit unit)
        throws InterruptedException, RemoteException;

    E takeFirst() throws InterruptedException, RemoteException;

    E takeLast() throws InterruptedException, RemoteException;

    E pollFirst(long timeout, TimeUnit unit) throws InterruptedException, RemoteException;

    E pollLast(long timeout, TimeUnit unit) throws InterruptedException, RemoteException;
}
