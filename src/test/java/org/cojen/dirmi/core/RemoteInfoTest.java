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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Iterator;
import java.util.Set;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.NoReply;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.Remote;
import org.cojen.dirmi.RemoteException;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Restorable;
import org.cojen.dirmi.Serialized;
import org.cojen.dirmi.Unbatched;

import org.cojen.dirmi.io.CaptureOutputStream;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteInfoTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RemoteInfoTest.class.getName());
    }

    public static interface R1 extends Remote {}

    public static interface R2 extends Remote {}

    public static class C1 implements R1, R2 {}

    @Test
    public void interfaceType() {
        try {
            RemoteInfo.examine(C1.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote type must be an interface");
        }
    }

    static interface R3 extends Remote {}

    @Test
    public void publicInterface() {
        try {
            RemoteInfo.examine(R3.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface must be public");
        }
    }

    public static interface R4 extends java.util.List {}

    @Test
    public void extendRemote() {
        try {
            RemoteInfo.examine((Class) R4.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface must extend");
        }
    }

    public static interface R5 extends Remote {
        @Batched
        Pipe foo() throws RemoteException;
    }

    @Test
    public void batchedPipe() {
        try {
            RemoteInfo.examine(R5.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Batched method must return void or a remote object");
        }
    }

    public static interface R6 extends Remote {
        Pipe foo(Pipe p1, Pipe p2) throws RemoteException;
    }

    @Test
    public void multiplePipes() {
        try {
            RemoteInfo.examine(R6.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Piped method must have exactly one Pipe parameter");
        }
    }

    public static interface R7 extends Remote {
        @Batched
        @Unbatched
        String foo() throws RemoteException;
    }

    @Test
    public void batchedAndUnbatched() {
        try {
            RemoteInfo.examine(R7.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method cannot be annotated as both @Batched and @Unbatched");
        }
    }

    static class C2 {}

    public static interface R8 extends Remote {
        C2 foo() throws RemoteException;
    }

    @Test
    public void publicReturnType() {
        try {
            RemoteInfo.examine(R8.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote method return type must be public");
        }
    }

    public static interface R9 extends Remote {
        void foo(C2 c) throws RemoteException;
    }

    @Test
    public void publicParamType() {
        try {
            RemoteInfo.examine(R9.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote method parameter types must be public");
        }
    }

    static class C3 extends Exception {}

    public static interface R10 extends Remote {
        void foo() throws RemoteException, C3;
    }

    @Test
    public void publicExceptionType() {
        try {
            RemoteInfo.examine(R10.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote exception type must be public");
        }
    }

    public static interface R11 extends Remote {
        @Batched
        void foo(String x) throws RemoteException;
    }

    public static interface R12 extends Remote {
        void foo(String x) throws RemoteException;
    }

    public static interface R13 extends R11, R12 {
    }

    @Test
    public void batchedConflict() {
        try {
            RemoteInfo.examine(R13.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "conflicting annotations");
        }
    }

    public static interface R17 extends Remote {
        @RemoteFailure(exception=RuntimeException.class)
        void foo(String x);
    }

    public static interface R18 extends Remote {
        @RemoteFailure(exception=IllegalStateException.class)
        void foo(String x);
    }

    public static interface R19 extends R17, R18 {
    }

    @Test
    public void remoteFailureConflict() {
        try {
            RemoteInfo.examine(R19.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "conflicting remote failure exceptions");
        }
    }

    static class C4 extends Exception {
        public C4(Exception ex) {
        }
    }

    public static interface R20 extends Remote {
        @RemoteFailure(exception=C4.class)
        void foo() throws Exception;
    }

    @Test
    public void publicRemoteFailure() {
        try {
            RemoteInfo.examine(R20.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote failure exception must be public");
        }
    }

    public static interface R21 extends Remote {
        public void foo();
    }

    @Test
    public void remoteFailureUsage() {
        try {
            RemoteInfo.examine(R21.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing");
        }
    }

    public static interface R22 extends Remote {
        @RemoteFailure(exception=IOException.class)
        public void foo();
    }

    @Test
    public void remoteFailureUsage2() {
        try {
            RemoteInfo.examine(R22.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.io.IOException (or a superclass)");
        }
    }

    public static interface Empty extends Remote {
    }

    @Test
    public void empty() throws Exception {
        var info = RemoteInfo.examine(Empty.class);

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        info.writeTo(pipe);
        pipe.flush();

        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var info2 = RemoteInfo.readFrom(pipe);
        assertEquals(info, info2);
        assertEquals(info.hashCode(), info2.hashCode());

        assertEquals(0, info.remoteMethods().size());
    }

    public static interface R30 extends Remote {
        int a() throws RemoteException;

        Pipe b(Pipe p) throws RemoteException;

        @Batched
        void c(String p) throws RemoteException;

        String d(Object x) throws RemoteException;

        @Unbatched
        String d(String p1, int p2) throws RemoteException;

        @RemoteFailure(declared=false)
        void e();

        @RemoteFailure(exception=RuntimeException.class)
        void f();

        @Disposer
        void close() throws RemoteException;

        @Override
        String toString();
    }

    @Test
    public void basic() throws Exception {
        var info = RemoteInfo.examine(R30.class);

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        info.writeTo(pipe);
        pipe.flush();

        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        var info2 = RemoteInfo.readFrom(pipe);
        assertEquals(info, info2);
        assertEquals(info.hashCode(), info2.hashCode());

        assertEquals(R30.class.getName(), info.name());
        assertEquals(1, info.interfaceNames().size());
        assertEquals(Remote.class.getName(), info.interfaceNames().iterator().next());

        Set<RemoteMethod> all = info.remoteMethods();
        assertEquals(8, all.size());

        Iterator<RemoteMethod> it = all.iterator();

        RemoteMethod rm = it.next();
        assertEquals("a", rm.name());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(int.class.descriptorString(), rm.returnType());
        assertEquals(0, rm.parameterTypes().size());
        assertEquals(RemoteException.class.getName(), rm.exceptionTypes().iterator().next());

        rm = it.next();
        assertEquals("b", rm.name());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(true, rm.isPiped());
        assertEquals(Pipe.class.descriptorString(), rm.returnType());
        assertEquals(1, rm.parameterTypes().size());
        assertEquals(Pipe.class.descriptorString(), rm.parameterTypes().iterator().next());

        rm = it.next();
        assertEquals("c", rm.name());
        assertEquals(false, rm.isDisposer());
        assertEquals(true, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(void.class.descriptorString(), rm.returnType());
        assertEquals(1, rm.parameterTypes().size());
        assertEquals(String.class.descriptorString(), rm.parameterTypes().iterator().next());

        rm = it.next();
        assertEquals("close", rm.name());
        assertEquals(true, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(void.class.descriptorString(), rm.returnType());
        assertEquals(0, rm.parameterTypes().size());

        rm = it.next();
        assertEquals("d", rm.name());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(String.class.descriptorString(), rm.returnType());
        assertEquals(1, rm.parameterTypes().size());
        assertEquals(Object.class.descriptorString(), rm.parameterTypes().iterator().next());

        rm = it.next();
        assertEquals("d", rm.name());
        assertFalse(rm.isRemoteFailureExceptionUndeclared());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(true, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(String.class.descriptorString(), rm.returnType());
        assertEquals(2, rm.parameterTypes().size());
        Iterator<String> pit = rm.parameterTypes().iterator();
        assertEquals(String.class.descriptorString(), pit.next());
        assertEquals(int.class.descriptorString(), pit.next());

        rm = it.next();
        assertEquals("e", rm.name());
        assertTrue(rm.isRemoteFailureExceptionUndeclared());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(void.class.descriptorString(), rm.returnType());
        assertEquals(0, rm.parameterTypes().size());

        rm = it.next();
        assertEquals("f", rm.name());
        assertEquals(false, rm.isDisposer());
        assertEquals(false, rm.isBatched());
        assertEquals(false, rm.isUnbatched());
        assertEquals(false, rm.isPiped());
        assertEquals(RuntimeException.class.getName(), rm.remoteFailureException());
        assertEquals(void.class.descriptorString(), rm.returnType());
        assertEquals(0, rm.parameterTypes().size());

        assertFalse(it.hasNext());

        it = all.iterator();
        Iterator<RemoteMethod> it2 = info2.remoteMethods().iterator();
        while (it.hasNext()) {
            rm = it.next();
            RemoteMethod rm2 = it2.next();
            assertEquals(rm, rm2);
        }
        assertFalse(it2.hasNext());
    }

    @RemoteFailure(declared=false)
    public static interface R31 extends Remote {
        void a000();
        void a001();
        void a002();
        void a003();
        void a004();
        void a005();
        void a006();
        void a007();
        void a008();
        void a009();
        void a010();
        void a011();
        void a012();
        void a013();
        void a014();
        void a015();
        void a016();
        void a017();
        void a018();
        void a019();
        void a020();
        void a021();
        void a022();
        void a023();
        void a024();
        void a025();
        void a026();
        void a027();
        void a028();
        void a029();
        void a030();
        void a031();
        void a032();
        void a033();
        void a034();
        void a035();
        void a036();
        void a037();
        void a038();
        void a039();
        void a040();
        void a041();
        void a042();
        void a043();
        void a044();
        void a045();
        void a046();
        void a047();
        void a048();
        void a049();
        void a050();
        void a051();
        void a052();
        void a053();
        void a054();
        void a055();
        void a056();
        void a057();
        void a058();
        void a059();
        void a060();
        void a061();
        void a062();
        void a063();
        void a064();
        void a065();
        void a066();
        void a067();
        void a068();
        void a069();
        void a070();
        void a071();
        void a072();
        void a073();
        void a074();
        void a075();
        void a076();
        void a077();
        void a078();
        void a079();
        void a080();
        void a081();
        void a082();
        void a083();
        void a084();
        void a085();
        void a086();
        void a087();
        void a088();
        void a089();
        void a090();
        void a091();
        void a092();
        void a093();
        void a094();
        void a095();
        void a096();
        void a097();
        void a098();
        void a099();
        void a100();
        void a101();
        void a102();
        void a103();
        void a104();
        void a105();
        void a106();
        void a107();
        void a108();
        void a109();
        void a110();
        void a111();
        void a112();
        void a113();
        void a114();
        void a115();
        void a116();
        void a117();
        void a118();
        void a119();
        void a120();
        void a121();
        void a122();
        void a123();
        void a124();
        void a125();
        void a126();
        void a127();
        void a128();
        void a129();
        void a130();
        void a131();
        void a132();
        void a133();
        void a134();
        void a135();
        void a136();
        void a137();
        void a138();
        void a139();
        void a140();
        void a141();
        void a142();
        void a143();
        void a144();
        void a145();
        void a146();
        void a147();
        void a148();
        void a149();
    }

    @Test
    public void big() throws Exception {
        var info = RemoteInfo.examine(R31.class);

        var capture = new CaptureOutputStream();
        var pipe = new BufferedPipe(InputStream.nullInputStream(), capture);
        info.writeTo(pipe);
        pipe.flush();

        byte[] bytes = capture.getBytes();
        var bin = new ByteArrayInputStream(bytes);
        pipe = new BufferedPipe(bin, OutputStream.nullOutputStream());

        RemoteInfo info2 = RemoteInfo.readFrom(pipe);
        assertEquals(info, info2);
        assertEquals(info.hashCode(), info2.hashCode());

        assertEquals(150, info.remoteMethods().size());
    }

    public static interface R32 extends java.rmi.Remote {
        void a(int x) throws RemoteException;

        void b() throws java.rmi.RemoteException;
    }

    @Test
    public void lenient() throws Exception {
        var info = RemoteInfo.examine(R32.class);

        Set<String> ifaces = info.interfaceNames();
        assertEquals(1, ifaces.size());
        assertEquals(java.rmi.Remote.class.getName(), ifaces.iterator().next());

        Set<RemoteMethod> all = info.remoteMethods();
        assertEquals(2, all.size());

        Iterator<RemoteMethod> it = all.iterator();

        RemoteMethod rm1 = it.next();
        assertEquals("a", rm1.name());
        assertEquals(RemoteException.class.getName(), rm1.remoteFailureException());

        RemoteMethod rm2 = it.next();
        assertEquals("b", rm2.name());
        assertEquals(java.rmi.RemoteException.class.getName(), rm2.remoteFailureException());
    }

    public static interface R33 extends Remote {
        void foo(Pipe p, int x) throws RemoteException;
    }

    @Test
    public void noPipeReturned() {
        try {
            RemoteInfo.examine(R33.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Piped method must return a Pipe");
        }
    }

    public static class C5 extends Exception {
        public C5(String msg) {
        }

        C5(Throwable cause) {
        }
    }

    public static interface R37 extends Remote {
        @RemoteFailure(exception=C5.class)
        void foo() throws C5;
    }

    @Test
    public void unsupportedExceptionType() {
        try {
            RemoteInfo.examine(R37.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "must have a public constructor");
        }
    }

    public static interface R38 extends Remote {
        @RemoteFailure(exception=java.rmi.RemoteException.class)
        void foo() throws java.rmi.RemoteException;
    }

    @Test
    public void supportedExceptionType() {
        RemoteInfo.examine(R38.class);
    }

    public static interface R39 extends Remote {
        @Restorable
        String foo() throws RemoteException;
    }

    @Test
    public void notRestorable() {
        try {
            RemoteInfo.examine(R39.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "must return a remote object");
        }
    }

    public static interface R40 extends Remote {
        @NoReply
        String foo() throws RemoteException;
    }

    @Test
    public void hasReply() {
        try {
            RemoteInfo.examine(R40.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "must return void");
        }
    }

    public static interface R41 extends Remote {
        @Serialized(filter="!*")
        Pipe foo(Pipe pipe) throws RemoteException;
    }

    @Test
    public void notSerialized() {
        try {
            RemoteInfo.examine(R41.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "cannot have @Serialized");
        }
    }

    @Test
    public void brokenStub() {
        try {
            RemoteInfo.examineStub(new StubInvoker(0, null, null));
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "No Remote types");
        }

        class Broken extends StubInvoker implements R1, R2 {
            Broken() {
                super(0, null, null);
            }
        }

        try {
            RemoteInfo.examineStub(new Broken());
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "At most one");
        }
    }

    private void confirm(IllegalArgumentException e, String substring) {
        if (!e.getMessage().contains(substring)) {
            throw e;
        }
    }
}
