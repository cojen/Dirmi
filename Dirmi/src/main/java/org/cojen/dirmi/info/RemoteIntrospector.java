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

package org.cojen.dirmi.info;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.lang.annotation.Annotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.cojen.classfile.MethodDesc;
import org.cojen.classfile.TypeDesc;
import org.cojen.util.WeakCanonicalSet;
import org.cojen.util.WeakIdentityMap;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.Batched;
import org.cojen.dirmi.CallMode;
import org.cojen.dirmi.Completion;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Timeout;
import org.cojen.dirmi.TimeoutParam;
import org.cojen.dirmi.TimeoutUnit;

/**
 * Supports examination of {@code Remote} types, returning all metadata
 * associated with it. As part of the examination, all annotations are gathered
 * up. All examined data is cached, so repeat examinations are fast, unless the
 * examination failed.
 *
 * @author Brian S O'Neill
 */
public class RemoteIntrospector {
    private static final Map<Class<?>, Class<?>> cInterfaceCache;
    private static final Map<Class<?>, RInfo> cInfoCache;
    private static final WeakCanonicalSet cParameterCache;

    static {
        cInterfaceCache = new WeakIdentityMap();
        cInfoCache = new WeakIdentityMap();
        cParameterCache = new WeakCanonicalSet();
    }

    static <T> RParameter<T> intern(RParameter<T> param) {
        return (RParameter<T>) cParameterCache.put(param);
    }

    /**
     * Returns the Remote interface implemented by the given Remote object.
     *
     * @param remoteObj remote object to examine
     * @throws IllegalArgumentException if remote is null or malformed
     */
    public static <R extends Remote> Class<? extends Remote> getRemoteType(R remoteObj)
        throws IllegalArgumentException
    {
        if (remoteObj == null) {
            throw new IllegalArgumentException("Remote object must not be null");
        }

        Class clazz = remoteObj.getClass();

        synchronized (cInterfaceCache) {
            Class theOne = cInterfaceCache.get(clazz);
            if (theOne != null) {
                return theOne;
            }

            // Only consider the one that implements Remote.

            for (Class iface : clazz.getInterfaces()) {
                if (Modifier.isPublic(iface.getModifiers()) &&
                    Remote.class.isAssignableFrom(iface))
                {
                    if (theOne != null) {
                        throw new IllegalArgumentException
                            ("At most one Remote interface may be directly implemented: " +
                             clazz.getName());
                    }
                    theOne = iface;
                }
            }

            if (theOne == null) {
                throw new IllegalArgumentException
                    ("No Remote types directly implemented: " + clazz.getName());
            }

            cInterfaceCache.put(clazz, theOne);
            return theOne;
        }
    }

    /**
     * @param remote remote interface to examine
     * @throws IllegalArgumentException if remote is null or malformed
     */
    public static RemoteInfo examine(Class<? extends Remote> remote)
        throws IllegalArgumentException
    {
        if (remote == null) {
            throw new IllegalArgumentException("Remote interface must not be null");
        }

        synchronized (cInfoCache) {
            RInfo info = cInfoCache.get(remote);
            if (info != null) {
                return info;
            }

            if (!remote.isInterface()) {
                throw new IllegalArgumentException("Remote type must be an interface: " + remote);
            }

            if (!Modifier.isPublic(remote.getModifiers())) {
                throw new IllegalArgumentException
                    ("Remote interface must be public: " + remote.getName());
            }

            if (!Remote.class.isAssignableFrom(remote)) {
                throw new IllegalArgumentException
                    ("Remote interface must extend java.rmi.Remote: " + remote.getName());
            }

            if (java.io.Serializable.class.isAssignableFrom(remote)) {
                throw new IllegalArgumentException
                    ("Remote interface cannot extend java.io.Serializable: " + remote.getName());
            }

            SortedMap<String, RMethod> methodMap = new TreeMap<String, RMethod>();

            for (Method m : remote.getMethods()) {
                if (!m.getDeclaringClass().isInterface()) {
                    continue;
                }

                String key;
                {
                    Class<?>[] params = m.getParameterTypes();
                    TypeDesc[] paramDescs = new TypeDesc[params.length];
                    for (int i=0; i<params.length; i++) {
                        paramDescs[i] = TypeDesc.forClass(params[i]);
                    }
                    MethodDesc desc = MethodDesc.forArguments
                        (TypeDesc.forClass(m.getReturnType()), paramDescs);
                    key = m.getName() + ':' + desc;
                }

                if (!methodMap.containsKey(key)) {
                    methodMap.put(key, new RMethod(m));
                    continue;
                }

                RMethod existing = methodMap.get(key);
                RMethod candidate = new RMethod(m);
                
                if (existing.equals(candidate)) {
                    continue;
                }

                // Same method inherited from multiple parent interfaces. Only
                // exceptions are allowed to differ. If so, select the
                // intersection of the exceptions.
                candidate = existing.intersectExceptions(candidate);

                methodMap.put(key, candidate);
            }

            for (RMethod method : methodMap.values()) {
                if (method.isAsynchronous()) {
                    if (method.getReturnType() != null) {
                        Class returnType = method.getReturnType().getType();
                        if (Pipe.class == returnType) {
                            if (method.isBatched()) {
                                throw new IllegalArgumentException
                                    ("Asynchronous batched method cannot return Pipe: " +
                                     method.methodDesc());
                            }
                            // Verify one parameter is a pipe.
                            int count = 0;
                            for (RemoteParameter param : method.getParameterTypes()) {
                                if (param.getType() == returnType) {
                                    count++;
                                }
                            }
                            if (count != 1) {
                                throw new IllegalArgumentException
                                    ("Asynchronous method which returns Pipe must have " +
                                     "exactly one matching Pipe input parameter: " +
                                     method.methodDesc());
                            }
                        } else if (Future.class == returnType || Completion.class == returnType) {
                            // Okay.
                        } else if (method.isBatched()) {
                            if (!Remote.class.isAssignableFrom(returnType)) {
                                throw new IllegalArgumentException
                                    ("Asynchronous batched method must return void, " +
                                     "a Remote object, Completion or Future: "
                                     + method.methodDesc());
                            }
                        } else {
                            throw new IllegalArgumentException
                                ("Asynchronous method must return void, Pipe, " +
                                 "Completion or Future: " + method.methodDesc());
                        }
                    }
                }
            }

            // Gather all implemented interfaces which implement Remote.
            SortedSet<String> interfaces = new TreeSet<String>();
            gatherRemoteInterfaces(interfaces, remote);

            info = new RInfo(remote, interfaces, new LinkedHashSet<RMethod>(methodMap.values()));

            cInfoCache.put(remote, info);

            // Now that RInfo is in the cache, call resolve to check remote
            // parameters.
            try {
                info.resolve();
            } catch (IllegalArgumentException e) {
                cInfoCache.remove(remote);
                throw e;
            }

            return info;
        }
    }

    private static void gatherRemoteInterfaces(Set<String> interfaces, Class clazz) {
        for (Class i : clazz.getInterfaces()) {
            if (Remote.class.isAssignableFrom(i)) {
                if (interfaces.add(i.getName())) {
                    gatherRemoteInterfaces(interfaces, i);
                }
            }
        }
        if (clazz.isInterface() && Remote.class.isAssignableFrom(clazz)) {
            interfaces.add(clazz.getName());
        }
    }

    private RemoteIntrospector() {
    }

    private static class RInfo implements RemoteInfo {
        private static final long serialVersionUID = 1L;

        // Id is assigned by resolve method.
        private long mId;

        private final String mName;
        private final SortedSet<String> mInterfaceNames;
        private final Set<RMethod> mMethods;

        private final transient RParameter<? extends Throwable> mRemoteFailureException;
        private final transient boolean mRemoteFailureExceptionDeclared;

        private final transient long mTimeout;
        private final transient TimeUnit mTimeoutUnit;

        private transient Map<String, Set<RMethod>> mMethodsByName;

        RInfo(Class<? extends Remote> remote, SortedSet<String> interfaces, Set<RMethod> methods) {
            mName = remote.getName();
            mInterfaceNames = Collections.unmodifiableSortedSet(interfaces);
            mMethods = Collections.unmodifiableSet(methods);

            {
                RemoteFailure ann  = remote.getAnnotation(RemoteFailure.class);
                if (ann == null) {
                    mRemoteFailureException = RParameter.make(RemoteException.class);
                    mRemoteFailureExceptionDeclared = true;
                } else {
                    mRemoteFailureException = RParameter.make(ann.exception());
                    mRemoteFailureExceptionDeclared = ann.declared();
                }
            }

            {
                Timeout ann = remote.getAnnotation(Timeout.class);
                if (ann == null) {
                    mTimeout = -1;
                } else {
                    long timeout = ann.value();
                    mTimeout = timeout < 0 ? -1 : timeout;
                }
            }

            {
                TimeoutUnit ann = remote.getAnnotation(TimeoutUnit.class);
                if (ann == null) {
                    mTimeoutUnit = TimeUnit.MILLISECONDS;
                } else {
                    TimeUnit unit = ann.value();
                    mTimeoutUnit = (unit == null) ? TimeUnit.MILLISECONDS : unit;
                }
            }
        }

        public String getName() {
            return mName;
        }

        public long getInfoId() {
            return mId;
        }

        public Set<String> getInterfaceNames() {
            return mInterfaceNames;
        }

        public Set<? extends RemoteMethod> getRemoteMethods() {
            return mMethods;
        }

        public Set<? extends RemoteMethod> getRemoteMethods(String name) {
            if (mMethodsByName == null) {
                Map<String, Set<RMethod>> methodsByName = new HashMap<String, Set<RMethod>>();

                for (RMethod method : mMethods) {
                    String methodName = method.getName();
                    Set<RMethod> set = methodsByName.get(methodName);
                    if (set == null) {
                        set = new LinkedHashSet<RMethod>();
                        methodsByName.put(methodName, set);
                    }
                    set.add(method);
                }

                // Pass through again, making sure each contained set is unmodifiable.
                for (Map.Entry<String, Set<RMethod>> entry : methodsByName.entrySet()) {
                    entry.setValue(Collections.unmodifiableSet(entry.getValue()));
                }

                mMethodsByName = methodsByName;
            }

            Set<? extends RemoteMethod> methods = mMethodsByName.get(name);
            if (methods == null) {
                methods = Collections.emptySet();
            }
            return methods;
        }

        public RemoteMethod getRemoteMethod(String name, RemoteParameter... params)
            throws NoSuchMethodException
        {
            int paramsLength = params == null ? 0 : params.length;
            search:
            for (RemoteMethod method : getRemoteMethods(name)) {
                List<? extends RemoteParameter> paramTypes = method.getParameterTypes();
                if (paramTypes.size() == paramsLength) {
                    for (int i=0; i<paramsLength; i++) {
                        if (!paramTypes.get(i).equalTypes(params[i])) {
                            continue search;
                        }
                    }
                    return method;
                }
            }
            throw new NoSuchMethodException(name);
        }

        @Override
        public int hashCode() {
            return mName.hashCode() + (int) (mId >> 32) + (int) mId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RInfo) {
                RInfo other = (RInfo) obj;
                return mName.equals(other.mName) && (mId == other.mId) &&
                    mInterfaceNames.equals(other.mInterfaceNames) &&
                    getRemoteMethods().equals(other.getRemoteMethods());
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("RemoteInfo {id=");
            b.append(mId);
            b.append(", name=");
            b.append(mName);
            b.append('}');
            return b.toString();
        }

        RParameter<? extends Throwable> getRemoteFailureException() {
            return mRemoteFailureException;
        }

        boolean isRemoteFailureExceptionDeclared() {
            return mRemoteFailureExceptionDeclared;
        }

        long getTimeout() {
            return mTimeout;
        }

        TimeUnit getTimeoutUnit() {
            return mTimeoutUnit;
        }

        void resolve() {
            Set<Class> validExceptions = new HashSet<Class>();
            for (RMethod method : mMethods) {
                method.resolve(this, validExceptions);
            }

            // Now assign the id by computing a hashcode of all elements.

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }

            OutputStream nullOut = new OutputStream() {
                public void write(int b) {}
                public void write(byte[] b, int off, int len) {}
            };

            DataOutput digestOutput = new DataOutputStream
                (new DigestOutputStream(nullOut, digest));

            try {
                mixIn(digestOutput);
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            byte[] hash = digest.digest();

            long id = 0;
            for (int i=0; i<hash.length; i++) {
                id ^= (hash[i] & 0xffL) << ((i & 7) << 3);
            }

            mId = id;

            // Finally, assign method ids using a portion of the secure hash as
            // a base. Even if a collision exists in the 64-bit identifier,
            // method ids might not collide.

            int methodId = 0;
            for (int i=0; i<4; i++) {
                methodId ^= (hash[i] & 0xff) << ((i & 3) << 3);
            }

            for (RMethod method : mMethods) {
                if (method.isAsynchronous()) {
                    if ((methodId & 1) == 0) {
                        methodId++;
                    }
                } else if ((methodId & 1) != 0) {
                    methodId++;
                }
                method.setId(methodId);
                methodId++;
            }
        }

        void mixIn(DataOutput digest) throws IOException {
            digest.writeUTF(mName);
            for (String name : mInterfaceNames) {
                digest.writeUTF(name);
            }
            for (RMethod method : mMethods) {
                method.mixIn(digest);
            }
        }
    }

    private static class RMethod implements RemoteMethod {
        private static final long serialVersionUID = 1L;

        // Id is assigned after RInfo has been resolved.
        private int mId;

        private final String mName;
        private RParameter mReturnType;
        private List<RParameter<Object>> mParameterTypes;
        private final SortedSet<RParameter<Throwable>> mExceptionTypes;

        private final CallMode mCallMode;
        private final boolean mBatched;

        private RParameter<? extends Throwable> mRemoteFailureException;
        private boolean mRemoteFailureExceptionDeclared;

        private long mTimeout;
        private TimeUnit mTimeoutUnit;

        private transient Method mMethod;

        RMethod(Method m) {
            if (!Modifier.isPublic(m.getModifiers())) {
                throw new IllegalArgumentException
                    ("Remote method must be public: " + methodDesc(m));
            }

            mName = m.getName();

            if (m.isAnnotationPresent(Batched.class)) {
                mBatched = true;
                mCallMode = CallMode.EVENTUAL;
                if (m.isAnnotationPresent(Asynchronous.class)) {
                    throw new IllegalArgumentException
                        ("Method cannot be annotated as both @Asynchronous and @Batched: " +
                         methodDesc(m));
                }
            } else {
                mBatched = false;
                Asynchronous ann = m.getAnnotation(Asynchronous.class);
                if (ann == null) {
                    mCallMode = null;
                } else {
                    mCallMode = ann.value();
                }
            }

            // First pass, treat all params as serialized. Resolve on second pass.
            // This allows remote methods to pass instances of declaring class without
            // causing the introspector to overflow the stack.

            Class<?> returnType = m.getReturnType();
            if (returnType == null) {
                mReturnType = null;
            } else {
                if (!Modifier.isPublic(returnType.getModifiers())) {
                    throw new IllegalArgumentException
                        ("Remote method return type must be public: " + methodDesc(m));
                }
                mReturnType = RParameter.make(returnType, mCallMode != null);
            }

            Class<?>[] paramsTypes = m.getParameterTypes();
            if (paramsTypes == null || paramsTypes.length == 0) {
                mParameterTypes = null;
            } else {
                mParameterTypes = new ArrayList<RParameter<Object>>(paramsTypes.length);
                Annotation[][] paramsAnns = m.getParameterAnnotations();

                // First pass, find any timeout and unit parameters.

                int timeoutValueParam = -1;
                int timeoutUnitParam = -1;
                boolean defaultTimeoutUnitParam = false;

                for (int i=0; i<paramsTypes.length; i++) {
                    Class paramType = paramsTypes[i];

                    if (!Modifier.isPublic(paramType.getModifiers())) {
                        throw new IllegalArgumentException
                            ("Remote method parameter types must be public: " + methodDesc(m));
                    }

                    Annotation[] paramAnns = paramsAnns[i];

                    if (paramAnns != null) {
                        for (Annotation ann : paramAnns) {
                            if (!(ann instanceof TimeoutParam)) {
                                continue;
                            }

                            if (paramType == TimeUnit.class) {
                                if (timeoutUnitParam >= 0 && !defaultTimeoutUnitParam) {
                                    throw new IllegalArgumentException
                                        ("At most one timeout unit parameter allowed: " +
                                         methodDesc(m));
                                }

                                timeoutUnitParam = i;
                                defaultTimeoutUnitParam = false;

                                continue;
                            }

                            TypeDesc desc = TypeDesc.forClass(paramType).toPrimitiveType();
                            if (desc != null &&
                                desc != TypeDesc.BOOLEAN && desc != TypeDesc.CHAR)
                            {
                                if (timeoutValueParam >= 0) {
                                    throw new IllegalArgumentException
                                        ("At most one timeout value parameter allowed: " +
                                         methodDesc(m));
                                }

                                timeoutValueParam = i;

                                // If next parameter type is a TimeUnit, it is
                                // selected to be the timeout unit parameter.
                                if (timeoutUnitParam < 0 &&
                                    i + 1 < paramsTypes.length &&
                                    paramsTypes[i + 1] == TimeUnit.class)
                                {
                                    timeoutUnitParam = i + 1;
                                    defaultTimeoutUnitParam = true;
                                }

                                continue;
                            }

                            throw new IllegalArgumentException
                                ("Timeout parameter can only apply to primitive " +
                                 "numerical types or TimeUnit, not " +
                                 TypeDesc.forClass(paramType).getFullName() +
                                 ": " + methodDesc(m));
                        }
                    }
                }

                for (int i=0; i<paramsTypes.length; i++) {
                    Class paramType = paramsTypes[i];
                    mParameterTypes.add(RParameter.make
                                        (paramType, mCallMode != null,
                                         timeoutValueParam == i, timeoutUnitParam == i));
                }
            }

            Class<?>[] exceptionTypes = m.getExceptionTypes();
            if (exceptionTypes == null || exceptionTypes.length == 0) {
                mExceptionTypes = null;
            } else {
                SortedSet<RParameter<Throwable>> set = new TreeSet<RParameter<Throwable>>();
                for (Class exceptionType : exceptionTypes) {
                    if (!Modifier.isPublic(exceptionType.getModifiers())) {
                        throw new IllegalArgumentException
                            ("Remote method declared exception types must be public: " +
                             methodDesc(m, exceptionType));
                    }
                    set.add(RParameter.make(exceptionType));
                }
                mExceptionTypes = Collections.unmodifiableSortedSet(set);
            }

            {
                RemoteFailure ann  = m.getAnnotation(RemoteFailure.class);
                if (ann == null) {
                    // Use inherited values from RemoteInfo, filled in when
                    // resolve is called.
                } else {
                    mRemoteFailureException = RParameter.make(ann.exception());
                    mRemoteFailureExceptionDeclared = ann.declared();
                }
            }

            {
                Timeout ann = m.getAnnotation(Timeout.class);
                if (ann == null) {
                    // Use inherited values from RemoteInfo, filled in when
                    // resolve is called. Store min value to indicate this.
                    mTimeout = Long.MIN_VALUE;
                } else {
                    long timeout = ann.value();
                    mTimeout = timeout < 0 ? -1 : timeout;
                }
            }

            {
                TimeoutUnit ann = m.getAnnotation(TimeoutUnit.class);
                if (ann == null) {
                    // Use inherited values from RemoteInfo, filled in when
                    // resolve is called.
                } else {
                    TimeUnit unit = ann.value();
                    mTimeoutUnit = (unit == null) ? TimeUnit.MILLISECONDS : unit;
                }
            }

            // Hang on to this until resolve is called.
            mMethod = m;
        }

        private RMethod(RMethod existing, SortedSet<RParameter<Throwable>> exceptionTypes) {
            mId = existing.mId;
            mName = existing.mName;
            mReturnType = existing.mReturnType;
            mParameterTypes = existing.mParameterTypes;
            mExceptionTypes = Collections.unmodifiableSortedSet(exceptionTypes);

            mCallMode = existing.mCallMode;
            mBatched = existing.mBatched;

            mRemoteFailureException = existing.mRemoteFailureException;
            mRemoteFailureExceptionDeclared = existing.mRemoteFailureExceptionDeclared;

            mMethod = existing.mMethod;
        }

        private static <E> List<E> unfixList(List<E> list) {
            if (list == null) {
                list = Collections.emptyList();
            }
            return list;
        }

        public String getName() {
            return mName;
        }

        public int getMethodId() {
            return mId;
        }

        public RemoteParameter getReturnType() {
            return mReturnType;
        }

        public List<? extends RemoteParameter<?>> getParameterTypes() {
            if (mParameterTypes == null) {
                return Collections.emptyList();
            }
            return mParameterTypes;
        }

        public Set<? extends RemoteParameter<? extends Throwable>> getExceptionTypes() {
            if (mExceptionTypes == null) {
                return Collections.emptySet();
            }
            return mExceptionTypes;
        }

        public String getSignature() {
            return getSignature(null);
        }

        public boolean isAsynchronous() {
            return mCallMode != null;
        }

        public CallMode getAsynchronousCallMode() {
            return mCallMode;
        }

        public boolean isBatched() {
            return mBatched;
        }

        public RemoteParameter<? extends Throwable> getRemoteFailureException() {
            return mRemoteFailureException;
        }

        public boolean isRemoteFailureExceptionDeclared() {
            return mRemoteFailureExceptionDeclared;
        }

        public long getTimeout() {
            return mTimeout;
        }

        public TimeUnit getTimeoutUnit() {
            return mTimeoutUnit;
        }

        @Override
        public int hashCode() {
            return mName.hashCode() + mId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RMethod) {
                RMethod other = (RMethod) obj;
                return mName.equals(other.mName) && (mId == other.mId) &&
                    getParameterTypes().equals(other.getParameterTypes()) &&
                    getExceptionTypes().equals(other.getExceptionTypes()) &&
                    (mCallMode == other.mCallMode) &&
                    (mBatched == other.mBatched) &&
                    (mRemoteFailureException == other.getRemoteFailureException()) &&
                    (mRemoteFailureExceptionDeclared == other.isRemoteFailureExceptionDeclared());
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("RemoteMethod {id=");
            b.append(mId);
            b.append(", sig=\"");
            b.append(getSignature(null));
            b.append('"');
            b.append('}');
            return b.toString();
        }

        /**
         * @param className optional
         */
        String getSignature(String className) {
            StringBuilder b = new StringBuilder();
            b.append(getReturnType() == null ? "void" : getReturnType());
            b.append(' ');
            if (className != null) {
                b.append(className);
                b.append('.');
            }
            b.append(getName());

            b.append('(');
            int count = 0;
            for (RemoteParameter param : getParameterTypes()) {
                if (count++ > 0) {
                    b.append(", ");
                }
                b.append(param);
            }
            b.append(')');

            Set<? extends RemoteParameter> exceptions = getExceptionTypes();
            if (exceptions.size() > 0) {
                b.append(" throws ");
                count = 0;
                for (RemoteParameter param : exceptions) {
                    if (count++ > 0) {
                        b.append(", ");
                    }
                    b.append(param);
                }
            }

            return b.toString();
        }

        RMethod intersectExceptions(RMethod other) {
            if (this == other) {
                return this;
            }
            if (!mName.equals(other.mName)) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("name mismatch");
            }
            if (mId != other.mId) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("id mismatch");
            }
            if (!getParameterTypes().equals(other.getParameterTypes())) {
                // This indicates a bug in RemoteIntrospector.
                throw new IllegalArgumentException("parameter types mismatch");
            }

            if (mCallMode != other.mCallMode) {
                // This is user error.
                throw new IllegalArgumentException
                    ("Inherited methods conflict in use of @Asynchronous annotation: " +
                     methodDesc() + " and " + other.methodDesc());
            }

            if (mBatched != other.mBatched) {
                // This is user error.
                throw new IllegalArgumentException
                    ("Inherited methods conflict in use of @Batched annotation: " +
                     methodDesc() + " and " + other.methodDesc());
            }

            SortedSet<RParameter<Throwable>> subset = new TreeSet<RParameter<Throwable>>();

            for (RParameter exceptionType : mExceptionTypes) {
                if (other.declaresException(exceptionType)) {
                    subset.add(exceptionType);
                }
            }

            for (RParameter exceptionType : other.mExceptionTypes) {
                if (this.declaresException(exceptionType)) {
                    subset.add(exceptionType);
                }
            }

            return new RMethod(this, subset);
        }

        boolean declaresException(RemoteParameter exceptionType) {
            return declaresException(exceptionType.getType());
        }

        boolean declaresException(Class<?> exceptionType) {
            if (mExceptionTypes == null) {
                return false;
            }
            for (RemoteParameter declared : mExceptionTypes) {
                if (declared.getType().isAssignableFrom(exceptionType)) {
                    return true;
                }
            }
            return false;
        }

        void resolve(RInfo info, Set<Class> validExceptions) {
            if (mParameterTypes != null) {
                int size = mParameterTypes.size();

                // If any individual parameter cannot be unshared, then none
                // can be unshared. This is because a complex serialized object
                // might refer to any parameter or even itself.
                boolean noneUnshared = false;
                for (int i=0; i<size; i++) {
                    if (!mParameterTypes.get(i).isUnshared()) {
                        noneUnshared = true;
                        break;
                    }
                }

                for (int i=0; i<size; i++) {
                    RParameter param = mParameterTypes.get(i);
                    Class type = param.getType();

                    boolean unshared = !noneUnshared && param.isUnshared();
                    // Can only be truly unshared if no other parameter is of same type.
                    if (unshared) {
                        for (int j=i+1; j<size; j++) {
                            RParameter jp = mParameterTypes.get(j);
                            if (type == jp.getType()) {
                                unshared = false;
                                // Mark parameter as unshared for when we see it again.
                                mParameterTypes.set(j, jp.toUnshared(false));
                                break;
                            }
                        }
                    }

                    mParameterTypes.set(i, param.toUnshared(unshared));
                }

                mParameterTypes = Collections.unmodifiableList(mParameterTypes);
            }

            if (mRemoteFailureException == null) {
                // Inherit from remote interface.
                mRemoteFailureException = info.getRemoteFailureException();
                mRemoteFailureExceptionDeclared = info.isRemoteFailureExceptionDeclared();
            }

            if (mTimeout == Long.MIN_VALUE) {
                // Inherit from remote interface.
                mTimeout = info.getTimeout();
            }

            if (mTimeoutUnit == null) {
                // Inherit from remote interface.
                mTimeoutUnit = info.getTimeoutUnit();
            }

            Class<? extends Throwable> failExClass = mRemoteFailureException.getType();

            if (!failExClass.isAssignableFrom(RemoteException.class)) {
                if (!Modifier.isPublic(failExClass.getModifiers())) {
                    throw new IllegalArgumentException
                        ("Remote failure exception must be public: " + failExClass.getName());
                }

                // Check for valid constructor.
                validCheck: {
                    if (validExceptions.contains(failExClass)) {
                        break validCheck;
                    }
                    for (Constructor ctor : failExClass.getConstructors()) {
                        Class[] paramTypes = ctor.getParameterTypes();
                        if (paramTypes.length != 1) {
                            continue;
                        }
                        if (paramTypes[0].isAssignableFrom(RemoteException.class)) {
                            validExceptions.add(failExClass);
                            break validCheck;
                        }
                    }
                    throw new IllegalArgumentException
                        ("Remote failure exception does not have a public single-argument " +
                         "constructor which accepts a RemoteException: " + failExClass.getName());
                }
            }

            if (isChecked(failExClass) && isRemoteFailureExceptionDeclared()) {
                if (!declaresException(failExClass)) {
                    String message = "Method must declare throwing " + failExClass.getName();
                    if (isBatched() || !isAsynchronous()) {
                        message += " (or a superclass)";
                    }
                    throw new IllegalArgumentException
                        (message + ": " + methodDesc() +
                         "; use @RemoteFailure to override behavior");
                }
            }

            if (isAsynchronous() && !isBatched()) {
                // Asynchronous non-batched methods can only declare throwing
                // the remote failure exception.
                Class<?>[] exceptionTypes = mMethod.getExceptionTypes();
                if (exceptionTypes != null) {
                    for (Class exceptionType : exceptionTypes) {
                        if (isChecked(exceptionType) && exceptionType != failExClass) {
                            throw new IllegalArgumentException
                                ("Asynchronous method can only declare throwing " +
                                 failExClass.getName() + ": " +
                                 methodDesc(mMethod, exceptionType) +
                                 "; use @RemoteFailure override behavior");
                        }
                    }
                }
            }

            // Won't need this again.
            mMethod = null;
        }

        private static boolean isChecked(Class<? extends Throwable> exClass) {
            return !(RuntimeException.class.isAssignableFrom(exClass) ||
                     Error.class.isAssignableFrom(exClass));
        }

        static String methodDesc(Method m) {
            return methodDesc(m, null);
        }

        static String methodDesc(Method m, Class exceptionType) {
            String name = m.getDeclaringClass().getName() + '.' + m.getName();
            StringBuilder b = new StringBuilder();
            b.append('"');
            b.append(MethodDesc.forMethod(m).toMethodSignature(name));
            if (exceptionType != null) {
                b.append(" throws ");
                b.append(exceptionType.getName());
            }
            b.append('"');
            return b.toString();
        }

        String methodDesc() {
            return methodDesc(mMethod);
        }

        void mixIn(DataOutput digest) throws IOException {
            digest.writeUTF(mName);
            if (mReturnType != null) {
                mReturnType.mixIn(digest);
            }
            if (mParameterTypes != null) {
                for (RParameter<?> param : mParameterTypes) {
                    param.mixIn(digest);
                }
            }
            if (mExceptionTypes != null) {
                for (RParameter<?> type : mExceptionTypes) {
                    type.mixIn(digest);
                }
            }
            if (mCallMode != null) {
                digest.writeUTF(mCallMode.name());
            }
            digest.writeBoolean(mBatched);
            mRemoteFailureException.mixIn(digest);
            digest.writeBoolean(mRemoteFailureExceptionDeclared);
            digest.writeLong(mTimeout);
            digest.writeUTF(mTimeoutUnit.name());
        }

        void setId(int id) {
            mId = id;
        }
    }

    private static class RParameter<T> implements RemoteParameter<T>, Comparable<RParameter> {
        private static final long serialVersionUID = 1L;

        private static final int FLAG_UNSHARED = 1;
        private static final int FLAG_TIMEOUT = 2;
        private static final int FLAG_TIMEOUT_UNIT = 4;

        static <T> RParameter<T> make(Class<T> type) {
            return make(type, false, false, false);
        }

        static <T> RParameter<T> make(Class<T> type, boolean asynchronous) {
            return make(type, asynchronous, false, false);
        }

        static <T> RParameter<T> make(Class<T> type, boolean asynchronous,
                                      boolean timeout, boolean timeoutUnit)
        {
            if (type == void.class || type == null) {
                return null;
            }

            boolean unshared = type.isPrimitive() ||
                String.class.isAssignableFrom(type) ||
                (asynchronous && Pipe.class.isAssignableFrom(type)) ||
                TypeDesc.forClass(type).toPrimitiveType() != null;

            int flags =
                (unshared ? FLAG_UNSHARED : 0) |
                (timeout ? FLAG_TIMEOUT : 0) |
                (timeoutUnit ? FLAG_TIMEOUT_UNIT : 0);

            return intern(new RParameter<T>(type, flags));
        }

        private final Class<T> mType;
        private final int mFlags;

        private RParameter(Class<T> type, int flags) {
            mType = type;
            mFlags = flags;
        }

        public Class<T> getType() {
            return mType;
        }

        public boolean isUnshared() {
            return (mFlags & FLAG_UNSHARED) != 0;
        }

        public boolean isTimeout() {
            return (mFlags & FLAG_TIMEOUT) != 0;
        }

        public boolean isTimeoutUnit() {
            return (mFlags & FLAG_TIMEOUT_UNIT) != 0;
        }

        public boolean equalTypes(RemoteParameter other) {
            if (this == other) {
                return true;
            }
            return getType().getName().equals(other.getType().getName());
        }

        @Override
        public int hashCode() {
            return mType.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RParameter) {
                RParameter other = (RParameter) obj;
                return mFlags == other.mFlags && mType.equals(other.mType);
            }
            return false;
        }

        @Override
        public String toString() {
            return TypeDesc.forClass(mType).getFullName();
        }

        @Override
        public int compareTo(RParameter other) {
            int compare = mType.getName().compareTo(other.mType.getName());
            if (compare == 0) {
                if (mFlags < other.mFlags) {
                    compare = -1;
                } else if (mFlags > other.mFlags) {
                    compare = 1;
                }
            }
            return compare;
        }

        RParameter toUnshared(boolean unshared) {
            int flags = unshared ? (mFlags | FLAG_UNSHARED) : (mFlags & ~FLAG_UNSHARED);
            if (flags == mFlags) {
                return this;
            }
            return intern(new RParameter(mType, flags));
        }

        void mixIn(DataOutput digest) throws IOException {
            digest.writeUTF(mType.getName());
            digest.writeInt(mFlags);
        }

        private Object readResolve() {
            return intern(this);
        }
    }
}
