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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class RemoteInfo {
    private static final int F_UNDECLARED_EX = 1;

    private static final SoftCache<Class<?>, RemoteInfo> cCache = new SoftCache<>();

    /**
     * @param type non-null remote interface to examine
     * @throws IllegalArgumentException if remote is malformed
     */
    public static RemoteInfo examine(Class<?> type) {
        RemoteInfo info = cCache.get(type);

        if (info != null) {
            return info;
        }

        synchronized (cCache) {
            info = cCache.get(type);
            if (info != null) {
                return info;
            }

            if (!type.isInterface()) {
                throw new IllegalArgumentException("Remote type must be an interface: " + type);
            }

            if (!Modifier.isPublic(type.getModifiers())) {
                throw new IllegalArgumentException
                    ("Remote interface must be public: " + type.getName());
            }

            if (!CoreUtils.isRemote(type)) {
                throw new IllegalArgumentException
                    ("Remote interface must extend " + Remote.class.getName() + ": " +
                     type.getName());
            }

            int flags = 0;

            Class<? extends Throwable> remoteFailureException;
            RemoteFailure ann = type.getAnnotation(RemoteFailure.class);

            if (ann == null) {
                remoteFailureException = RemoteException.class;
            } else {
                remoteFailureException = ann.exception();
                if (remoteFailureException == null) {
                    remoteFailureException = RemoteException.class;
                }
                if (!ann.declared() || CoreUtils.isUnchecked(remoteFailureException)) {
                    flags |= F_UNDECLARED_EX;
                }
            }

            Map<RemoteMethod, RemoteMethod> methodMap = new TreeMap<>();

            for (Method m : type.getMethods()) {
                if (m.getDeclaringClass().isInterface() && !isObjectMethod(m)) {
                    RemoteMethod candidate;
                    try {
                        candidate = new RemoteMethod(m, ann);
                    } catch (IllegalArgumentException e) {
                        if (m.isDefault()) {
                            continue;
                        }
                        throw e;
                    }

                    RemoteMethod existing = methodMap.putIfAbsent(candidate, candidate);

                    if (existing != null) {
                        // The same method is inherited from multiple parent interfaces.
                        existing.conflictCheck(m, candidate);
                    }
                }
            }

            SortedSet<RemoteMethod> methods;
            if (methodMap.isEmpty()) {
                methods = Collections.emptySortedSet();
            } else {
                methods = new TreeSet<>(methodMap.keySet());
            }

            // Gather all of the additional implemented interfaces which implement Remote.
            Set<String> interfaces = new TreeSet<>();
            gatherRemoteInterfaces(interfaces, type);
            interfaces.remove(type.getName());

            if (interfaces.isEmpty()) {
                interfaces = Collections.emptySet();
            }

            String name = type.getName().intern();
            String remoteFailureString = remoteFailureException.getName().intern();

            info = new RemoteInfo(flags, name, remoteFailureString, interfaces, methods);

            cCache.put(type, info);

            return info;
        }
    }

    private static void gatherRemoteInterfaces(Set<String> interfaces, Class<?> clazz) {
        for (Class<?> i : clazz.getInterfaces()) {
            if (CoreUtils.isRemote(i)) {
                if (interfaces.add(i.getName().intern())) {
                    gatherRemoteInterfaces(interfaces, i);
                }
            }
        }
        if (clazz.isInterface() && CoreUtils.isRemote(clazz)) {
            interfaces.add(clazz.getName().intern());
        }
    }

    private static boolean isObjectMethod(Method m) {
        try {
            return Object.class.getMethod(m.getName(), m.getParameterTypes()) != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private final int mFlags;
    private final String mName;
    private final String mRemoteFailureException;
    private final Set<String> mInterfaceNames;
    private final SortedSet<RemoteMethod> mRemoteMethods;

    private int mHashCode;

    private RemoteInfo(int flags, String name, String remoteFailureException,
                       Set<String> interfaceNames, SortedSet<RemoteMethod> remoteMethods)
    {
        mFlags = flags;
        mName = name;
        mRemoteFailureException = remoteFailureException;
        mInterfaceNames = interfaceNames;
        mRemoteMethods = remoteMethods;
    }

    /**
     * @see RemoteFailure
     */
    boolean isRemoteFailureExceptionUndeclared() {
        return (mFlags & F_UNDECLARED_EX) != 0;
    }

    /**
     * Returns the name of the remote interface described by this RemoteInfo, which is the same
     * as the interface name.
     */
    String name() {
        return mName;
    }

    /**
     * Returns the default remote failure exception to be thrown by remote methods.
     */
    String remoteFailureException() {
        return mRemoteFailureException;
    }

    /**
     * Returns the names of all remote interfaces implemented by this RemoteInfo, excluding
     * itself. The set elements are guaranteed to have a consistent ordering.
     */
    Set<String> interfaceNames() {
        return mInterfaceNames;
    }

    /**
     * Returns all of remote methods in a consistent order.
     */
    SortedSet<RemoteMethod> remoteMethods() {
        return mRemoteMethods;
    }

    @Override
    public int hashCode() {
        int hash = mHashCode;
        if (hash == 0) {
            hash = mFlags;
            hash = hash * 31 + mName.hashCode();
            hash = hash * 31 + mRemoteFailureException.hashCode();
            hash = hash * 31 + mInterfaceNames.hashCode();
            hash = hash * 31 + mRemoteMethods.hashCode();
            mHashCode = hash;
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof RemoteInfo) {
            var other = (RemoteInfo) obj;
            return mFlags == other.mFlags && mName.equals(other.mName)
                && mRemoteFailureException.equals(other.mRemoteFailureException)
                && mInterfaceNames.equals(other.mInterfaceNames)
                && mRemoteMethods.equals(other.mRemoteMethods);
        }
        return false;
    }

    void writeTo(Pipe pipe) throws IOException {
        pipe.enableReferences();
        try {
            pipe.writeInt(mFlags);
            pipe.writeObject(mName);
            pipe.writeObject(mRemoteFailureException);
            writeStrings(pipe, mInterfaceNames);

            int size = mRemoteMethods.size();
            writeSize(pipe, size);
            for (RemoteMethod m : mRemoteMethods) {
                m.writeTo(pipe);
            }
        } finally {
            pipe.disableReferences();
        }
    }

    static void writeSize(Pipe pipe, int size) throws IOException {
        if (size < 128) {
            pipe.write(size);
        } else {
            pipe.writeInt(size | (1 << 31));
        }
    }

    static void writeStrings(Pipe pipe, Collection<String> c) throws IOException {
        writeSize(pipe, c.size());
        for (String s : c) {
            pipe.writeObject(s);
        }
    }

    static RemoteInfo readFrom(Pipe pipe) throws IOException {
        int flags = pipe.readInt();
        var name = ((String) pipe.readObject()).intern();
        var remoteFailureException = ((String) pipe.readObject()).intern();
        var interfaceNames = readInternedStringSet(pipe);

        SortedSet<RemoteMethod> remoteMethods;
        int size = readSize(pipe);
        if (size == 0) {
            remoteMethods = Collections.emptySortedSet();
        } else {
            remoteMethods = new TreeSet<RemoteMethod>();
            for (int i=0; i<size; i++) {
                remoteMethods.add(RemoteMethod.readFrom(pipe));
            }
        }

        return new RemoteInfo(flags, name, remoteFailureException, interfaceNames, remoteMethods);
    }

    static List<String> readInternedStringList(Pipe pipe) throws IOException {
        int size = readSize(pipe);
        if (size == 0) {
            return Collections.emptyList();
        }
        var list = new ArrayList<String>(size);
        for (int i=0; i<size; i++) {
            list.add(((String) pipe.readObject()).intern());
        }
        return list;
    }

    static Set<String> readInternedStringSet(Pipe pipe) throws IOException {
        int size = readSize(pipe);
        if (size == 0) {
            return Collections.emptySet();
        }
        var set = new TreeSet<String>();
        for (int i=0; i<size; i++) {
            set.add(((String) pipe.readObject()).intern());
        }
        return set;
    }

    static int readSize(Pipe pipe) throws IOException {
        int size = pipe.readByte();
        if (size < 0) {
            size &= 0x7f;
            size = (size << 24) | (pipe.readUnsignedByte() << 16) | pipe.readUnsignedShort();
        }
        return size;
    }
}
