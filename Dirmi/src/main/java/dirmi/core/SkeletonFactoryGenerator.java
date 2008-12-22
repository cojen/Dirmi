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

package dirmi.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.io.DataInput;
import java.io.IOException;

import java.rmi.Remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.TypeDesc;

import org.cojen.util.ClassInjector;
import org.cojen.util.KeyFactory;
import org.cojen.util.SoftValuedHashMap;

import dirmi.CallMode;
import dirmi.MalformedRemoteObjectException;
import dirmi.Pipe;

import dirmi.info.RemoteInfo;
import dirmi.info.RemoteIntrospector;
import dirmi.info.RemoteMethod;
import dirmi.info.RemoteParameter;

import static dirmi.core.CodeBuilderUtil.*;

/**
 * Generates {@link SkeletonFactory} instances for any given Remote type.
 *
 * @author Brian S O'Neill
 */
public class SkeletonFactoryGenerator<R extends Remote> {
    private static final String SUPPORT_FIELD_NAME = "support";
    private static final String REMOTE_FIELD_NAME = "remote";

    private static final Map<Object, SkeletonFactory<?>> cCache;

    static {
        cCache = new SoftValuedHashMap<Object, SkeletonFactory<?>>();
    }

    /**
     * Returns a new or cached SkeletonFactory.
     *
     * @param type
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type)
        throws IllegalArgumentException
    {
        synchronized (cCache) {
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(type);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(type).generateFactory();
                cCache.put(type, factory);
            }
            return factory;
        }
    }

    /**
     * Returns a new or cached SkeletonFactory for use with remote objects
     * served by batched methods.
     *
     * @param type
     * @param remoteInfo remote type as supported by remote server
     * @throws IllegalArgumentException if type is null or malformed
     */
    public static <R extends Remote> SkeletonFactory<R> getSkeletonFactory(Class<R> type,
                                                                           RemoteInfo remoteInfo)
    {
        synchronized (cCache) {
            Object key = KeyFactory.createKey(new Object[] {type, remoteInfo});
            SkeletonFactory<R> factory = (SkeletonFactory<R>) cCache.get(key);
            if (factory == null) {
                factory = new SkeletonFactoryGenerator<R>(type, remoteInfo).generateFactory();
                cCache.put(key, factory);
            }
            return factory;
        }
    }

    private final Class<R> mType;
    private final RemoteInfo mInfo;

    private final RemoteInfo mLocalInfo;
    // Is set only if mLocalInfo is null.
    private final String mMalformedInfoMessage;

    private SkeletonFactoryGenerator(Class<R> type) throws IllegalArgumentException {
        mType = type;
        mInfo = mLocalInfo = RemoteIntrospector.examine(type);
        mMalformedInfoMessage = null;
    }

    private SkeletonFactoryGenerator(Class<R> type, RemoteInfo remoteInfo) {
        mType = type;
        mInfo = remoteInfo;

        RemoteInfo localInfo;
        String malformed;

        try {
            localInfo = RemoteIntrospector.examine(type);
            malformed = null;
        } catch (IllegalArgumentException e) {
            localInfo = null;
            malformed = e.getMessage();
        }

        mLocalInfo = localInfo;
        mMalformedInfoMessage = malformed;
    }

    private SkeletonFactory<R> generateFactory() {
        if (mInfo.getRemoteMethods().size() == 0) {
            return EmptySkeletonFactory.THE;
        }

        CodeBuilderUtil.IdentifierSet.setMethodIds(mInfo);
        try {
            Class<? extends Skeleton> skeletonClass = generateSkeleton();
            try {
                SkeletonFactory<R> factory = new Factory<R>
                    (skeletonClass.getConstructor(SkeletonSupport.class, mType));
                invokeFactoryRefMethod(skeletonClass, factory);
                return factory;
            } catch (IllegalAccessException e) {
                throw new Error(e);
            } catch (InvocationTargetException e) {
                throw new Error(e);
            } catch (NoSuchMethodException e) {
                NoSuchMethodError nsme = new NoSuchMethodError();
                nsme.initCause(e);
                throw nsme;
            }
        } finally {
            CodeBuilderUtil.IdentifierSet.clearIds();
        }
    }

    private Class<? extends Skeleton> generateSkeleton() {
        ClassInjector ci = ClassInjector.create
            (cleanClassName(mType.getName()) + "$Skeleton", mType.getClassLoader());

        ClassFile cf = new ClassFile(ci.getClassName());
        cf.addInterface(Skeleton.class);
        cf.setSourceFile(SkeletonFactoryGenerator.class.getName());
        cf.markSynthetic();
        cf.setTarget("1.5");

        final TypeDesc remoteType = TypeDesc.forClass(mType);

        // Add fields
        {
            cf.addField(Modifiers.PRIVATE.toFinal(true), SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            cf.addField(Modifiers.PRIVATE.toFinal(true), REMOTE_FIELD_NAME, remoteType);
        }

        // Add static method to assign identifiers.
        addInitMethodAndFields(cf, mInfo);

        // Add constructor
        {
            MethodInfo mi = cf.addConstructor
                (Modifiers.PUBLIC, new TypeDesc[] {SKEL_SUPPORT_TYPE, remoteType});

            CodeBuilder b = new CodeBuilder(mi);

            b.loadThis();
            b.invokeSuperConstructor(null);

            b.loadThis();
            b.loadLocal(b.getParameter(0));
            b.storeField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
            b.loadThis();
            b.loadLocal(b.getParameter(1));
            b.storeField(REMOTE_FIELD_NAME, remoteType);

            b.returnVoid();
        }

        // Add the all-important invoke method
        MethodInfo mi = cf.addMethod
            (Modifiers.PUBLIC, "invoke", TypeDesc.BOOLEAN,
             new TypeDesc[] {VERSIONED_IDENTIFIER_TYPE, IDENTIFIER_TYPE, INV_CHANNEL_TYPE});
        CodeBuilder b = new CodeBuilder(mi);

        // Note: This implementation ignores the object id parameter. Instances
        // can only serve a single object.

        LocalVariable methodIDVar = b.getParameter(1);
        LocalVariable channelVar = b.getParameter(2);

        // Have a reference to the InputStream for reading parameters.
        b.loadLocal(channelVar);
        b.invokeInterface(INV_CHANNEL_TYPE, "getInputStream", INV_IN_TYPE, null);
        LocalVariable invInVar = b.createLocalVariable(null, INV_IN_TYPE);
        b.storeLocal(invInVar);

        Set<? extends RemoteMethod> methods = mInfo.getRemoteMethods();

        // Create a switch statement that operates on method identifier
        // hashcodes, accounting for possible collisions.

        Map<Integer, List<RemoteMethod>> hashToMethodMap =
            new LinkedHashMap<Integer, List<RemoteMethod>>(methods.size());
        for (RemoteMethod method : methods) {
            Integer key = method.getMethodID().hashCode();
            List<RemoteMethod> matches = hashToMethodMap.get(key);
            if (matches == null) {
                matches = new ArrayList<RemoteMethod>(2);
                hashToMethodMap.put(key, matches);
            }
            matches.add(method);
        }

        int caseCount = hashToMethodMap.size();
        int[] cases = new int[caseCount];
        Label[] switchLabels = new Label[caseCount];
        Label defaultLabel = b.createLabel();

        {
            int i = 0;
            for (Integer key : hashToMethodMap.keySet()) {
                cases[i] = key;
                switchLabels[i] = b.createLabel();
                i++;
            }
        }

        LocalVariable pendingRequestVar = b.createLocalVariable(null, TypeDesc.BOOLEAN);
        b.loadConstant(false);
        b.storeLocal(pendingRequestVar);

        // Each case operates on the remote server first, so put it on the stack early.
        b.loadThis();
        b.loadField(REMOTE_FIELD_NAME, remoteType);

        b.loadLocal(methodIDVar);
        b.invokeVirtual(methodIDVar.getType(), "hashCode", TypeDesc.INT, null);
        b.switchBranch(cases, switchLabels, defaultLabel);

        // Generate case for each set of matches.

        int methodCount = methods.size();
        Label[] tryStarts = new Label[methodCount];
        Label[] tryEnds = new Label[methodCount];
        boolean[] isAsync = new boolean[methodCount];
        int asyncCount = 0;

        int ordinal = 0;
        int entryIndex = 0;
        for (Map.Entry<Integer, List<RemoteMethod>> entry : hashToMethodMap.entrySet()) {
            switchLabels[entryIndex].setLocation();

            List<RemoteMethod> matches = entry.getValue();

            for (int j=0; j<matches.size(); j++) {
                RemoteMethod method = matches.get(j);

                if (method.isAsynchronous()) {
                    isAsync[ordinal] = true;
                    asyncCount++;
                }

                // Make sure identifier matches before proceeding.
                b.loadLocal(methodIDVar);
                loadMethodID(b, ordinal);
                b.invokeVirtual(methodIDVar.getType(), "equals",
                                TypeDesc.BOOLEAN, new TypeDesc[] {TypeDesc.OBJECT});

                Label collision = null;
                if (j + 1 < matches.size()) {
                    // Branch to next possibly matching method.
                    collision = b.createLabel();
                    b.ifZeroComparisonBranch(collision, "==");
                } else {
                    // Branch to default label to throw exception.
                    b.ifZeroComparisonBranch(defaultLabel, "==");
                }

                TypeDesc returnDesc = getTypeDesc(method.getReturnType());
                List<? extends RemoteParameter> paramTypes = method.getParameterTypes();

                boolean reuseChannel = true;

                if (paramTypes.size() != 0) {
                    // Read parameters onto stack.

                    boolean lookForPipe = method.isAsynchronous() &&
                        returnDesc != null && Pipe.class.isAssignableFrom(returnDesc.toClass());

                    for (RemoteParameter paramType : paramTypes) {
                        if (lookForPipe && Pipe.class.isAssignableFrom(paramType.getType())) {
                            lookForPipe = false;
                            // Use channel as Pipe.
                            b.loadLocal(channelVar);
                            reuseChannel = false;
                        } else {
                            readParam(b, paramType, invInVar);
                        }
                    }
                }

                LocalVariable remoteIdVar = null;
                LocalVariable remoteTypeIdVar = null;

                if (method.isBatched() && returnDesc != null &&
                    Remote.class.isAssignableFrom(returnDesc.toClass()))
                {
                    // Read the type id and object id that was generated by client.
                    b.loadLocal(invInVar);
                    b.invokeStatic(IDENTIFIER_TYPE, "read", IDENTIFIER_TYPE,
                                   new TypeDesc[] {TypeDesc.forClass(DataInput.class)});
                    remoteTypeIdVar = b.createLocalVariable(null, IDENTIFIER_TYPE);
                    b.storeLocal(remoteTypeIdVar);

                    b.loadLocal(invInVar);
                    b.invokeStatic(VERSIONED_IDENTIFIER_TYPE, "readAndUpdateRemoteVersion",
                                   VERSIONED_IDENTIFIER_TYPE,
                                   new TypeDesc[] {TypeDesc.forClass(DataInput.class)});
                    remoteIdVar = b.createLocalVariable(null, VERSIONED_IDENTIFIER_TYPE);
                    b.storeLocal(remoteIdVar);
                }

                if (method.getAsynchronousCallMode() == CallMode.ACKNOWLEDGED) {
                    // Acknowledge request by writing null.
                    b.loadLocal(channelVar);
                    b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
                    b.dup();
                    b.loadNull();
                    b.invokeVirtual(INV_OUT_TYPE, "writeThrowable", null,
                                    new TypeDesc[] {THROWABLE_TYPE});
                    b.invokeVirtual(INV_OUT_TYPE, "flush", null, null);
                }

                if (method.isAsynchronous() && reuseChannel && remoteIdVar == null) {
                    // Call finished method before invocation.
                    genFinished(b, channelVar, false);
                    b.storeLocal(pendingRequestVar);
                }

                {
                    // Try handler right before server side method invocation.
                    tryStarts[ordinal] = b.createLabel().setLocation();

                    if (methodExists(method)) {
                        // Invoke the server side method.
                        TypeDesc[] params = getTypeDescs(paramTypes);
                        b.invokeInterface(remoteType, method.getName(), returnDesc, params);
                    } else if (mLocalInfo == null) {
                        // Cannot invoke method because interface is malformed.
                        TypeDesc exType = TypeDesc.forClass(MalformedRemoteObjectException.class);
                        b.newObject(exType);
                        b.dup();
                        b.loadConstant(mMalformedInfoMessage);
                        b.loadConstant(remoteType);
                        b.invokeConstructor(exType, new TypeDesc[] {TypeDesc.STRING, CLASS_TYPE});
                        b.throwObject();
                    } else {
                        // Cannot invoke method because it is unimplemented.
                        b.newObject(UNIMPLEMENTED_EX_TYPE);
                        b.dup();
                        b.loadConstant(method.getSignature());
                        b.invokeConstructor
                            (UNIMPLEMENTED_EX_TYPE, new TypeDesc[] {TypeDesc.STRING});
                        b.throwObject();
                    }

                    // Exception handler covers server method invocation only.
                    tryEnds[ordinal] = b.createLabel().setLocation();
                }

                if (remoteIdVar != null) {
                    // Link remote object to id for batched method.
                    LocalVariable remoteVar = b.createLocalVariable(null, returnDesc);
                    b.storeLocal(remoteVar);

                    b.loadThis();
                    b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
                    b.loadConstant(returnDesc);
                    b.loadLocal(remoteVar);
                    b.loadLocal(remoteTypeIdVar);
                    b.loadLocal(remoteIdVar);
                    b.invokeInterface(SKEL_SUPPORT_TYPE, "linkBatchedRemote", null,
                                      new TypeDesc[] {CLASS_TYPE,
                                                      TypeDesc.forClass(Remote.class),
                                                      remoteTypeIdVar.getType(),
                                                      remoteIdVar.getType()});

                    if (reuseChannel) {
                        // Finished with channel after linked.
                        genFinished(b, channelVar, false);
                    } else {
                        b.loadLocal(pendingRequestVar);
                    }

                    b.returnValue(TypeDesc.BOOLEAN);
                } else if (method.isAsynchronous()) {
                    // Discard return value from asynchronous methods.
                    if (returnDesc != null) {
                        if (returnDesc.isDoubleWord()) {
                            b.pop2();
                        } else {
                            b.pop();
                        }
                    }

                    b.loadLocal(pendingRequestVar);
                    b.returnValue(TypeDesc.BOOLEAN);
                } else {
                    // For synchronous method, write response and flush stream.

                    LocalVariable retVar = null;
                    if (returnDesc != null) {
                        retVar = b.createLocalVariable(null, returnDesc);
                        b.storeLocal(retVar);
                    }

                    b.loadLocal(channelVar);
                    b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
                    LocalVariable invOutVar = b.createLocalVariable(null, INV_OUT_TYPE);
                    b.storeLocal(invOutVar);

                    b.loadLocal(invOutVar);
                    b.loadNull();
                    b.invokeVirtual(INV_OUT_TYPE, "writeThrowable", null,
                                    new TypeDesc[] {THROWABLE_TYPE});
                    if (retVar != null) {
                        writeParam(b, method.getReturnType(), invOutVar, retVar);
                    }

                    // Call finished method.
                    genFinished(b, channelVar, true);
                    b.returnValue(TypeDesc.BOOLEAN);
                }

                ordinal++;

                if (collision != null) {
                    collision.setLocation();
                }
            }

            entryIndex++;
        }

        // For default case, throw a NoSuchMethodException.
        defaultLabel.setLocation();
        b.pop(); // pop remote server
        b.newObject(NO_SUCH_METHOD_EX_TYPE);
        b.dup();
        b.loadLocal(methodIDVar);
        b.invokeStatic(TypeDesc.STRING, "valueOf",
                       TypeDesc.STRING, new TypeDesc[] {TypeDesc.OBJECT});
        b.invokeConstructor(NO_SUCH_METHOD_EX_TYPE, new TypeDesc[] {TypeDesc.STRING});
        b.throwObject();

        // Create common exception handlers. One for regular methods, the other
        // for asynchronous methods.

        LocalVariable throwableVar = b.createLocalVariable(null, THROWABLE_TYPE);

        // Handler for asynchronous methods (if any). Re-throw exception
        // wrapped in AsynchronousInvocationException.
        if (asyncCount > 0) {
            for (ordinal=0; ordinal<methodCount; ordinal++) {
                if (!isAsync[ordinal]) {
                    continue;
                }
                b.exceptionHandler
                    (tryStarts[ordinal], tryEnds[ordinal], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            b.newObject(ASYN_INV_EX_TYPE);
            b.dup();
            b.loadLocal(throwableVar);
            b.loadLocal(pendingRequestVar);
            b.invokeConstructor(ASYN_INV_EX_TYPE,
                                new TypeDesc[] {THROWABLE_TYPE, TypeDesc.BOOLEAN});
            b.throwObject();
        }

        // Handler for synchronous methods (if any). Write exception to channel.
        if (caseCount - asyncCount > 0) {
            for (ordinal=0; ordinal<methodCount; ordinal++) {
                if (isAsync[ordinal]) {
                    continue;
                }
                b.exceptionHandler
                    (tryStarts[ordinal], tryEnds[ordinal], Throwable.class.getName());
            }

            b.storeLocal(throwableVar);

            b.loadLocal(channelVar);
            b.invokeInterface(INV_CHANNEL_TYPE, "getOutputStream", INV_OUT_TYPE, null);
            LocalVariable invOutVar = b.createLocalVariable(null, INV_OUT_TYPE);
            b.storeLocal(invOutVar);

            b.loadLocal(invOutVar);
            b.loadLocal(throwableVar);
            b.invokeVirtual(INV_OUT_TYPE, "writeThrowable", null, new TypeDesc[] {THROWABLE_TYPE});

            // Call finished method.
            genFinished(b, channelVar, true);
            b.returnValue(TypeDesc.BOOLEAN);
        }

        // Add the Unreferenced.unreferenced method.
        {
            mi = cf.addMethod(Modifiers.PUBLIC, "unreferenced", null, null);
            b = new CodeBuilder(mi);
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);
            b.instanceOf(UNREFERENCED_TYPE);
            Label notUnref = b.createLabel();
            b.ifZeroComparisonBranch(notUnref, "==");
            b.loadThis();
            b.loadField(REMOTE_FIELD_NAME, remoteType);
            b.checkCast(UNREFERENCED_TYPE);
            b.invokeInterface(UNREFERENCED_TYPE, "unreferenced", null, null);
            notUnref.setLocation();
            b.returnVoid();
        }
                                 
        return ci.defineClass(cf);
    }

    private boolean methodExists(RemoteMethod method) {
        if (mLocalInfo == mInfo) {
            // Since method came from same info, of course it exists.
            return true;
        }

        if (mLocalInfo == null) {
            // Local interface is malformed and so no skeleton methods can be
            // implemented.
            return false;
        }

        List<? extends RemoteParameter> paramList = method.getParameterTypes();
        RemoteParameter[] paramTypes = new RemoteParameter[paramList.size()];
        paramList.toArray(paramTypes);

        try {
            RemoteMethod localMethod = mLocalInfo.getRemoteMethod(method.getName(), paramTypes);
            return equalTypes(localMethod.getReturnType(), method.getReturnType());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // Implementation leaves a boolean on the stack.
    private void genFinished(CodeBuilder b, LocalVariable channelVar, boolean synchronous) {
        b.loadThis();
        b.loadField(SUPPORT_FIELD_NAME, SKEL_SUPPORT_TYPE);
        b.loadLocal(channelVar);
        b.loadConstant(synchronous);
        b.invokeInterface(SKEL_SUPPORT_TYPE, "finished", TypeDesc.BOOLEAN,
                          new TypeDesc[] {INV_CHANNEL_TYPE, TypeDesc.BOOLEAN});
    }

    private static class Factory<R extends Remote> implements SkeletonFactory<R> {
        private final Constructor<? extends Skeleton> mSkeletonCtor;

        Factory(Constructor<? extends Skeleton> ctor) {
            mSkeletonCtor = ctor;
        }

        public Skeleton createSkeleton(SkeletonSupport support, R remoteServer) {
            Throwable error;
            try {
                return mSkeletonCtor.newInstance(support, remoteServer);
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

    private static class EmptySkeletonFactory implements SkeletonFactory {
        static final EmptySkeletonFactory THE = new EmptySkeletonFactory();

        private EmptySkeletonFactory() {
        }

        public Skeleton createSkeleton(SkeletonSupport support, Remote remoteServer) {
            return new Skeleton() {
                public boolean invoke(VersionedIdentifier objectID, Identifier methodID,
                                      InvocationChannel channel)
                    throws IOException, NoSuchMethodException
                {
                    throw new NoSuchMethodException
                        ("Object id: " + objectID + ", method id: " + methodID);
                }

                public void unreferenced() {
                }
            };
        }
    }
}
