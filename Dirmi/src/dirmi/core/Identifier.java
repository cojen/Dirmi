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
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

import java.rmi.NoSuchObjectException;

import java.security.SecureRandom;

import java.util.Map;

import org.cojen.util.WeakCanonicalSet;
import org.cojen.util.WeakIdentityMap;
import org.cojen.util.WeakValuedHashMap;

/**
 * Object which uniquely identifies another object. All Identifier instances
 * themselves are identity comparable.
 *
 * @author Brian S O'Neill
 */
public class Identifier implements Serializable, Comparable<Identifier> {
    private static final SecureRandom cRandom;
    private static final WeakCanonicalSet<Identifier> cIdentifiers;

    private static final Map<Object, Identifier> cObjectsToIdentifiers;
    private static final Map<Identifier, Object> cIdentifiersToObjects;

    static {
        cRandom = new SecureRandom();
        cIdentifiers = new WeakCanonicalSet<Identifier>();

        cObjectsToIdentifiers = new WeakIdentityMap<Object, Identifier>();
        cIdentifiersToObjects = new WeakValuedHashMap<Identifier, Object>();
    }

    /**
     * Returns a new or existing unique identifier for the given object. If
     * new, the given object is automatically registered with the identifier.
     *
     * @throws IllegalArgumentException if object is null
     */
    public synchronized static Identifier identify(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }
        Identifier id = cObjectsToIdentifiers.get(obj);
        if (id == null) {
            // FIXME: Possibly use a faster generator, like Mersenne twister.
            do {
                id = new Identifier(cRandom.nextLong());
            } while (cIdentifiersToObjects.containsKey(id));
            id = (Identifier) cIdentifiers.put(id);
            cObjectsToIdentifiers.put(obj, id);
            cIdentifiersToObjects.put(id, obj);
        }
        return id;
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static Identifier read(DataInput in) throws IOException {
        return canonicalIdentifier(new Identifier(in.readLong()));
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static Identifier read(InputStream in) throws IOException {
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
        return canonicalIdentifier(new Identifier(bits));
    }

    private synchronized static Identifier canonicalIdentifier(Identifier id) {
        return cIdentifiers.put(id);
    }

    private synchronized static Object register(Identifier id, Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Registered object cannot be null");
        }
        Object existing = cIdentifiersToObjects.get(id);
        if (existing != null) {
            return existing;
        }
        cObjectsToIdentifiers.put(obj, id);
        cIdentifiersToObjects.put(id, obj);
        return obj;
    }

    private synchronized static Object tryRetrieve(Identifier id) {
        return cIdentifiersToObjects.get(id);
    }

    private final long mBits;

    private Identifier(long bits) {
        mBits = bits;
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
     * referenced elsewhere. Only one object may be registered with the
     * identifier, and attempting to register another object has no effect.
     *
     * @return registered object, never null
     * @throws IllegalArgumentException if given object is null
     */
    public <T> T register(T obj) throws IllegalArgumentException {
        return (T) register(this, obj);
    }

    public void write(DataOutput out) throws IOException {
        out.writeLong(mBits);
    }

    public void write(OutputStream out) throws IOException {
        long bits = mBits;
        byte[] buf = new byte[8];
        buf[0] = (byte)(bits >>> 56);
        buf[1] = (byte)(bits >>> 48);
        buf[2] = (byte)(bits >>> 40);
        buf[3] = (byte)(bits >>> 32);
        buf[4] = (byte)(bits >>> 24);
        buf[5] = (byte)(bits >>> 16);
        buf[6] = (byte)(bits >>>  8);
        buf[7] = (byte)(bits);
        out.write(buf, 0, 8);
    }

    @Override
    public int hashCode() {
        return (int) (mBits ^ (mBits >>> 32));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Identifier) {
            Identifier other = (Identifier) obj;
            return mBits == other.mBits;
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("0000000000000000");
        int end = b.length();
        String bits = Long.toHexString(mBits);
        b.replace(end - bits.length(), end, bits);
        return b.toString();
    }

    /**
     * Lexigraphically compares two identifiers.
     */
    public int compareTo(Identifier id) {
        if (this != id) {
            if (mBits < id.mBits) {
                return -1;
            } else if (mBits > id.mBits) {
                return 1;
            }
        }
        return 0;
    }

    private Object readResolve() throws java.io.ObjectStreamException {
        return canonicalIdentifier(this);
    }
}
