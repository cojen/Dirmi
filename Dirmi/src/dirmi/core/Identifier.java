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
    private static final MersenneTwisterFast cRandom;
    private static final WeakCanonicalSet<Identifier> cIdentifiers;

    private static final Map<Object, Identifier> cObjectsToIdentifiers;
    private static final Map<Identifier, Object> cIdentifiersToObjects;

    static {
        SecureRandom rnd = new SecureRandom();
        int[] seed = new int[MersenneTwisterFast.N];
        for (int i=0; i<seed.length; i++) {
            seed[i] = rnd.nextInt();
        }

        cRandom = new MersenneTwisterFast(seed);

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
            do {
                if (obj instanceof Class) {
                    long v;
                    do {
                        v = cRandom.nextLong();
                    } while (v == 0);
                    id = new Identifier(0, v);
                } else {
                    Identifier classId = identify(obj.getClass());
                    id = new Identifier(classId.mLowerBits, cRandom.nextLong());
                }
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
        return canonicalIdentifier(new Identifier(in.readLong(), in.readLong()));
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static Identifier read(InputStream in) throws IOException {
        int off = 0;
        int len = 16;
        byte[] buf = new byte[len];
        int amt;
        while ((amt = in.read(buf, off, len)) > 0) {
            off += amt;
            len -= amt;
        }
        if (len > 0) {
            throw new EOFException("Unable to fully read identifier");
        }
        return canonicalIdentifier(new Identifier(readBits(buf, 0), readBits(buf, 8)));
    }

    private static long readBits(byte[] buf, int offset) {
        return (((long)buf[offset] << 56) +
                ((long)(buf[offset + 1] & 0xff) << 48) +
                ((long)(buf[offset + 2] & 0xff) << 40) +
                ((long)(buf[offset + 3] & 0xff) << 32) +
                ((long)(buf[offset + 4] & 0xff) << 24) +
                ((buf[offset + 5] & 0xff) << 16) +
                ((buf[offset + 6] & 0xff) <<  8) +
                (buf[offset + 7] & 0xff));
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

    private final long mUpperBits;
    private final long mLowerBits;

    private Identifier(long upperBits, long lowerBits) {
        mUpperBits = upperBits;
        mLowerBits = lowerBits;
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
        out.writeLong(mUpperBits);
        out.writeLong(mLowerBits);
    }

    public void write(OutputStream out) throws IOException {
        byte[] buf = new byte[16];
        writeBits(mUpperBits, buf, 0);
        writeBits(mLowerBits, buf, 8);
        out.write(buf, 0, 16);
    }

    private void writeBits(long bits, byte[] buf, int offset) {
        buf[offset] = (byte)(bits >>> 56);
        buf[offset + 1] = (byte)(bits >>> 48);
        buf[offset + 2] = (byte)(bits >>> 40);
        buf[offset + 3] = (byte)(bits >>> 32);
        buf[offset + 4] = (byte)(bits >>> 24);
        buf[offset + 5] = (byte)(bits >>> 16);
        buf[offset + 6] = (byte)(bits >>>  8);
        buf[offset + 7] = (byte)(bits);
    }

    @Override
    public int hashCode() {
        return (int) ((mUpperBits ^ (mUpperBits >>> 32)) ^ (mLowerBits ^ (mLowerBits >>> 32)));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Identifier) {
            Identifier other = (Identifier) obj;
            return mUpperBits == other.mUpperBits && mLowerBits == other.mLowerBits;
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder b;
        if (mUpperBits == 0) {
            // Identifier for a class.
            b = new StringBuilder("0000000000000000");
            String bits = Long.toHexString(mLowerBits);
            b.replace(16 - bits.length(), 16, bits);
        } else {
            b = new StringBuilder("0000000000000000-0000000000000000");
            String bits = Long.toHexString(mUpperBits);
            b.replace(16 - bits.length(), 16, bits);
            bits = Long.toHexString(mLowerBits);
            b.replace(33 - bits.length(), 33, bits);
        }
        return b.toString();
    }

    /**
     * Lexigraphically compares two identifiers.
     */
    public int compareTo(Identifier id) {
        if (this != id) {
            if (mUpperBits < id.mUpperBits) {
                return -1;
            } else if (mUpperBits > id.mUpperBits) {
                return 1;
            }
            if (mLowerBits < id.mLowerBits) {
                return -1;
            } else if (mLowerBits > id.mLowerBits) {
                return 1;
            }
        }
        return 0;
    }

    private Object readResolve() throws java.io.ObjectStreamException {
        return canonicalIdentifier(this);
    }
}
