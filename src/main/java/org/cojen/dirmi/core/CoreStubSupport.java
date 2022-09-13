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

import java.net.SocketAddress;

import org.cojen.dirmi.Pipe;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class CoreStubSupport implements StubSupport {
    private final CoreSession<?> mSession;

    CoreStubSupport(CoreSession<?> session) {
        mSession = session;
    }

    @Override
    public Pipe unbatch() {
        // FIXME: unbatch
        throw null;
    }

    @Override
    public void rebatch(Pipe pipe) {
        // FIXME: rebatch
        throw null;
    }

    @Override
    public <T extends Throwable> Pipe connect(Class<T> remoteFailureException) throws T {
        try {
            return mSession.connect();
        } catch (Throwable e) {
            throw CoreUtils.remoteException(remoteFailureException, e);
        }
    }

    @Override
    public <T extends Throwable, R> R createBatchedRemote(Class<T> remoteFailureException,
                                                          Pipe pipe, Class<R> type)
        throws T
    {
        // FIXME: createBatchedRemote
        throw null;
    }

    @Override
    public Throwable readResponse(Pipe pipe) throws IOException {
        var ex = (Throwable) pipe.readObject();

        if (ex == null) {
            return null;
        }

        // Augment the stack trace with a local trace.

        StackTraceElement[] trace = ex.getStackTrace();
        int traceLength = trace.length;

        // Prune the local trace for all calls that occur before (and including) the invoker.
        String fileName = InvokerMaker.class.getSimpleName();
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
        int localLength = local.length;

        // Prune the local trace for all calls that occur after the stub.
        fileName = StubMaker.class.getSimpleName();
        for (int i=0; i<local.length; i++) {
            StackTraceElement element = local[i];
            if (fileName.equals(element.getFileName())) {
                localStart = i;
                localLength = local.length - i;
                break;
            }
        }

        var combined = new StackTraceElement[traceLength + stitch.length + localLength];
        System.arraycopy(trace, 0, combined, 0, traceLength);
        System.arraycopy(stitch, 0, combined, traceLength, stitch.length);
        System.arraycopy(local, localStart, combined, traceLength + stitch.length, localLength);

        ex.setStackTrace(combined);

        return ex;
    }

    @Override
    public void finished(Pipe pipe) {
        try {
            pipe.recycle();
        } catch (IOException e) {
            // FIXME: log it
            CoreUtils.uncaughtException(e);
        }
    }

    @Override
    public void batched(Pipe pipe) {
        // FIXME: batched
        throw null;
    }

    @Override
    public void release(Pipe pipe) {
        // Nothing to do.
    }

    @Override
    public <T extends Throwable> T failed(Class<T> remoteFailureException,
                                          Pipe pipe, Throwable cause)
    {
        return CoreUtils.remoteException(remoteFailureException, cause);
    }

    @Override
    public StubSupport dispose(Stub stub) {
        mSession.mStubs.remove(stub);
        return DisposedStubSupport.THE;
    }

    /**
     * Returns pseudo traces which report the pipe's local and remote addresses.
     */
    private static StackTraceElement[] stitch(Pipe pipe) {
        StackTraceElement remote = trace(pipe.remoteAddress());
        StackTraceElement local = trace(pipe.localAddress());

        if (remote == null) {
            if (local == null) {
                return new StackTraceElement[0];
            } else {
                return new StackTraceElement[] {local};
            }
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
