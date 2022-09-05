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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Unbatched;

/**
 * Describes a remote method, as provided by {@link RemoteInfo}.
 *
 * @author Brian S O'Neill
 */
final class RemoteMethod implements Comparable<RemoteMethod> {
    private static final int F_UNDECLARED_EX = 1, F_DISPOSER = 2,
        F_BATCHED = 4, F_UNBATCHED = 8, F_PIPED = 16;

    private final int mFlags;
    private final String mName;
    private final String mRemoteFailureException;
    private final String mReturnType;
    private final List<String> mParameterTypes;
    private final Set<String> mExceptionTypes;

    private int mHashCode;

    private RemoteMethod(int flags, String name, String remoteFailureException,
                         String returnType, List<String> parameterTypes, Set<String> exceptionTypes)
    {
        mFlags = flags;
        mName = name;
        mRemoteFailureException = remoteFailureException;
        mReturnType = returnType;
        mParameterTypes = parameterTypes;
        mExceptionTypes = exceptionTypes;
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

        if (m.isAnnotationPresent(Disposer.class)) {
            flags |= F_DISPOSER;
        }
        if (m.isAnnotationPresent(Batched.class)) {
            flags |= F_BATCHED;
        }
        if (m.isAnnotationPresent(Unbatched.class)) {
            flags |= F_UNBATCHED;
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
                } else if (!Modifier.isPublic(remoteFailureException.getModifiers())) {
                    throw new IllegalArgumentException
                        ("Remote failure exception must be public: " +
                         remoteFailureException.getName());
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
                } else if (!TypeCodeMap.isDeclarable(returnType)) {
                    throw new IllegalArgumentException
                        ("Remote method return type isn't supported: " + returnType.getName());
                }
            } else if (returnType != void.class && !CoreUtils.isRemote(returnType)) {
                throw new IllegalArgumentException
                    ("Batched method must return void or a remote object: " + m);
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
                } else if (!TypeCodeMap.isDeclarable(paramType)) {
                    throw new IllegalArgumentException
                        ("Remote method parameter type isn't supported: " + paramType.getName());
                }
            }

            if ((flags & F_PIPED) != 0) {
                if (numPipes != 1) {
                    throw new IllegalArgumentException
                        ("Piped method must have exactly one Pipe parameter: " + m);
                }
            } else if (numPipes != 0) {
                throw new IllegalArgumentException("Piped method must return a Pipe: " + m);
            }
        }

        {
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
                    for (Class<?> exceptionType : exceptionTypes) {
                        if (exceptionType.isAssignableFrom(java.rmi.RemoteException.class)) {
                            remoteFailureException = java.rmi.RemoteException.class;
                            break check;
                        }
                    }
                }

                throw new IllegalArgumentException
                    ("Method must declare throwing " + remoteFailureException.getName() +
                     " (or a superclass): " + m);
            }
        }

        mFlags = flags;

        mRemoteFailureException = remoteFailureException.getName().intern();
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
     * @see Unbatched
     */
    boolean isUnbatched() {
        return (mFlags & F_UNBATCHED) != 0;
    }

    /**
     * @see Pipe
     */
    boolean isPiped() {
        return (mFlags & F_PIPED) != 0;
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

    void conflictCheck(Method m, RemoteMethod other) {
        String prefix = "Method is inherited multiple times with conflicting ";

        if (mFlags != other.mFlags) {
            throw new IllegalArgumentException(prefix + "annotations: " + m);
        }

        if (!mRemoteFailureException.equals(other.mRemoteFailureException)) {
            throw new IllegalArgumentException(prefix + "remote failure exceptions: " + m);
        }

        if (!mExceptionTypes.equals(other.mExceptionTypes)) {
            throw new IllegalArgumentException(prefix + "declared exceptions: " + m);
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
            mHashCode = hash;
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof RemoteMethod) {
            var other = (RemoteMethod) obj;
            return mFlags == other.mFlags && mName.equals(other.mName)
                && mRemoteFailureException.equals(other.mRemoteFailureException)
                && mReturnType.equals(other.mReturnType)
                && mParameterTypes.equals(other.mParameterTypes)
                && mExceptionTypes.equals(other.mExceptionTypes);
        }
        return false;
    }

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
        return compare(mParameterTypes, other.mParameterTypes);
    }

    static <E extends Comparable<E>> int compare(Collection<E> a, Collection<E> b) {
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
                                returnType, parameterTypes, exceptionTypes);
    }
}
