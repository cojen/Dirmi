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

import java.io.IOException;
import java.io.ObjectInputFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Data;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.NoReply;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Restorable;
import org.cojen.dirmi.Serialized;
import org.cojen.dirmi.Unbatched;

/**
 * Describes a remote method, as provided by {@link RemoteInfo}.
 *
 * @author Brian S O'Neill
 */
final class RemoteMethod implements Comparable<RemoteMethod> {
    private static final int F_UNDECLARED_EX = 1, F_DISPOSER = 2,
        F_BATCHED = 4, F_UNBATCHED = 8, F_RESTORABLE = 16, F_PIPED = 32, F_NOREPLY = 64,
        F_SERIALIZED = 128, F_UNIMPLEMENTED = 256, F_LENIENT = 512, F_DATA = 1024;

    private final int mFlags;
    private final String mName;
    private final String mRemoteFailureException;
    private final String mReturnType;
    private final List<String> mParameterTypes;
    private final Set<String> mExceptionTypes;
    private final ObjectInputFilter mObjectInputFilter;

    private int mHashCode;

    private RemoteMethod(int flags, String name, String remoteFailureException,
                         String returnType, List<String> parameterTypes, Set<String> exceptionTypes,
                         ObjectInputFilter objectInputFilter)
    {
        mFlags = flags;
        mName = name;
        mRemoteFailureException = remoteFailureException;
        mReturnType = returnType;
        mParameterTypes = parameterTypes;
        mExceptionTypes = exceptionTypes;
        mObjectInputFilter = objectInputFilter;
    }

    /**
     * @param defaultFailure defined by the interface (optional)
     */
    RemoteMethod(Method m, RemoteFailure defaultFailure) {
        if (!Modifier.isPublic(m.getModifiers())) {
            throw new IllegalArgumentException("Remote method must be public: " + m);
        }

        mName = m.getName().intern();

        int flags = 0;

        Serialized serializedAnn;

        if (m.isAnnotationPresent(Disposer.class)) {
            flags |= F_DISPOSER;
        }
        if (m.isAnnotationPresent(Batched.class)) {
            flags |= F_BATCHED;
        }
        if (m.isAnnotationPresent(Unbatched.class)) {
            flags |= F_UNBATCHED;
        }
        Restorable restorableAnn = m.getAnnotation(Restorable.class);
        if (restorableAnn != null) {
            flags |= F_RESTORABLE;
            if (restorableAnn.lenient()) {
                flags |= F_LENIENT;
            }
        }
        if (m.isAnnotationPresent(NoReply.class)) {
            flags |= F_NOREPLY;
        }
        if ((serializedAnn = m.getAnnotation(Serialized.class)) != null) {
            flags |= F_SERIALIZED;
        }
        if (m.isAnnotationPresent(Unimplemented.class) && !m.getDeclaringClass().isInterface()) {
            flags |= F_UNIMPLEMENTED;
        }

        if ((flags & F_BATCHED) != 0 && (flags & F_UNBATCHED) != 0) {
            throw new IllegalArgumentException
                ("Method cannot be annotated as both @Batched and @Unbatched: " + m);
        }

        Class<? extends Throwable> remoteFailureException;
        {
            RemoteFailure ann = m.getAnnotation(RemoteFailure.class);

            if (ann == null) {
                ann = defaultFailure;
            }

            if (ann == null) {
                remoteFailureException = RemoteException.class;
            } else {
                remoteFailureException = ann.exception();

                if (remoteFailureException == null) {
                    remoteFailureException = RemoteException.class;
                } else {
                    if (!Modifier.isPublic(remoteFailureException.getModifiers())) {
                        throw new IllegalArgumentException
                            ("Remote failure exception must be public: " +
                             remoteFailureException.getName());
                    }

                    ctorCheck: {
                        try {
                            remoteFailureException.getConstructor(Throwable.class);
                            break ctorCheck;
                        } catch (NoSuchMethodException e) {
                        }
                        try {
                            remoteFailureException.getConstructor(String.class, Throwable.class);
                            break ctorCheck;
                        } catch (NoSuchMethodException e) {
                        }
                        throw new IllegalArgumentException
                            ("Remote failure exception must have a public constructor " +
                             "which accepts a Throwable cause: " +
                             remoteFailureException.getName());
                    }
                }

                if (!ann.declared() || CoreUtils.isUnchecked(remoteFailureException)) {
                    flags |= F_UNDECLARED_EX;
                }
            }
        }

        {
            Class<?> returnType = m.getReturnType();

            if (!Modifier.isPublic(returnType.getModifiers())) {
                throw new IllegalArgumentException
                    ("Remote method return type must be public: " + m);
            }

            if ((flags & F_BATCHED) == 0) {
                if (returnType == Pipe.class) {
                    flags |= F_PIPED;
                }
            } else if (returnType != void.class && !CoreUtils.isRemote(returnType)) {
                throw new IllegalArgumentException
                    ("Batched method must return void or a remote object: " + m);
            }

            if ((flags & F_RESTORABLE) != 0) {
                if (!CoreUtils.isRemote(returnType)) {
                    throw new IllegalArgumentException
                        ("Restorable method must return a remote object: " + m);
                }
                if ((flags & (F_DISPOSER | F_LENIENT)) == (F_DISPOSER | F_LENIENT)) {
                    throw new IllegalArgumentException
                        ("Lenient restorable method cannot also be a disposer: " + m);
                }
            }

            if ((flags & F_NOREPLY) != 0 && returnType != void.class) {
                throw new IllegalArgumentException("NoReply method must return void: " + m);
            }

            mReturnType = returnType.descriptorString().intern();
        }

        {
            Class<?>[] paramTypes = m.getParameterTypes();

            if (paramTypes.length == 0) {
                mParameterTypes = Collections.emptyList();
            } else {
                mParameterTypes = new ArrayList<>(paramTypes.length);
                for (Class<?> paramType : paramTypes) {
                    if (!Modifier.isPublic(paramType.getModifiers())) {
                        throw new IllegalArgumentException
                            ("Remote method parameter types must be public: " + m);
                    }
                    mParameterTypes.add(paramType.descriptorString().intern());
                }
            }

            int numPipes = 0;
            for (Class<?> paramType : paramTypes) {
                if (paramType == Pipe.class) {
                    numPipes++;
                }
            }

            if ((flags & F_PIPED) != 0) {
                if (numPipes != 1) {
                    throw new IllegalArgumentException
                        ("Piped method must have exactly one Pipe parameter: " + m);
                }
                if (serializedAnn != null) {
                    throw new IllegalArgumentException
                        ("Piped method cannot have @Serialized annotation: " + m);
                }
            } else if (numPipes != 0) {
                throw new IllegalArgumentException("Piped method must return a Pipe: " + m);
            }
        }

        if (m.isAnnotationPresent(Data.class)) {
            if (!mParameterTypes.isEmpty()) {
                throw new IllegalArgumentException
                    ("Data method cannot have any parameters: " + m);
            }
            if ((flags & ~F_SERIALIZED) != 0) {
                throw new IllegalArgumentException
                    ("Data method cannot have any other annotations except @Serialized: " + m);
            }
            flags |= F_DATA;
            mExceptionTypes = Collections.emptySet();
        } else {
            Class<?>[] exceptionTypes = m.getExceptionTypes();

            if (exceptionTypes.length == 0) {
                mExceptionTypes = Collections.emptySet();
            } else {
                mExceptionTypes = new TreeSet<>();
                for (Class<?> exceptionType : exceptionTypes) {
                    if (!Modifier.isPublic(exceptionType.getModifiers())) {
                        throw new IllegalArgumentException
                            ("Remote exception type must be public: " + m +
                             " throws " + exceptionType);
                    }
                    mExceptionTypes.add(exceptionType.getName().intern());
                }
            }

            check: if ((flags & F_UNDECLARED_EX) == 0) {
                for (Class<?> exceptionType : exceptionTypes) {
                    if (exceptionType.isAssignableFrom(remoteFailureException)) {
                        break check;
                    }
                }

                if (remoteFailureException == RemoteException.class) {
                    // Be lenient and also support java.rmi interfaces.
                    try {
                        for (Class<?> exceptionType : exceptionTypes) {
                            if (exceptionType.isAssignableFrom(java.rmi.RemoteException.class)) {
                                remoteFailureException = java.rmi.RemoteException.class;
                                break check;
                            }
                        }
                    } catch (Throwable e) {
                        // The java.rmi module might not be found.
                    }
                }

                throw new IllegalArgumentException
                    ("Method must declare throwing " + remoteFailureException.getName() +
                     " (or a superclass): " + m);
            }
        }

        mFlags = flags;

        mRemoteFailureException = remoteFailureException.getName().intern();

        if (serializedAnn == null) {
            mObjectInputFilter = null;
        } else {
            mObjectInputFilter = SerializedFilter.filterFor(serializedAnn);
        }
    }

    /**
     * Returns a new instance which is designated as batched immediate.
     *
     * @see #isBatchedImmediate
     */
    RemoteMethod asBatchedImmediate() {
        int flags = (mFlags | (F_BATCHED | F_UNBATCHED)) & ~F_NOREPLY;
        return new RemoteMethod(flags, mName, mRemoteFailureException,
                                mReturnType, mParameterTypes, mExceptionTypes, mObjectInputFilter);
    }

    /**
     * @see RemoteFailure
     */
    boolean isRemoteFailureExceptionUndeclared() {
        return (mFlags & F_UNDECLARED_EX) != 0;
    }

    /**
     * @see Disposer
     */
    boolean isDisposer() {
        return (mFlags & F_DISPOSER) != 0;
    }

    /**
     * @see Batched
     */
    boolean isBatched() {
        return (mFlags & F_BATCHED) != 0;
    }

    /**
     * Is true to designate a batched method variant which runs immediately and returns a
     * remote object. This variant is used when the client doesn't know the remote typeId yet.
     */
    boolean isBatchedImmediate() {
        return (mFlags & (F_BATCHED | F_UNBATCHED)) == (F_BATCHED | F_UNBATCHED);
    }

    /**
     * @see Unbatched
     */
    boolean isUnbatched() {
        return (mFlags & (F_BATCHED | F_UNBATCHED)) == F_UNBATCHED;
    }

    /**
     * @see Restorable
     */
    boolean isRestorable() {
        return (mFlags & F_RESTORABLE) != 0;
    }

    /**
     * @see Restorable
     */
    boolean isLenient() {
        return (mFlags & F_LENIENT) != 0;
    }

    /**
     * @see Pipe
     */
    boolean isPiped() {
        return (mFlags & F_PIPED) != 0;
    }

    /**
     * @see NoReply
     */
    boolean isNoReply() {
        return (mFlags & F_NOREPLY) != 0;
    }

    /**
     * @see Serialized
     */
    boolean isSerialized() {
        return (mFlags & F_SERIALIZED) != 0;
    }

    /**
     * Returns true if the method is Serialized and the return type is an object type.
     */
    boolean isSerializedReturnType() {
        return isSerialized() && CoreUtils.isObjectType(returnType());
    }

    /**
     * Returns a non-null filter if this method is Serialized and this RemoteMethod instance
     * was constructed locally. That is, it didn't come from the readFrom method.
     */
    ObjectInputFilter objectInputFilter() {
        return mObjectInputFilter;
    }

    /**
     * @see Data
     */
    boolean isData() {
        return (mFlags & F_DATA) != 0;
    }

    /**
     * @see Unimplemented
     */
    boolean isUnimplemented() {
        return (mFlags & F_UNIMPLEMENTED) != 0;
    }

    /**
     * Returns true if this method doesn't block waiting for a reply from the pipe.
     */
    boolean isUnacknowledged() {
        return (mFlags & (F_BATCHED | F_PIPED | F_NOREPLY)) != 0;
    }

    /**
     * Returns the name of this method.
     */
    String name() {
        return mName;
    }

    /**
     * Returns the remote failure exception to be thrown from this method.
     */
    String remoteFailureException() {
        return mRemoteFailureException;
    }

    /**
     * Returns the return type signature of this method.
     */
    String returnType() {
        return mReturnType;
    }

    /**
     * Returns all of the parameter type signatures of this method.
     */
    List<String> parameterTypes() {
        return mParameterTypes;
    }

    /**
     * Returns all of the declared exception type names in a consistent order.
     */
    Set<String> exceptionTypes() {
        return mExceptionTypes;
    }

    /**
     * Given another method with the same signature, returns true if the annotations match well
     * enough such that the wire protocol matches.
     */
    boolean isCompatibleWith(RemoteMethod other) {
        int mask = F_BATCHED | F_NOREPLY | F_SERIALIZED | F_DATA;
        return (mFlags & mask) == (other.mFlags & mask);
    }

    void conflictCheck(Method m, RemoteMethod other) {
        String prefix = "Method is inherited multiple times with conflicting ";

        if (mFlags != other.mFlags) {
            throw new IllegalArgumentException(prefix + "annotations: " + m);
        }

        if (!mRemoteFailureException.equals(other.mRemoteFailureException)) {
            throw new IllegalArgumentException(prefix + "remote failure exceptions: " + m);
        }
    }

    @Override
    public int hashCode() {
        int hash = mHashCode;
        if (hash == 0) {
            hash = mFlags;
            hash = hash * 31 + mName.hashCode();
            hash = hash * 31 + mRemoteFailureException.hashCode();
            hash = hash * 31 + mReturnType.hashCode();
            hash = hash * 31 + mParameterTypes.hashCode();
            hash = hash * 31 + mExceptionTypes.hashCode();
            if (mObjectInputFilter != null) {
                hash = hash * 31 + mObjectInputFilter.hashCode();
            }
            mHashCode = hash;
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof RemoteMethod other) {
            return mFlags == other.mFlags && mName.equals(other.mName)
                && mRemoteFailureException.equals(other.mRemoteFailureException)
                && mReturnType.equals(other.mReturnType)
                && mParameterTypes.equals(other.mParameterTypes)
                && mExceptionTypes.equals(other.mExceptionTypes)
                && Objects.equals(mObjectInputFilter, other.mObjectInputFilter);
        }
        return false;
    }

    /**
     * Compares methods for client/server compatibility.
     */
    @Override
    public int compareTo(RemoteMethod other) {
        int cmp = mName.compareTo(other.mName);
        if (cmp != 0) {
            return cmp;
        }
        cmp = mReturnType.compareTo(other.mReturnType);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compare(mParameterTypes, other.mParameterTypes);
        if (cmp != 0) {
            return cmp;
        }
        // Flipping the order causes a batched immediate variant to come before the normal
        // batched variant. This makes things easier for StubMaker.
        return Boolean.compare(other.isBatchedImmediate(), isBatchedImmediate());
    }

    private static <E extends Comparable<E>> int compare(List<E> a, List<E> b) {
        int sizea = a.size();
        int sizeb = b.size();

        int size = Math.min(sizea, sizeb);

        if (size != 0) {
            Iterator<E> ita = a.iterator();
            Iterator<E> itb = b.iterator();
            for (int i=0; i<size; i++) {
                int cmp = ita.next().compareTo(itb.next());
                if (cmp != 0) {
                    return cmp;
                }
            }
        }

        return Integer.compare(sizea, sizeb);
    }

    void writeTo(Pipe pipe) throws IOException {
        pipe.writeInt(mFlags);
        pipe.writeObject(mName);
        pipe.writeObject(mRemoteFailureException);
        pipe.writeObject(mReturnType);
        RemoteInfo.writeStrings(pipe, mParameterTypes);
        RemoteInfo.writeStrings(pipe, mExceptionTypes);
    }

    static RemoteMethod readFrom(Pipe pipe) throws IOException {
        int flags = pipe.readInt();
        var name = ((String) pipe.readObject()).intern();
        var remoteFailureException = ((String) pipe.readObject()).intern();
        var returnType = ((String) pipe.readObject()).intern();
        var parameterTypes = RemoteInfo.readInternedStringList(pipe);
        var exceptionTypes = RemoteInfo.readInternedStringSet(pipe);

        return new RemoteMethod(flags, name, remoteFailureException,
                                returnType, parameterTypes, exceptionTypes, null);
    }
}
