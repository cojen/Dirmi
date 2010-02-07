/*
 *  Copyright 2008-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.DataInput;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;

import java.util.Map;

import org.cojen.util.WeakCanonicalSet;
import org.cojen.util.WeakIdentityMap;
import org.cojen.util.WeakValuedHashMap;

import org.cojen.dirmi.util.Random;

/**
 * Storage and factory of unique identifiers.
 *
 * @author Brian S O'Neill
 */
abstract class IdentifierStore<I extends AbstractIdentifier> {
    private final WeakCanonicalSet<I> mIdentifiers;

    private final Map<Object, I> mObjectsToIdentifiers;
    private final Map<I, Object> mIdentifiersToObjects;

    public IdentifierStore() {
        mIdentifiers = new WeakCanonicalSet<I>();

        mObjectsToIdentifiers = new WeakIdentityMap<Object, I>();
        mIdentifiersToObjects = new WeakValuedHashMap<I, Object>();
    }

    /**
     * Returns a new or existing unique identifier for the given object. If
     * new, the given object is automatically registered with the identifier.
     *
     * @throws IllegalArgumentException if object is null
     */
    public synchronized I identify(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }
        I id = mObjectsToIdentifiers.get(obj);
        if (id == null) {
            do {
                id = newIdentifier(Random.randomLong());
            } while (mIdentifiersToObjects.containsKey(id));
            id = mIdentifiers.put(id);
            mObjectsToIdentifiers.put(obj, id);
            mIdentifiersToObjects.put(id, obj);
        }
        return id;
    }

    /**
     * Returns a new or existing unique identifier for the given object. If
     * new, the given object is automatically registered with the identifier.
     *
     * @param andMask optional mask to encode data in LSB of id
     * @param orMask optional mask to encode data in LSB of id
     * @throws IllegalArgumentException if object is null
     * @throws IllegalStateException if data cannot be encoded
     */
    /*
    synchronized I identify(Object obj, byte andMask, byte orMask) {
        if (obj == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }
        I id = mObjectsToIdentifiers.get(obj);
        if (id != null) {
            if ((id.getData() & andMask) != orMask) {
                throw new IllegalStateException("Unable to encode data in identifier");
            }
        } else {
            do {
                long v = Random.randomLong();
                v &= (~0xff) | (andMask & 0xff);
                v |= (orMask & 0xff);
                id = newIdentifier(v);
            } while (mIdentifiersToObjects.containsKey(id));
            id = mIdentifiers.put(id);
            mObjectsToIdentifiers.put(obj, id);
            mIdentifiersToObjects.put(id, obj);
        }
        return id;
    }
    */

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public I read(DataInput in) throws IOException {
        return canonicalIdentifier(newIdentifier(in.readLong()));
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public I read(InputStream in) throws IOException {
        int off = 0;
        int len = 8;
        byte[] buf = new byte[len];
        int amt;
        while ((amt = in.read(buf, off, len)) > 0) {
            off += amt;
            len -= amt;
        }
        if (len > 0) {
            throw new EOFException("Unable to fully read identifier");
        }
        long bits = (((long)buf[0] << 56) +
                     ((long)(buf[1] & 0xff) << 48) +
                     ((long)(buf[2] & 0xff) << 40) +
                     ((long)(buf[3] & 0xff) << 32) +
                     ((long)(buf[4] & 0xff) << 24) +
                     ((buf[5] & 0xff) << 16) +
                     ((buf[6] & 0xff) <<  8) +
                     (buf[7] & 0xff));
        return canonicalIdentifier(newIdentifier(bits));
    }

    synchronized <T> void register(I id, T obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Registered object cannot be null");
        }
        mObjectsToIdentifiers.put(obj, id);
    }

    synchronized Object tryRetrieve(I id) {
        return mIdentifiersToObjects.get(id);
    }

    synchronized I canonicalIdentifier(I id) {
        return mIdentifiers.put(id);
    }

    abstract I newIdentifier(long bits);
}
