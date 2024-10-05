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

import java.io.Closeable;
import java.io.IOException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;

import java.nio.channels.SocketChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import java.util.function.BiConsumer;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Variable;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class CoreUtils {
    static final long PROTOCOL_V2 = 4052788960387224692L;

    static final Object MAKER_KEY = new Object();

    static final ThreadLocal<CoreSession> cCurrentSession = new ThreadLocal<>();

    public static void setOptions(Socket s) throws IOException {
        if (s.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
            s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
    }

    public static void setOptions(SocketChannel s) throws IOException {
        if (s.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
            s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        s.configureBlocking(true);
    }

    public static Session accessSession(Object obj) {
        if (!(obj instanceof Stub stub)) {
            throw new IllegalArgumentException();
        }
        return stub.support().session();
    }

    public static Session currentSession() {
        Session session = cCurrentSession.get();
        if (session == null) {
            throw new IllegalStateException();
        }
        return session;
    }

    public static boolean dispose(Object obj) {
        if (!(obj instanceof Stub stub)) {
            throw new IllegalArgumentException();
        }
        if (stub.support() instanceof CoreStubSupport css) {
            return css.session().stubDisposeAndNotify(stub, null, true);
        }
        return false;
    }

    public static boolean disposeServer(Object server) {
        CoreSession session = cCurrentSession.get();
        if (session == null) {
            throw new IllegalStateException();
        }
        return session.serverDispose(server);
    }

    // Is called by generated code. See StubMaker. It cannot be generated directly by StubMaker
    // because the Stub.invoker method must not be public.
    public static StubInvoker invoker(Object obj) {
        return ((Stub) obj).invoker();
    }

    /**
     * Grants the given maker access to the core package.
     */
    static void allowAccess(ClassMaker cm) {
        var thisModule = CoreUtils.class.getModule();
        var thatModule = cm.classLoader().getUnnamedModule();
        thisModule.addExports("org.cojen.dirmi.core", thatModule);
    }

    static boolean isRemote(Class<?> clazz) {
        try {
            return Remote.class.isAssignableFrom(clazz) ||
                // Be lenient and also support java.rmi interfaces.
                java.rmi.Remote.class.isAssignableFrom(clazz);
        } catch (Throwable e) {
            // The java.rmi module might not be found.
            return false;
        }
    }

    static boolean isUnchecked(Class<?> clazz) {
        return RuntimeException.class.isAssignableFrom(clazz)
            || Error.class.isAssignableFrom(clazz);
    }

    /**
     * Given one or more exceptions, returns a reduced set of exception types to check by
     * examining the hierarchy. Unchecked exception types are implicitly considered.
     *
     * @param clazz non-null
     * @param others can be null or empty
     * @return non-empty list
     */
    static List<Class<?>> reduceExceptions(Class<?> clazz, Collection<Class<?>> others) {
        var reduced = new ArrayList<Class<?>>();
        reduced.add(clazz);

        if (others != null) {
            for (Class<?> other : others) {
                reduceExceptions(reduced, other);
            }
        }

        reduceExceptions(reduced, RuntimeException.class);
        reduceExceptions(reduced, Error.class);

        return reduced;
    }

    private static void reduceExceptions(List<Class<?>> reduced, Class<?> clazz) {
        Iterator<Class<?>> it = reduced.iterator();
        while (it.hasNext()) {
            Class<?> ex = it.next();
            if (ex.isAssignableFrom(clazz)) {
                return;
            }
            if (clazz.isAssignableFrom(ex)) {
                it.remove();
            }
        }

        reduced.add(clazz);
    }

    static Class<?> loadClassByNameOrDescriptor(String name, ClassLoader loader)
        throws ClassNotFoundException
    {
        char first = name.charAt(0);

        if (first == '[') {
            // Name is an array descriptor.
            return loadClassByNameOrDescriptor(name.substring(1), loader).arrayType();
        }

        if (first == 'L' && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1).replace('/', '.');
        } else {
            switch (name) {
            case "Z": return boolean.class;
            case "C": return char.class;
            case "F": return float.class;
            case "D": return double.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "I": return int.class;
            case "J": return long.class;
            case "V": return void.class;
            }
        }

        return Class.forName(name, false, loader);
    }

    /**
     * @return false if handler was null or if it threw an exception itself
     */
    static boolean acceptException(BiConsumer<Session<?>, Throwable> h, Session<?> s, Throwable e) {
        if (h != null) {
            try {
                h.accept(s, e);
                return true;
            } catch (Throwable e2) {
                try {
                    e.addSuppressed(e2);
                } catch (Throwable e3) {
                }
            }
        }

        return false;
    }

    static int roundUpPower2(int i) {
        // Hacker's Delight figure 3-3.
        i--;
        i |= i >> 1;
        i |= i >> 2;
        i |= i >> 4;
        i |= i >> 8;
        return (i | (i >> 16)) + 1;
    }

    public static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable e) {
            // Ignore.
        }
    }

    public static <T extends Throwable> T remoteException(Class<T> remoteFailureEx,
                                                          Throwable cause)
    {
        return remoteException((SocketAddress) null, remoteFailureEx, cause);
    }

    public static <T extends Throwable> T remoteException(StubSupport support,
                                                          Class<T> remoteFailureEx,
                                                          Throwable cause)
    {
        Session session = support == null ? null : support.session();
        return remoteException(session, remoteFailureEx, cause);
    }

    public static <T extends Throwable> T remoteException(Session session,
                                                          Class<T> remoteFailureEx,
                                                          Throwable cause)
    {
        SocketAddress remoteAddress = session == null ? null : session.remoteAddress();
        return remoteException(remoteAddress, remoteFailureEx, cause);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T remoteException(SocketAddress remoteAddress,
                                                          Class<T> remoteFailureEx,
                                                          Throwable cause)
    {
        if (cause == null) {
            RemoteException re = new RemoteException();
            if (remoteAddress != null) {
                re.remoteAddress(remoteAddress);
            }
            cause = re;
        }

        T actual;

        if (remoteFailureEx.isInstance(cause)) {
            actual = (T) cause;
        } else if (remoteFailureEx == RemoteException.class) {
            actual = (T) new RemoteException(cause);
        } else {
            actual = ExceptionWrapper.forClass(remoteFailureEx).wrap(cause, remoteAddress);
        }

        if (remoteAddress != null && actual instanceof RemoteException re) {
            re.remoteAddress(remoteAddress);
        }

        return actual;
    }

    /**
     * Rethrows the given exception without the compiler complaining about it being checked or
     * not. Use as follows: {@code throw rethrow(e)}
     */
    public static RuntimeException rethrow(Throwable e) {
        CoreUtils.<RuntimeException>castAndThrow(e);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void castAndThrow(Throwable e) throws T {
        throw (T) e;
    }

    /**
     * Condy bootstrap method for finding a MethodHandle which refers to a virtual method.
     * This technique is used to obtain a lookup object which has more access.
     *
     * @param name method name
     * @param clazz enclosing class
     * @param mt parameters and return type
     * @see StubMaker
     */
    public static MethodHandle findVirtual(MethodHandles.Lookup lookup, String name, Class type,
                                           Class clazz, MethodType mt)
        throws NoSuchMethodException, IllegalAccessException
    {
        return lookup.findVirtual(clazz, name, mt);
    }

    static void writeParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (type == null) {
            pipeVar.invoke("writeObject", paramVar);
        } else if (!type.isPrimitive()) {
            pipeVar.invoke("writeObject", paramVar);
        } else {
            String m;
            if (type == int.class) {
                m = "writeInt";
            } else if (type == long.class) {
                m = "writeLong";
            } else if (type == boolean.class) {
                m = "writeBoolean";
            } else if (type == double.class) {
                m = "writeDouble";
            } else if (type == float.class) {
                m = "writeFloat";
            } else if (type == byte.class) {
                m = "writeByte";
            } else if (type == char.class) {
                m = "writeChar";
            } else if (type == short.class) {
                m = "writeShort";
            } else {
                throw new AssertionError();
            }
            pipeVar.invoke(m, paramVar);
        }
    }

    static void readParam(Variable pipeVar, Variable paramVar) {
        Class<?> type = paramVar.classType();

        if (type == null) {
            paramVar.set(pipeVar.invoke("readObject").cast(paramVar));
        } else if (!type.isPrimitive()) {
            var objectVar = pipeVar.invoke("readObject");
            paramVar.set(type == Object.class ? objectVar : objectVar.cast(type));
        } else {
            String m;
            if (type == int.class) {
                m = "readInt";
            } else if (type == long.class) {
                m = "readLong";
            } else if (type == boolean.class) {
                m = "readBoolean";
            } else if (type == double.class) {
                m = "readDouble";
            } else if (type == float.class) {
                m = "readFloat";
            } else if (type == byte.class) {
                m = "readByte";
            } else if (type == char.class) {
                m = "readChar";
            } else if (type == short.class) {
                m = "readShort";
            } else {
                throw new AssertionError();
            }
            paramVar.set(pipeVar.invoke(m));
        }
    }

    /**
     * @param max maximum possible id
     */
    static void writeIntId(Variable pipeVar, int max, Variable idVar) {
        if (max < 256) {
            pipeVar.invoke("writeByte", idVar);
        } else if (max < 65536) {
            pipeVar.invoke("writeShort", idVar);
        } else {
            pipeVar.invoke("writeInt", idVar);
        }
    }

    /**
     * @param max maximum possible id
     * @return a new int variable with the id
     */
    static Variable readIntId(Variable pipeVar, int max) {
        var methodIdVar = pipeVar.methodMaker().var(int.class);

        if (max < 256) {
            methodIdVar.set(pipeVar.invoke("readUnsignedByte"));
        } else if (max < 65536) {
            methodIdVar.set(pipeVar.invoke("readUnsignedShort"));
        } else {
            methodIdVar.set(pipeVar.invoke("readInt"));
        }

        return methodIdVar;
    }

    /**
     * Returns true if any of the given parameter types represents an object.
     *
     * @param ptypes consists of Class instances, Variables, or String descriptors
     */
    static boolean anyObjectTypes(Object[] ptypes) {
        for (Object ptype : ptypes) {
            if (isObjectType(ptype)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if any of the given parameter types represents an object.
     *
     * @param ptypes consists of Class instances, Variables, or String descriptors
     */
    static boolean anyObjectTypes(List<?> ptypes) {
        for (Object ptype : ptypes) {
            if (isObjectType(ptype)) {
                return true;
            }
        }
        return false;
    }

    static boolean isObjectType(Object type) {
        if (type instanceof Class) {
            return !((Class) type).isPrimitive();
        } else if (type instanceof Variable) {
            Class<?> ctype = ((Variable) type).classType();
            return ctype == null || !ctype.isPrimitive();
        } else {
            char first = ((String) type).charAt(0);
            return first == 'L' || first == '[';
        }
    }

    static void assignTrace(Pipe pipe, Throwable ex) {
        // Augment the stack trace with a local trace.

        StackTraceElement[] trace = ex.getStackTrace();
        int traceLength = trace.length;

        // Prune the local trace for all calls that occur before (and including) the skeleton.
        String fileName = SkeletonMaker.class.getSimpleName();
        for (int i=trace.length; --i>=0; ) {
            StackTraceElement element = trace[i];
            if (fileName.equals(element.getFileName())) {
                traceLength = i;
                break;
            }
        }

        StackTraceElement[] stitch = stitch(pipe);

        StackTraceElement[] local = new Throwable().getStackTrace();
        int localStart = 0;

        // Prune the local trace for all calls that occur after the stub, or else just prune
        // this "assignTrace" method if no stub method is found.

        if (local.length != 0) {
            localStart = 1; // prune this method
        }

        fileName = StubMaker.class.getSimpleName();
        for (int i=0; i<local.length; i++) {
            StackTraceElement element = local[i];
            if (fileName.equals(element.getFileName())) {
                localStart = i;
                break;
            }
        }

        int localLength = local.length - localStart;

        var combined = new StackTraceElement[traceLength + stitch.length + localLength];
        System.arraycopy(trace, 0, combined, 0, traceLength);
        System.arraycopy(stitch, 0, combined, traceLength, stitch.length);
        System.arraycopy(local, localStart, combined, traceLength + stitch.length, localLength);

        ex.setStackTrace(combined);
    }

    /**
     * Returns pseudo traces which report the pipe's local and remote addresses.
     */
    private static StackTraceElement[] stitch(Pipe pipe) {
        StackTraceElement remote = trace(pipe.remoteAddress());
        StackTraceElement local = trace(pipe.localAddress());

        if (remote == null) {
            if (local == null) {
                local = new StackTraceElement
                    ("...remote method invocation..", "", "no address", -1);
            }
            return new StackTraceElement[] {local};
        } else if (local == null) {
            return new StackTraceElement[] {remote};
        } else {
            return new StackTraceElement[] {remote, local};
        }
    }

    private static StackTraceElement trace(SocketAddress address) {
        String str;
        if (address == null || (str = address.toString()).isEmpty()) {
            return null;
        }
        return new StackTraceElement("...remote method invocation..", "", str, -1);
    }
}
