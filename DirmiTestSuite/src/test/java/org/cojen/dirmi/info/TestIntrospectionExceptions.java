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

package org.cojen.dirmi.info;

import java.rmi.Remote;
import java.rmi.RemoteException;

import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.Batched;
import org.cojen.dirmi.CallMode;
import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.RemoteFailure;
import org.cojen.dirmi.Timeout;
import org.cojen.dirmi.TimeoutParam;
import org.cojen.dirmi.TimeoutUnit;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class TestIntrospectionExceptions {
    public static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestIntrospectionExceptions.class.getName());
    }

    @Test
    public void nullObject() {
        try {
            RemoteIntrospector.getRemoteType(null);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote object must not be null");
        }
    }

    public static class C1 implements Remote {}

    public static class C2 extends C1 {}

    @Test
    public void nonRemoteObject() {
        try {
            RemoteIntrospector.getRemoteType(new C2());
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "No Remote types directly implemented");
        }
    }

    public static interface R1 extends Remote {}

    public static interface R2 extends Remote {}

    public static class C3 implements R1, R2 {}

    @Test
    public void multipleRemoteObject() {
        try {
            RemoteIntrospector.getRemoteType(new C3());
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "At most one Remote interface may be directly implemented");
        }
    }

    @Test
    public void nullInterface() {
        try {
            RemoteIntrospector.examine(null);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface must not be null");
        }
    }

    @Test
    public void interfaceType() {
        try {
            RemoteIntrospector.examine(C3.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote type must be an interface");
        }
    }

    static interface R3 extends Remote {}

    @Test
    public void publicInterface() {
        try {
            RemoteIntrospector.examine(R3.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface must be public");
        }
    }

    public static interface R4 extends java.util.List {}

    @Test
    @SuppressWarnings("unchecked")
    public void extendRemote() {
        try {
            RemoteIntrospector.examine((Class) R4.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface must extend java.rmi.Remote");
        }
    }

    public static interface R5 extends Remote, java.io.Serializable {}

    @Test
    public void extendSerializable() {
        try {
            RemoteIntrospector.examine(R5.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote interface cannot extend java.io.Serializable");
        }
    }

    public static interface R6 extends Remote {
        @Batched
        Pipe foo() throws RemoteException;
    }

    @Test
    public void batchedPipe() {
        try {
            RemoteIntrospector.examine(R6.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous batched method cannot return Pipe");
        }
    }

    public static interface R7 extends Remote {
        @Asynchronous
        Pipe foo(Pipe p1, Pipe p2) throws RemoteException;
    }

    @Test
    public void multiplePipes() {
        try {
            RemoteIntrospector.examine(R7.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous method which returns Pipe must have " +
                    "exactly one matching Pipe input parameter");
        }
    }

    public static interface R8 extends Remote {
        @Batched
        String foo() throws RemoteException;
    }

    @Test
    public void batchedReturnType() {
        try {
            RemoteIntrospector.examine(R8.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous batched method must return void, " +
                    "a Remote object, Completion or Future");
        }
    }

    public static interface R9 extends Remote {
        @Asynchronous
        String foo() throws RemoteException;
    }

    @Test
    public void asyncReturnType() {
        try {
            RemoteIntrospector.examine(R9.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous method must return void, Pipe, " +
                    "Completion or Future");
        }
    }

    public static interface R10 extends Remote {
        @Asynchronous
        @Batched
        String foo() throws RemoteException;
    }

    @Test
    public void asyncAndBatched() {
        try {
            RemoteIntrospector.examine(R10.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method cannot be annotated as both @Asynchronous and @Batched");
        }
    }

    static class C4 {}

    public static interface R11 extends Remote {
        C4 foo() throws RemoteException;
    }

    @Test
    public void publicReturnType() {
        try {
            RemoteIntrospector.examine(R11.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote method return type must be public");
        }
    }

    public static interface R12 extends Remote {
        void foo(C4 c) throws RemoteException;
    }

    @Test
    public void publicParamType() {
        try {
            RemoteIntrospector.examine(R12.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote method parameter types must be public");
        }
    }

    static class C5 extends Exception {}

    public static interface R13 extends Remote {
        void foo() throws RemoteException, C5;
    }

    @Test
    public void publicExceptionType() {
        try {
            RemoteIntrospector.examine(R13.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote method declared exception types must be public");
        }
    }

    public static interface R14 extends Remote {
        void foo
            (@TimeoutParam TimeUnit u1, @TimeoutParam TimeUnit u2
            ) throws RemoteException;
    }

    @Test
    public void multipleTimeoutUnits() {
        try {
            RemoteIntrospector.examine(R14.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "At most one timeout unit parameter allowed");
        }
    }

    public static interface R15 extends Remote {
        void foo
            (@TimeoutParam int t1, @TimeoutParam int t2
            ) throws RemoteException;
    }

    @Test
    public void multipleTimeoutValues() {
        try {
            RemoteIntrospector.examine(R15.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "At most one timeout value parameter allowed");
        }
    }

    public static interface R16 extends Remote {
        void foo
            (@TimeoutParam String t1
            ) throws RemoteException;
    }

    @Test
    public void timeoutType() {
        try {
            RemoteIntrospector.examine(R16.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Timeout parameter can only apply to primitive " +
                    "numerical types or TimeUnit");
        }
    }

    public static interface R17 extends Remote {
        @Asynchronous
        void foo() throws RemoteException;
    }

    public static interface R18 extends Remote {
        void foo() throws RemoteException;
    }

    public static interface R19 extends R17, R18 {
    }

    @Test
    public void asyncConflict() {
        try {
            RemoteIntrospector.examine(R19.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Inherited methods conflict in use of @Asynchronous annotation");
        }
    }

    public static interface R20 extends Remote {
        @Asynchronous(CallMode.EVENTUAL)
        void foo() throws RemoteException;
    }

    public static interface R21 extends R17, R20 {
    }

    @Test
    public void asyncConflict2() {
        try {
            RemoteIntrospector.examine(R21.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Inherited methods conflict in use of @Asynchronous annotation");
        }
    }

    public static interface R22 extends Remote {
        @Batched
        void foo() throws RemoteException;
    }

    public static interface R23 extends Remote {
        void foo() throws RemoteException;
    }

    public static interface R24 extends R22, R23 {
    }

    @Test
    public void batchedConflict() {
        try {
            RemoteIntrospector.examine(R24.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Inherited methods conflict in use of @Batched annotation");
        }
    }

    public static interface R25 extends Remote {
        @Batched(CallMode.IMMEDIATE)
        void foo() throws RemoteException;
    }

    public static interface R26 extends R22, R25 {
    }

    @Test
    public void batchedConflict2() {
        try {
            RemoteIntrospector.examine(R26.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Inherited methods conflict in use of @Batched annotation");
        }
    }

    public static interface R27 extends Remote {
        @Asynchronous
        void foo() throws RemoteException;
    }

    public static interface R28 extends Remote {
        @Batched
        void foo() throws RemoteException;
    }

    public static interface R29 extends R27, R28 {
    }

    @Test
    public void asyncBatchedConflict() {
        try {
            RemoteIntrospector.examine(R29.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Inherited methods conflict in use of @Asynchronous/@Batched annotation");
        }
    }

    static class C6 extends Exception {
        public C6(Exception ex) {
        }
    }

    public static interface R30 extends Remote {
        @RemoteFailure(exception=C6.class)
        void foo() throws Exception;
    }

    @Test
    public void publicRemoteFailure() {
        try {
            RemoteIntrospector.examine(R30.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote failure exception must be public");
        }
    }

    public static class C7 extends Exception {
        public C7() {}
    }

    public static interface R31 extends Remote {
        @RemoteFailure(exception=C7.class)
        void foo() throws Exception;
    }

    @Test
    public void remoteFailureCtor() {
        try {
            RemoteIntrospector.examine(R31.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote failure exception does not have a public single-argument " +
                    "constructor which accepts a RemoteException");
        }
    }

    public static class C8 extends Exception {
        public C8(String str) {}
    }

    public static interface R32 extends Remote {
        @RemoteFailure(exception=C8.class)
        void foo() throws Exception;
    }

    @Test
    public void remoteFailureCtor2() {
        try {
            RemoteIntrospector.examine(R32.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote failure exception does not have a public single-argument " +
                    "constructor which accepts a RemoteException");
        }
    }

    public static class C9 extends Exception {
        C9(Exception ex) {}
    }

    public static interface R33 extends Remote {
        @RemoteFailure(exception=C9.class)
        void foo() throws Exception;
    }

    @Test
    public void remoteFailureCtor3() {
        try {
            RemoteIntrospector.examine(R33.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Remote failure exception does not have a public single-argument " +
                    "constructor which accepts a RemoteException");
        }
    }

    public static interface R34 extends Remote {
        public void foo();
    }

    @Test
    public void remoteFailureUsage() {
        try {
            RemoteIntrospector.examine(R34.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.rmi.RemoteException (or a superclass)");
        }
    }

    public static interface R35 extends Remote {
        @RemoteFailure(exception=java.sql.SQLException.class)
        public void foo();
    }

    @Test
    public void remoteFailureUsage2() {
        try {
            RemoteIntrospector.examine(R35.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.sql.SQLException (or a superclass)");
        }
    }

    public static interface R36 extends Remote {
        @Asynchronous
        public void foo();
    }

    @Test
    public void remoteFailureUsage3() {
        try {
            RemoteIntrospector.examine(R36.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.rmi.RemoteException:");
        }
    }

    public static interface R37 extends Remote {
        @Asynchronous
        @RemoteFailure(exception=java.sql.SQLException.class)
        public void foo();
    }

    @Test
    public void remoteFailureUsage4() {
        try {
            RemoteIntrospector.examine(R37.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.sql.SQLException:");
        }
    }

    public static interface R38 extends Remote {
        @Batched
        public void foo();
    }

    @Test
    public void remoteFailureUsage5() {
        try {
            RemoteIntrospector.examine(R38.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.rmi.RemoteException (or a superclass)");
        }
    }

    public static interface R39 extends Remote {
        @Batched
        @RemoteFailure(exception=java.sql.SQLException.class)
        public void foo();
    }

    @Test
    public void remoteFailureUsage6() {
        try {
            RemoteIntrospector.examine(R39.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Method must declare throwing java.sql.SQLException (or a superclass)");
        }
    }

    public static interface R40 extends Remote {
        @Asynchronous
        public void foo() throws Exception;
    }

    @Test
    public void remoteFailureUsage7() {
        try {
            RemoteIntrospector.examine(R40.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous method can only declare throwing java.rmi.RemoteException");
        }
    }

    public static interface R41 extends Remote {
        @Asynchronous
        @RemoteFailure(exception=java.sql.SQLException.class)
        public void foo() throws Exception;
    }

    @Test
    public void remoteFailureUsage8() {
        try {
            RemoteIntrospector.examine(R41.class);
            fail();
        } catch (IllegalArgumentException e) {
            confirm(e, "Asynchronous method can only declare throwing java.sql.SQLException");
        }
    }

    private void confirm(IllegalArgumentException e, String substring) {
        assertTrue(e.getMessage().indexOf(substring) >= 0);
    }
}
