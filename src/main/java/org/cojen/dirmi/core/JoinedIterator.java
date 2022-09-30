/*
 *  Copyright 2022 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Joins two iterators into an iterator that yields element pairs. Unmatched elements are
 * represented by null.
 *
 * @author Brian S O'Neill
 */
final class JoinedIterator<E extends Comparable<E>> implements Iterator<JoinedIterator.Pair<E>> {
    private final Iterator<E> aIt, bIt;

    private E aElement, bElement;
    private Pair<E> pair;

    JoinedIterator(Iterator<E> a, Iterator<E> b) {
        aIt = a;
        bIt = b;
    }

    JoinedIterator(Iterable<E> a, Iterable<E> b) {
        this(a.iterator(), b.iterator());
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean hasNext() {
        return pair != null || prepareNext();
    }

    private boolean prepareNext() {
        E ae = aElement;
        E be = bElement;

        if (ae == null && aIt.hasNext()) {
            aElement = ae = aIt.next();
        }

        if (be == null && bIt.hasNext()) {
            bElement = be = bIt.next();
        }

        int cmp;

        if (ae == null) {
            if (be == null) {
                return false;
            }
            cmp = 1;
        } else if (be == null) {
            cmp = -1;
        } else {
            cmp = ae.compareTo(be);
        }

        pair = new Pair<E>();

        if (cmp <= 0) {
            pair.a = ae;
            aElement = null;
        }
        if (cmp >= 0) {
            pair.b = be;
            bElement = null;
        }

        return true;
    }

    @Override
    public Pair<E> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        var pair = this.pair;
        this.pair = null;
        return pair;
    }

    static final class Pair<E extends Comparable<E>> {
        E a, b;
    }
}
