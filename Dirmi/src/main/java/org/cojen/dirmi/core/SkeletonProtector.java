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

package org.cojen.dirmi.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicReference;

import java.rmi.Remote;

import java.security.AccessController;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;

import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.RuntimeClassFile;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.KeyFactory;
import org.cojen.util.SoftValuedHashMap;

import static org.cojen.dirmi.core.CodeBuilderUtil.*;

/**
 * Wraps Skeletons such that they have a ProtectionDomain applied.
 *
 * @author Brian S O'Neill
 */
class SkeletonProtector {
    private static final String SKELETON_FIELD_NAME = "skeleton";

    private static Map<Object, Factory> cCache;

    static {
        cCache = new SoftValuedHashMap<Object, Factory>();
    }

    /**
     * Returns a new Skeleton with a ProtectionDomain, or the current Skeleton
     * if the new domain matches.
     *
     * @param skeleton current wrapped or non-wrapped skeleton
     * @param domain current skeleton domain
     * @param newDomain ProtectionDomain to apply
     */
    static <R extends Remote> Skeleton<R> withProtectionDomain(Skeleton<R> skeleton,
                                                               ProtectionDomain domain,
                                                               ProtectionDomain newDomain)
    {
        final Object key;
        {
            if (domain == newDomain ||
                (key = createDomainKey(newDomain)).equals(createDomainKey(domain)))
            {
                return skeleton;
            }
        }

        Factory factory;
        synchronized (cCache) {
            factory = cCache.get(key);
            if (factory == null) {
                factory = new SkeletonProtector(newDomain).generateFactory();
                cCache.put(key, factory);
            }
        }

        return factory.wrapSkeleton(skeleton);
    }

    /**
     * Returns true if all aspects of the given domains are equal, excluding
     * their ClassLoaders. Null arguments are allowed.
     */
    static boolean equalDomains(ProtectionDomain a, ProtectionDomain b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return createDomainKey(a).equals(createDomainKey(b));
    }

    private static Object createDomainKey(ProtectionDomain domain) {
        // ProtectionDomain doesn't have an equals method, so break it apart
        // and add the elements to a composite key.

        Object domainKey = null;
        Object csKey = null;
        Object permsKey = null;
        Object principalsKey = null;

        if (domain != null) {
            domainKey = "";
            csKey = domain.getCodeSource();

            PermissionCollection pc = domain.getPermissions();
            if (pc != null) {
                List<Permission> permList = Collections.list(pc.elements());
                if (permList.size() == 1) {
                    permsKey = permList.get(0);
                } else if (permList.size() > 1) {
                    permsKey = new HashSet<Permission>(permList);
                }
            }

            Principal[] principals = domain.getPrincipals();
            if (principals != null && principals.length > 0) {
                if (principals.length == 1) {
                    principalsKey = principals[0];
                } else {
                    Set<Principal> principalSet = new HashSet<Principal>(principals.length);
                    for (Principal principal : principals) {
                        principalSet.add(principal);
                    }
                    principalsKey = principalSet;
                }
            }
        }

        return KeyFactory.createKey(new Object[] {domainKey, csKey, permsKey, principalsKey});
    }

    private final ProtectionDomain mDomain;

    private final AtomicReference<Object> mFactoryRef = new AtomicReference<Object>();

    private SkeletonProtector(ProtectionDomain domain) {
        mDomain = domain;
    }

    private Factory generateFactory() {
        return AccessController.doPrivileged(new PrivilegedAction<Factory>() {
            public Factory run() {
                Class<? extends Skeleton> skeletonClass = generateSkeleton();
                try {
                    Factory factory = new Factory(skeletonClass.getConstructor(Skeleton.class));
                    mFactoryRef.set(factory);
                    return factory;
                } catch (NoSuchMethodException e) {
                    NoSuchMethodError nsme = new NoSuchMethodError();
                    nsme.initCause(e);
                    throw nsme;
                }
            }
        });
    }

    private Class<? extends Skeleton> generateSkeleton() {
        RuntimeClassFile cf = new RuntimeClassFile
            (getClass().getName(), null, getClass().getClassLoader(), mDomain);
        cf.addInterface(Skeleton.class);
        cf.setSourceFile(getClass().getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc skelType = TypeDesc.forClass(Skeleton.class);
        final TypeDesc pdType = TypeDesc.forClass(ProtectionDomain.class);

        // Add fields.
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), SKELETON_FIELD_NAME, skelType);
        }

        // Add reference to factory.
        addStaticFactoryRef(cf, mFactoryRef);

        // Add constructor.
        {
            MethodInfo mi = cf.addConstructor(Modifiers.PUBLIC, new TypeDesc[] {skelType});
            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(SKELETON_FIELD_NAME, skelType);

            b.returnVoid();
        }

        // Add remote server access method.
        {
            TypeDesc remoteType = TypeDesc.forClass(Remote.class);
            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, "getRemoteServer", remoteType, null);
            CodeBuilder b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(SKELETON_FIELD_NAME, skelType);
            b.invokeInterface(skelType, "getRemoteServer", remoteType, null);
            b.returnValue(remoteType);
        }

        // Add invoke method.
        {
            TypeDesc[] params = new TypeDesc[] {TypeDesc.INT, INV_CHANNEL_TYPE, BATCH_INV_EX_TYPE};
            MethodInfo mi = cf.addMethod
                (Modifiers.PUBLIC, "invoke", TypeDesc.BOOLEAN, params);
            CodeBuilder b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(SKELETON_FIELD_NAME, skelType);
            b.loadLocal(b.getParameter(0));
            b.loadLocal(b.getParameter(1));
            b.loadLocal(b.getParameter(2));
            b.invokeInterface(skelType, "invoke", TypeDesc.BOOLEAN, params);
            b.returnValue(TypeDesc.BOOLEAN);
        }

        // Add withProtectionDomain method.
        {
            // Without access to the SkeletonSupport which can check if domain
            // is the same, always generate a new Skeleton instance from the
            // wrapped one.

            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "withProtectionDomain",
                                         skelType, new TypeDesc[] {pdType});
            CodeBuilder b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(SKELETON_FIELD_NAME, skelType);
            b.loadLocal(b.getParameter(0));
            b.invokeInterface(skelType, "withProtectionDomain", skelType, new TypeDesc[] {pdType});
            b.returnValue(skelType);
        }

        // Add the Unreferenced.unreferenced method.
        {
            MethodInfo mi = cf.addMethod(Modifiers.PUBLIC, "unreferenced", null, null);
            CodeBuilder b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(SKELETON_FIELD_NAME, skelType);
            b.invokeInterface(skelType, "unreferenced", null, null);
            b.returnVoid();
        }

        return cf.defineClass();
    }

    private static class Factory {
        private final Constructor<? extends Skeleton> mSkeletonCtor;

        Factory(Constructor<? extends Skeleton> ctor) {
            mSkeletonCtor = ctor;
        }

        public <R extends Remote> Skeleton<R> wrapSkeleton(Skeleton<R> skeleton) {
            if (skeleton == null) {
                throw new IllegalArgumentException("Skeleton is null");
            }
            Throwable error;
            try {
                return mSkeletonCtor.newInstance(skeleton);
            } catch (InstantiationException e) {
                error = e;
            } catch (IllegalAccessException e) {
                error = e;
            } catch (InvocationTargetException e) {
                error = e.getCause();
            }
            InternalError ie = new InternalError();
            ie.initCause(error);
            throw ie;
        }
    }
}
