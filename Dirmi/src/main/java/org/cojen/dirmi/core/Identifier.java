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

package org.cojen.dirmi.core;

import java.io.DataInput;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectStreamException;

/**
 * Object which uniquely identifies another object. All Identifier instances
 * themselves are identity comparable.
 *
 * @author Brian S O'Neill
 */
public class Identifier extends AbstractIdentifier {
    private static final IdentifierStore<Identifier> cStore = new IdentifierStore<Identifier>() {
        @Override
        Identifier newIdentifier(long bits) {
            return new Identifier(bits);
        }
    };

    /**
     * Returns a new or existing unique identifier for the given object. If
     * new, the given object is automatically registered with the identifier.
     *
     * @throws IllegalArgumentException if object is null
     */
    public static Identifier identify(Object obj) {
        return cStore.identify(obj);
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
    static Identifier identify(Object obj, byte andMask, byte orMask) {
        return cStore.identify(obj, andMask, orMask);
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static Identifier read(DataInput in) throws IOException {
        return cStore.read(in);
    }

    /**
     * Returns a deserialized identifier, which may or may not have an object
     * registered with it.
     */
    public static Identifier read(InputStream in) throws IOException {
        return cStore.read(in);
    }

    Identifier(long bits) {
        super(bits);
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
