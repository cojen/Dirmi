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

import java.util.concurrent.TimeUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteBlockingQueue<E> extends RemoteQueue<E>, Remote {
    boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException, RemoteException;

    E poll(long timeout, TimeUnit unit) throws InterruptedException, RemoteException;

    E take() throws InterruptedException, RemoteException;

    void put(E o) throws InterruptedException, RemoteException;

    int remainingCapacity() throws RemoteException;

    int drainTo(Collection<? super E> c) throws RemoteException;

    int drainTo(RemoteCollection<? super E> c) throws RemoteException;
    
    int drainTo(Collection<? super E> c, int maxElements) throws RemoteException;

    int drainTo(RemoteCollection<? super E> c, int maxElements) throws RemoteException;
}
