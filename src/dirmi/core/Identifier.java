/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.rmi.NoSuchObjectException;

import java.security.SecureRandom;

import java.util.Map;

import org.cojen.util.WeakCanonicalSet;
import org.cojen.util.WeakIdentityMap;
import org.cojen.util.WeakValuedHashMap;

/**
 * Object which uniquely identifies an object. All Identifier instances
 * themselves are identity comparable.
 *
 * @author Brian S O'Neill
 */
public class Identifier implements Comparable<Identifier> {
    private static final SecureRandom cRandom;
    private static final WeakCanonicalSet cIdentifiers;

    private static final Map<Object, Identifier> cObjectsToIdentifiers;
    private static final Map<Identifier, Object> cIdentifiersToObjects;

    static {
        cRandom = new SecureRandom();
        cIdentifiers = new WeakCanonicalSet();

        cObjectsToIdentifiers = new WeakIdentityMap();
        cIdentifiersToObjects = new WeakValuedHashMap();
    }

    /**
     * Returns a new or existing unique identifier for the given object.
     *
     * @throws IllegalArgumentException if object is null
     */
    public synchronized static Identifier identify(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }
        Identifier id = cObjectsToIdentifiers.get(obj);
        if (id == null) {
            do {
                id = new Identifier(cRandom.nextLong(), cRandom.nextLong());
            } while (cIdentifiersToObjects.containsKey(id));
            id = (Identifier) cIdentifiers.put(id);
            cObjectsToIdentifiers.put(obj, id);
            cIdentifiersToObjects.put(id, obj);
        }
        return id;
    }

    public static Identifier read(DataInput in) throws IOException {
        long high = in.readLong();
        long low = in.readLong();
        Identifier id = new Identifier(high, low);
        return canonicalIdentifier(id);
    }

    private synchronized static Identifier canonicalIdentifier(Identifier id) {
        return (Identifier) cIdentifiers.put(id);
    }

    private synchronized static void register(Identifier id, Object obj) {
        Object existing = cIdentifiersToObjects.get(id);
        if (existing != null) {
            throw new IllegalStateException
                ("An object is already registered: " + id + " -> " + existing);
        }
        cObjectsToIdentifiers.put(obj, id);
        cIdentifiersToObjects.put(id, obj);
    }

    private synchronized static Object tryRetrieve(Identifier id) {
        return cIdentifiersToObjects.get(id);
    }

    private final long mHighID;
    private final long mLowID;

    private Identifier(long high, long low) {
        mHighID = high;
        mLowID = low;
    }

    /**
     * Returns the identified object, if it exists locally.
     *
     * @return identified object, never null
     * @throws NoSuchObjectException if object doesn't exist locally.
     */
    public Object retrieve() throws NoSuchObjectException {
        Object obj = tryRetrieve();
        if (obj == null) {
            throw new NoSuchObjectException("No object for: " + this);
        }
        return obj;
    }

    /**
     * Returns the identified object, if it exists locally.
     *
     * @return identified object, or null if it doesn't exist locally.
     */
    public Object tryRetrieve() {
        return tryRetrieve(this);
    }

    /**
     * Register the given object with this identifier. Registered objects are
     * not strongly referenced, and so they may be garbage collected unless
     * referenced elsewhere.
     *
     * @throws IllegalStateException if an object is already registered with
     * this identifier
     */
    public void register(Object obj) {
        register(this, obj);
    }

    public void write(DataOutput out) throws IOException {
        out.writeLong(mHighID);
        out.writeLong(mLowID);
    }

    @Override
    public int hashCode() {
        return (int) ((mHighID ^ (mHighID >>> 32)) ^ (mLowID ^ (mHighID >>> 32)));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Identifier) {
            Identifier other = (Identifier) obj;
            return mHighID == other.mHighID && mLowID == other.mLowID;
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("Identifier: 00000000000000000000000000000000");
        int end = b.length();
        String high = Long.toHexString(mHighID);
        b.replace(end - 16 - high.length(), end - 16, high);
        String low = Long.toHexString(mLowID);
        b.replace(end - low.length(), end, low);
        return b.toString();
    }

    /**
     * Lexigraphically compares two identifiers.
     */
    public int compareTo(Identifier id) {
	if (this == id) {
	    return 0;
	}
	int result = compareLong(mHighID >>> 32, id.mHighID >>> 32);
	if (result == 0) {
	    result = compareLong((int) mHighID, (int) id.mHighID);
	    if (result == 0) {
		result = compareLong(mLowID >>> 32, id.mLowID >>> 32);
		if (result == 0) {
		    result = compareLong((int) mLowID, (int) id.mLowID);
		}
	    }
	}
	return result;
    }

    private int compareLong(long a, long b) {
	return a < b ? -1 : (a > b ? 1 : 0);
    }
}
