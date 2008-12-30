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

package org.cojen.dirmi.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectStreamException;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Identifier which also tracks version numbers for local and remote
 * endpoints. Version numbers are used to detect race conditions during
 * distributed garbage collection.
 *
 * @author Brian S O'Neill
 */
public class VersionedIdentifier extends AbstractIdentifier {
    private static final AtomicIntegerFieldUpdater<VersionedIdentifier> cLocalUpdater =
        AtomicIntegerFieldUpdater.newUpdater(VersionedIdentifier.class, "mLocalVersion");

    private static final AtomicIntegerFieldUpdater<VersionedIdentifier> cRemoteUpdater =
        AtomicIntegerFieldUpdater.newUpdater(VersionedIdentifier.class, "mRemoteVersion");

    private static final IdentifierStore<VersionedIdentifier> cStore =
        new IdentifierStore<VersionedIdentifier>()
    {
        @Override
        VersionedIdentifier newIdentifier(long bits) {
            return new VersionedIdentifier(bits);
        }
    };

    /**
     * Returns a new or existing unique identifier for the given object. If
     * new, the given object is automatically registered with the identifier.
     *
     * @throws IllegalArgumentException if object is null
     */
    public static VersionedIdentifier identify(Object obj) {
        return cStore.identify(obj);
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static VersionedIdentifier read(DataInput in) throws IOException {
        return cStore.read(in);
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static VersionedIdentifier read(InputStream in) throws IOException {
        return cStore.read(in);
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it. Also reads version number and updates it.
     */
    public static VersionedIdentifier readAndUpdateRemoteVersion(DataInput in) throws IOException {
        VersionedIdentifier id = cStore.read(in);
        id.updateRemoteVersion(in.readInt());
        return id;
    }

    private volatile transient int mLocalVersion;
    private volatile transient int mRemoteVersion;

    VersionedIdentifier(long bits) {
        super(bits);
    }

    /**
     * Writes the identifier and also writes the next local version number.
     */
    public void writeWithNextVersion(DataOutput out) throws IOException {
        super.write(out);
        out.writeInt(nextLocalVersion());
    }

    /**
     * Returns the current local version number.
     */
    public int localVersion() {
        return mLocalVersion;
    }

    /**
     * Atomically increments local version number and returns the new value.
     */
    public int nextLocalVersion() {
        return cLocalUpdater.incrementAndGet(this);
    }

    /**
     * Returns the current remote version number.
     */
    public int remoteVersion() {
        return mRemoteVersion;
    }

    /**
     * Atomically updates the remote version number only if the given value is
     * greater than the current remote version.
     *
     * @return true if version was updated
     */
    public boolean updateRemoteVersion(int value) {
        int current;
        // Use difference to handle version overflow, which assumes that
        // absolute difference is always less than 2^31.
        while (((current = mRemoteVersion) - value) < 0) {
            if (cRemoteUpdater.compareAndSet(this, current, value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object tryRetrieve() {
        return cStore.tryRetrieve(this);
    }

    @Override
    public <T> void register(T obj) throws IllegalArgumentException {
        cStore.register(this, obj);
    }

    @Override
    Object readResolve() throws ObjectStreamException {
        return cStore.canonicalIdentifier(this);
    }
}
