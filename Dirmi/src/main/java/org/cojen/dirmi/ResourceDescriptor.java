/*
 *  Copyright 2009 Brian S O'Neill
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

package org.cojen.dirmi;

import java.io.Serializable;

import java.util.Arrays;

/**
 * Describes a resource available from a {@link PackageDirectory}.
 *
 * @author Brian S O'Neill
 */
public final class ResourceDescriptor implements Serializable, Comparable<ResourceDescriptor> {
    private static final long serialVersionUID = 1L;

    private final String mName;
    private final int mLength;
    private final byte[] mDigest;

    /**
     * @param name resource name, relative to its enclosing package
     * @param length full length
     * @param digest SHA-1 digest
     */
    public ResourceDescriptor(String name, int length, byte[] digest) {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length: " + length);
        }
        if (digest == null) {
            throw new IllegalArgumentException("digest is null");
        }
        mName = name;
        mLength = length;
        mDigest = digest.clone();
    }

    /**
     * Returns the relative resource name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the full length of the resource.
     */
    public int getLength() {
        return mLength;
    }

    /**
     * Returns the SHA-1 digest of the resource, as computed over its full
     * length.
     */
    public byte[] getDigest() {
        return mDigest.clone();
    }

    /**
     * Compares resources only by name.
     */
    @Override
    public int compareTo(ResourceDescriptor other) {
        return mName.compareTo(other.mName);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mDigest);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof ResourceDescriptor) {
            ResourceDescriptor other = (ResourceDescriptor) obj;
            return mName.equals(other.mName) &&
                mLength == other.mLength &&
                Arrays.equals(mDigest, other.mDigest);
        }
        return false;
    }

    @Override
    public String toString() {
        return "ResourceDescriptor {name=" + mName +
            ", length=" + mLength + ", digest=" + Arrays.toString(mDigest) + '}';
    }
}
