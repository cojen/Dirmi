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

import java.io.Serializable;

import java.lang.reflect.Array;

import java.rmi.RemoteException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.SortedMap;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class Adapters {
    public static <E> RemoteIterator<E> remote(Iterator<E> i) {
        return new RemoteIteratorServer<Iterator<E>, E>(i);
    }

    public static <E> RemoteIterable<E> remote(Iterable<E> i) {
        return new RemoteIterableServer<Iterable<E>, E>(i);
    }

    public static <E> RemoteCollection<E> remote(Collection<E> c) {
        return new RemoteCollectionServer<Collection<E>, E>(c);
    }

    public static <E> RemoteSet<E> remote(Set<E> c) {
        return new RemoteSetServer<Set<E>, E>(c);
    }

    public static <E> RemoteSortedSet<E> remote(SortedSet<E> c) {
        return new RemoteSortedSetServer<SortedSet<E>, E>(c);
    }

    public static <E> RemoteListIterator<E> remote(ListIterator<E> i) {
        return new RemoteListIteratorServer<ListIterator<E>, E>(i);
    }

    public static <E> RemoteList<E> remote(List<E> c) {
        return new RemoteListServer<List<E>, E>(c);
    }

    public static <K, V> RemoteMap.Entry<K, V> remote(Map.Entry<K, V> c) {
        // Don't return server directly. Instead, wrap it in a serializable
        // object to eliminate round trip call to get key and value from entry.
        return new SerializedMapEntry<K, V>(c);
    }

    public static <K, V> RemoteMap<K, V> remote(Map<K, V> c) {
        return new RemoteMapServer<Map<K, V>, K, V>(c);
    }

    private static class RemoteIteratorServer<W extends Iterator<E>, E>
        implements RemoteIterator<E>
    {
        protected final W wrapped;

        RemoteIteratorServer(W i) {
            wrapped = i;
        }

        public boolean hasNext() {
            return wrapped.hasNext();
        }

        public E next() {
            return wrapped.next();
        }

        public E[] next(Class<? extends E> type, int max) {
            if (!wrapped.hasNext()) {
                return null;
            }
            List<E> list = new ArrayList<E>();
            while (--max >= 0) {
                list.add(wrapped.next());
                if (!wrapped.hasNext()) {
                    break;
                }
            }
            E[] array = (E[]) Array.newInstance(type, list.size());
            return list.toArray(array);
        }

        public void remove() {
            wrapped.remove();
        }
    }

    private static class RemoteIterableServer<W extends Iterable<E>, E>
        implements RemoteIterable<E>
    {
        protected final W wrapped;

        RemoteIterableServer(W i) {
            wrapped = i;
        }

        public RemoteIterator<E> iterator() {
            return remote(wrapped.iterator());
        }
    }

    private static class RemoteCollectionServer<W extends Collection<E>, E>
        extends RemoteIterableServer<W, E>
        implements RemoteCollection<E>
    {
        RemoteCollectionServer(W c) {
            super(c);
        }

        public int size() {
            return wrapped.size();
        }

        public boolean isEmpty() {
            return wrapped.isEmpty();
        }

        public boolean contains(Object o) {
            return wrapped.contains(o);
        }

        public Object[] toArray() {
            return wrapped.toArray();
        }

        public <T> T[] toArray(T[] a) {
            return wrapped.toArray(a);
        }

        public boolean add(E o) {
            return wrapped.add(o);
        }

        public boolean remove(Object o) {
            return remove(o);
        }

        public boolean containsAll(Collection<?> c) {
            return wrapped.containsAll(c);
        }

        public boolean containsAll(RemoteCollection<?> c) throws RemoteException {
            if (c instanceof RemoteCollectionServer) {
                return wrapped.containsAll((Collection) ((RemoteCollectionServer) c).wrapped);
            }
            RemoteIterator<?> i = c.iterator();
            while (i.hasNext()) {
                if (!contains(i.next())) {
                    return false;
                }
            }
            return true;
        }

        public boolean addAll(Collection<? extends E> c) {
            return wrapped.addAll(c);
        }

        public boolean addAll(RemoteCollection<? extends E> c) throws RemoteException {
            if (c instanceof RemoteCollectionServer) {
                return wrapped.addAll((Collection) ((RemoteCollectionServer) c).wrapped);
            }
            boolean modified = false;
            RemoteIterator<? extends E> i = c.iterator();
            while (i.hasNext()) {
                if (add(i.next())) {
                    modified = true;
                }
            }
            return modified;
        }

        public boolean removeAll(Collection<?> c) {
            return wrapped.removeAll(c);
        }

        public boolean removeAll(RemoteCollection<?> c) throws RemoteException {
            if (c instanceof RemoteCollectionServer) {
                return wrapped.removeAll((Collection) ((RemoteCollectionServer) c).wrapped);
            }
            boolean modified = false;
            Iterator<?> i = wrapped.iterator();
            while (i.hasNext()) {
                if (c.contains(i.next())) {
                    i.remove();
                    modified = true;
                }
            }
            return modified;
        }

        public boolean retainAll(Collection<?> c) {
            return wrapped.retainAll(c);
        }

        public boolean retainAll(RemoteCollection<?> c) throws RemoteException {
            if (c instanceof RemoteCollectionServer) {
                return wrapped.retainAll((Collection) ((RemoteCollectionServer) c).wrapped);
            }
            boolean modified = false;
            Iterator<E> i = wrapped.iterator();
            while (i.hasNext()) {
                if (!c.contains(i.next())) {
                    i.remove();
                    modified = true;
                }
            }
            return modified;
        }

        public void clear() {
            wrapped.clear();
        }
    }

    private static class RemoteSetServer<W extends Set<E>, E>
        extends RemoteCollectionServer<W, E>
        implements RemoteSet<E>
    {
        RemoteSetServer(W c) {
            super(c);
        }
    }

    private static class RemoteSortedSetServer<W extends SortedSet<E>, E>
        extends RemoteSetServer<W, E>
        implements RemoteSortedSet<E>
    {
        RemoteSortedSetServer(W c) {
            super(c);
        }

        public Comparator<? super E> comparator() {
            return wrapped.comparator();
        }

        public RemoteSortedSet<E> subSet(E fromElement, E toElement) {
            return remote(wrapped.subSet(fromElement, toElement));
        }

        public RemoteSortedSet<E> headSet(E toElement) {
            return remote(wrapped.headSet(toElement));
        }

        public RemoteSortedSet<E> tailSet(E fromElement) {
            return remote(wrapped.tailSet(fromElement));
        }

        public E first() {
            return wrapped.first();
        }

        public E last() {
            return wrapped.last();
        }
    }

    private static class RemoteListIteratorServer<W extends ListIterator<E>, E>
        extends RemoteIteratorServer<W, E>
        implements RemoteListIterator<E>
    {
        RemoteListIteratorServer(W c) {
            super(c);
        }

        public boolean hasPrevious() {
            return wrapped.hasPrevious();
        }

        public E previous() {
            return wrapped.previous();
        }

        public E[] previous(Class<? extends E> type, int max) {
            if (!wrapped.hasPrevious()) {
                return null;
            }
            List<E> list = new ArrayList<E>();
            while (--max >= 0) {
                list.add(wrapped.previous());
                if (!wrapped.hasPrevious()) {
                    break;
                }
            }
            E[] array = (E[]) Array.newInstance(type, list.size());
            return list.toArray(array);
        }

        public int nextIndex() {
            return wrapped.nextIndex();
        }

        public int previousIndex() {
            return wrapped.previousIndex();
        }

        public void set(E o) {
            wrapped.set(o);
        }

        public void add(E o) {
            wrapped.add(o);
        }
    }

    private static class RemoteListServer<W extends List<E>, E>
        extends RemoteCollectionServer<W, E>
        implements RemoteList<E>
    {
        RemoteListServer(W c) {
            super(c);
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            return wrapped.addAll(index, c);
        }

        public boolean addAll(int index, RemoteCollection<? extends E> c) throws RemoteException {
            if (c instanceof RemoteCollectionServer) {
                return wrapped.addAll(index, (Collection) ((RemoteCollectionServer) c).wrapped);
            }
            boolean modified = false;
            RemoteIterator<? extends E> i = c.iterator();
            while (i.hasNext()) {
                add(index++, i.next());
                modified = true;
            }
            return modified;
        }

        public E get(int index) {
            return wrapped.get(index);
        }

        public E set(int index, E element) {
            return wrapped.set(index, element);
        }

        public void add(int index, E element) {
            wrapped.add(index, element);
        }

        public E remove(int index) {
            return wrapped.remove(index);
        }

        public int indexOf(Object o) {
            return wrapped.indexOf(o);
        }

        public int lastIndexOf(Object o) {
            return wrapped.lastIndexOf(o);
        }

        public RemoteListIterator<E> listIterator() {
            return remote(wrapped.listIterator());
        }

        public RemoteListIterator<E> listIterator(int index) {
            return remote(wrapped.listIterator(index));
        }

        public RemoteList<E> subList(int fromIndex, int toIndex) {
            return remote(wrapped.subList(fromIndex, toIndex));
        }
    }

    private static class RemoteMapEntryServer<K, V>
        implements RemoteMap.Entry<K, V>
    {
        protected final Map.Entry<K, V> wrapped;

        RemoteMapEntryServer(Map.Entry<K, V> c) {
            wrapped = c;
        }

        public K getKey() {
            return wrapped.getKey();
        }

        public V getValue() {
            return wrapped.getValue();
        }

        public V setValue(V value) {
            return wrapped.setValue(value);
        }
    }
    
    private static class SerializedMapEntry<K, V>
        implements RemoteMap.Entry<K, V>,
                   Serializable
    {
        private final RemoteMap.Entry<K, V> server;
        private final K key;
        private V value;

        SerializedMapEntry(Map.Entry<K, V> c) {
            server = new RemoteMapEntryServer<K, V>(c);
            key = c.getKey();
            value = c.getValue();
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V value) throws RemoteException {
            V old = server.setValue(value);
            this.value = value;
            return old;
        }
    }

    private static class RemoteMapServer<W extends Map<K, V>, K, V>
        implements RemoteMap<K, V>
    {
        protected final W wrapped;

        RemoteMapServer(W i) {
            wrapped = i;
        }

        public int size() {
            return wrapped.size();
        }

        public boolean isEmpty() {
            return wrapped.isEmpty();
        }

        public boolean containsKey(Object key) {
            return wrapped.containsKey(key);
        }

        public boolean containsValue(Object value) {
            return wrapped.containsValue(value);
        }

        public V get(Object key) {
            return wrapped.get(key);
        }

        public V put(K key, V value) {
            return wrapped.put(key, value);
        }

        public V remove(Object key) {
            return wrapped.remove(key);
        }

        public void putAll(Map<? extends K, ? extends V> c) {
            wrapped.putAll(c);
        }

        public void putAll(RemoteMap<? extends K, ? extends V> c) throws RemoteException {
            if (c instanceof RemoteMapServer) {
                wrapped.putAll((Map) ((RemoteMapServer) c).wrapped);
            } else {
                RemoteIterator<? extends RemoteMap.Entry<? extends K, ? extends V>> it =
                    c.entrySet().iterator();
                while (it.hasNext()) {
                    RemoteMap.Entry<? extends K, ? extends V> e = it.next();
                    put(e.getKey(), e.getValue());
                }
            }
        }

        public void clear() {
            wrapped.clear();
        }

        public RemoteSet<K> keySet() {
            return remote(wrapped.keySet());
        }

        public RemoteCollection<V> values() {
            return remote(wrapped.values());
        }

        public RemoteSet<RemoteMap.Entry<K, V>> entrySet() {
            // FIXME return remote(wrapped.entrySet());
            return null;
        }
    }
}
