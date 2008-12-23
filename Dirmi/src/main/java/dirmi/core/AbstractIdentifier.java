/*
 *  Copyright 2008 Brian S O'Neill
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

import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.io.Serializable;

import java.rmi.NoSuchObjectException;

/**
 * Abstract object which uniquely identifies another object.
 *
 * @author Brian S O'Neill
 */
public abstract class AbstractIdentifier implements Comparable<AbstractIdentifier>, Serializable  {
    private final long mBits;

    AbstractIdentifier(long bits) {
        mBits = bits;
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
    public abstract Object tryRetrieve();

    /**
     * Register the given object with this identifier. Registered objects are
     * not strongly referenced, and so they may be garbage collected unless
     * referenced elsewhere. Many objects can be registered with an identifier.
     *
     * <p>A registered object cannot be retrieved, but calling identify against
     * a registered object returns the original identifier object.
     *
     * @throws IllegalArgumentException if given object is null
     */
    public abstract <T> void register(T obj) throws IllegalArgumentException;

    public byte getData() {
        return (byte) mBits;
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
        if (obj instanceof AbstractIdentifier) {
            AbstractIdentifier other = (AbstractIdentifier) obj;
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
    public int compareTo(AbstractIdentifier id) {
        if (this != id) {
            if (mBits < id.mBits) {
                return -1;
            } else if (mBits > id.mBits) {
                return 1;
            }
        }
        return 0;
    }

    abstract Object readResolve() throws ObjectStreamException;
}
