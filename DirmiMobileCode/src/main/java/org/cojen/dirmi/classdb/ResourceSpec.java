/*
 *  Copyright 2008-2009 Brian S O'Neill
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

package org.cojen.dirmi.classdb;

import java.io.Serializable;

import java.util.Arrays;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class ResourceSpec implements Serializable, Comparable<ResourceSpec> {
    private final String mName;
    private final boolean mIsClass;
    private final int mLength;
    private final byte[] mDigest;

    /**
     * @param name resource name relative to package; if class, must not have
     * file extension
     * @param isClass true if resource is a class file
     * @param length uncompressed length of resource, in bytes
     * @param digest message digest of uncompressed resource
     */
    public ResourceSpec(String name, boolean isClass, int length, byte[] digest) {
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
        mIsClass = isClass;
        mLength = length;
        mDigest = digest.clone();
    }

    public String getName() {
        return mName;
    }

    public boolean isClass() {
        return mIsClass;
    }

    public int getLength() {
        return mLength;
    }

    public byte[] getDigest() {
        return mDigest.clone();
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
        if (obj instanceof ResourceSpec) {
            ResourceSpec other = (ResourceSpec) obj;
            return mName.equals(other.mName) &&
                mIsClass == other.mIsClass &&
                mLength == other.mLength &&
                Arrays.equals(mDigest, other.mDigest);
        }
        return false;
    }

    @Override
    public String toString() {
        return "ResourceSpec {name=" + mName + ", class=" + mIsClass +
            ", length=" + mLength + ", digest=" + Arrays.toString(mDigest) + '}';
    }

    /**
     * Non-total ordering by name and isClass property.
     */
    @Override
    public int compareTo(ResourceSpec other) {
        int compare = mName.compareTo(other.mName);
        if (compare == 0) {
            compare = Boolean.valueOf(mIsClass).compareTo(other.mIsClass);
        }
        return compare;
    }
}
