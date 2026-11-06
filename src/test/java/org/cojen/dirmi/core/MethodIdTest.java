/*
 *  Copyright 2026 Cojen.org
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
 
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
 
import java.net.ServerSocket;
 
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
 
import org.junit.*;
import static org.junit.Assert.*;
 
import org.cojen.maker.ClassMaker;
 
import org.cojen.dirmi.Data;
import org.cojen.dirmi.Environment;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.Session;
 
/**
 * Regression tests for method id encoding, and for method names which conflict with generated
 * skeleton methods.
 *
 * @author Claude
 */
public class MethodIdTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(MethodIdTest.class.getName());
    }
 
    private Environment mEnv;
    private ServerSocket mServerSocket;
 
    // Names of the methods which were actually executed on the server side.
    private final List<String> mExecuted = Collections.synchronizedList(new ArrayList<>());
 
    @Before
    public void setup() throws Exception {
        mEnv = Environment.create();
        mServerSocket = new ServerSocket(0);
        mEnv.acceptAll(mServerSocket);
    }
 
    @After
    public void teardown() throws Exception {
        mEnv.close();
        mServerSocket.close();
    }
 
    /**
     * Defines a public remote interface with regular methods named m000, m001, etc, and data
     * methods named d0, d1, etc.
     *
     * @param first number of the first regular method
     */
    private static Class<?> makeInterface(String name, int first, int regular, int data) {
        ClassMaker cm = ClassMaker.begin(name, Remote.class.getClassLoader())
            .public_().interface_().implement(Remote.class);
 
        for (int i = first; i < first + regular; i++) {
            cm.addMethod(String.class, String.format("m%03d", i))
                .public_().abstract_().throws_(RemoteException.class);
        }
 
        for (int i = 0; i < data; i++) {
            cm.addMethod(String.class, "d" + i)
                .public_().abstract_().throws_(RemoteException.class)
                .addAnnotation(Data.class, true);
        }
 
        return cm.finish();
    }
 
    /**
     * Returns a server-side implementation which records the name of every method called,
     * and which returns that name followed by '!'.
     */
    private Object makeServer(Class<?> iface) {
        return Proxy.newProxyInstance
            (iface.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> {
                mExecuted.add(method.getName());
                return method.getName() + '!';
            });
    }
 
    private Object call(Object remote, String name) throws Exception {
        Method m = remote.getClass().getMethod(name);
        m.setAccessible(true);
        try {
            return m.invoke(remote);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
 
    /**
     * The id width used by a stub must match the width expected by the skeleton. The skeleton
     * only counts the regular methods, but the stub also counted the data methods. When the
     * regular count was below a power of 256 and the total wasn't, the stub wrote two byte ids
     * and the skeleton read one. The wrong method was called, and the pipe was left out of sync.
     */
    @Test(timeout = 60_000)
    public void dataMethodsDontAffectIdWidth() throws Exception {
        // Each pair is the number of regular methods and the number of data methods.
        int[][] counts = {
            {252, 3},   // 255 total: below the threshold, regardless
            {254, 3},   // 257 total, but only 254 regular
            {255, 1},   // 256 total, but only 255 regular
            {255, 0},   // 255 total
            {256, 0},   // 256 total, 256 regular: two byte ids for both
        };
 
        for (int i = 0; i < counts.length; i++) {
            int regular = counts[i][0], data = counts[i][1];
            String desc = regular + " regular + " + data + " data methods";
 
            Class<?> iface = makeInterface("org.cojen.dirmi.core.Ids" + i, 0, regular, data);
            mEnv.export("ids" + i, (Remote) makeServer(iface));
 
            Session<?> session = mEnv.connect(iface, "ids" + i, "localhost", mServerSocket.getLocalPort());
            Object remote = session.root();
 
            // Reading the data methods runs them on the server side.
            mExecuted.clear();
 
            String last = String.format("m%03d", regular - 1);
 
            // Call the same method twice to also detect a pipe which was left out of sync.
            assertEquals(desc, "m005!", call(remote, "m005"));
            assertEquals(desc, "m005!", call(remote, "m005"));
            assertEquals(desc, last + '!', call(remote, last));
            assertEquals(desc, "m000!", call(remote, "m000"));
 
            assertEquals(desc, Arrays.asList("m005", "m005", last, "m000"),
                         new ArrayList<>(mExecuted));
        }
    }
 
    /**
     * When a stub is remapped to a changed server interface, the id width must be based on the
     * number of methods in the server's interface, because that's what the skeleton uses. It
     * was based on the highest id which was mapped, which can be much smaller.
     */
    @Test(timeout = 60_000)
    public void mappedIdWidth() throws Exception {
        // The stub was generated for the original type. It has ids 0..9, which don't line up
        // with the ids of the same methods in the current type. For example, m001 is id 0 in
        // the original, and id 1 in the current type. The current type has more than 255
        // methods, and so it requires two byte ids, even though the highest mapped id is 10.
        Class<?> originalType = makeInterface("org.cojen.dirmi.core.Original", 1, 10, 0);
        Class<?> currentType = makeInterface("org.cojen.dirmi.core.Current", 0, 300, 0);
 
        MethodIdWriter writer = MethodIdWriterMaker.writerFor
            (RemoteInfo.examine(originalType), RemoteInfo.examine(currentType), false);
 
        assertNotNull(writer);
 
        for (int originalId : new int[] {0, 1, 9}) {
            String name = String.format("m%03d", originalId + 1);
 
            var capture = new ByteArrayOutputStream();
            var out = new BufferedPipe(InputStream.nullInputStream(), capture);
            writer.writeMethodId(out, originalId, name);
            out.flush();
 
            // Follow the method id with some of whatever would be next in the stream.
            byte[] bytes = Arrays.copyOf(capture.toByteArray(), capture.size() + 1);
 
            @SuppressWarnings("unchecked")
            SkeletonFactory<Object> factory = SkeletonMaker.factoryFor((Class) currentType);
            Skeleton<Object> skeleton = factory.newSkeleton(1L, null, makeServer(currentType));
 
            mExecuted.clear();
 
            var in = new BufferedPipe
                (new ByteArrayInputStream(bytes), OutputStream.nullOutputStream());
 
            try {
                skeleton.invoke(null, in);
            } catch (Throwable e) {
                throw new AssertionError("Skeleton failed for " + name, e);
            }
 
            assertEquals(Arrays.asList(name), new ArrayList<>(mExecuted));
        }
    }
 
    public static interface Invoke extends Remote {
        String invoke() throws RemoteException;
 
        String invoke(int x) throws RemoteException;
    }
 
    public static interface BatchFinish extends Remote {
        String batchFinish() throws RemoteException;
    }
 
    /**
     * Skeleton methods are generated with the same name as the remote method. The names
     * "invoke" and "batchFinish" conflicted with methods defined by the skeleton base classes.
     */
    @Test(timeout = 60_000)
    public void conflictingMethodNames() throws Exception {
        // Fails with an IllegalStateException if the skeleton cannot be generated.
        assertNotNull(SkeletonMaker.factoryFor(Invoke.class));
        assertNotNull(SkeletonMaker.factoryFor(BatchFinish.class));
 
        mEnv.export("invoke", (Remote) makeServer(Invoke.class));
        mEnv.export("batchFinish", (Remote) makeServer(BatchFinish.class));
 
        int port = mServerSocket.getLocalPort();
 
        var invoke = (Invoke) mEnv.connect(Invoke.class, "invoke", "localhost", port).root();
        assertEquals("invoke!", invoke.invoke());
        assertEquals("invoke!", invoke.invoke(5));
 
        var finish = (BatchFinish) mEnv.connect
            (BatchFinish.class, "batchFinish", "localhost", port).root();
        assertEquals("batchFinish!", finish.batchFinish());
    }
}
