/*
 *  Copyright 2010 Brian S O'Neill
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

package org.cojen.dirmi.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.rmi.Remote;

import java.rmi.RemoteException;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.Disposer;
import org.cojen.dirmi.Ordered;
import org.cojen.dirmi.Unreferenced;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class RecyclableSocketChannel extends SocketChannel {
    // Channel states, or'd together.
    private static final int REMOTE_RECYCLE_READY = 1;
    private static final int LOCAL_RECYCLE_READY = 2;
    private static final int OUTPUT_CLOSED = 4;

    private Recycler mRecycler;
    private RecycleControl mRemoteControl;

    private Input mRecycledInput;
    private Output mRecycledOutput;

    private int mState;

    RecyclableSocketChannel(IOExecutor executor, SimpleSocket socket) throws IOException {
        super(executor, socket);
    }

    RecyclableSocketChannel(RecyclableSocketChannel channel, Input in, Output out) {
        super(channel, in, out);
        in.setChannel(this);
        out.setChannel(this);
    }

    @Override
    public synchronized Remote installRecycler(Recycler recycler) {
        if (mRecycler != null) {
            throw new IllegalStateException();
        }
        if (recycler == null) {
            throw new IllegalArgumentException();
        }
        mRecycler = recycler;
        return new LocalControl();
    }

    @Override
    public void setRecycleControl(Remote control) {
        if (!(control instanceof RecycleControl)) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            mRemoteControl = (RecycleControl) control;
        }
    }

    @Override
    Input createInputStream(SimpleSocket socket) throws IOException {
        return new Input(socket.getInputStream(), this);
    }

    @Override
    Output createOutputStream(SimpleSocket socket) throws IOException {
        return new Output(socket.getOutputStream(), this);
    }

    @Override
    public void close() throws IOException {
        if (!markClosed()) {
            return;
        }

        RecycleControl remoteControl;
        int state;
        check: {
            synchronized (this) {
                state = mState;
                if (mRecycler != null && (remoteControl = mRemoteControl) != null) {
                    state |= OUTPUT_CLOSED;
                    mState = state;
                    break check;
                }
                // Ensure stub isn't referenced, breaking remote object cycle.
                mRemoteControl = null;
            }
            // Cannot recycle.
            super.close();
            return;
        }

        try {
            // Instruct remote endpoint to stop writing.
            if ((state & LOCAL_RECYCLE_READY) != 0) {
                remoteControl.outputCloseAndDispose();
            } else {
                remoteControl.outputClose();
            }

            // Start draining and unblock remote endpoint's writing.
            getInputStream().inputClose();

            // Close local output and wait for streams to recycle.
            getOutputStream().outputClose();
        } catch (IOException e) {
            forceDisconnect();
            throw e;
        }
    }

    @Override
    public void disconnect() {
        if (markClosed()) {
            forceDisconnect();
        }
    }

    void forceDisconnect() {
        synchronized (this) {
            // Ensure stub isn't referenced, breaking remote object cycle.
            mRemoteControl = null;
        }
        super.disconnect();
    }

    void unreferenced() {
        synchronized (this) {
            // Check if unreferenced following a dispose request, in which case
            // the request should be ignored.
            if ((mState & REMOTE_RECYCLE_READY) != 0) {
                return;
            }
        }
        forceDisconnect();
    }

    protected RecyclableSocketChannel newRecycledChannel(Input in, Output out) {
        return new RecyclableSocketChannel(this, in, out);
    }

    void inputRecycled(Input in) {
        RecycleControl ready;
        int state;
        synchronized (this) {
            state = mState;
            mRecycledInput = in;
            if (mRecycledOutput == null) {
                ready = null;
            } else {
                ready = mRemoteControl;
                state |= LOCAL_RECYCLE_READY;
                mState = state;
            }
        }

        localRecycled(ready, state);
    }

    void outputRecycled(Output out) {
        RecycleControl ready;
        int state;
        synchronized (this) {
            state = mState;
            mRecycledOutput = out;
            if (mRecycledInput == null) {
                ready = null;
            } else {
                ready = mRemoteControl;
                state |= LOCAL_RECYCLE_READY;
                mState = state;
            }
        }

        localRecycled(ready, state);
    }

    private void localRecycled(RecycleControl ready, int state) {
        if (ready != null) {
            try {
                if ((state & OUTPUT_CLOSED) != 0) {
                    ready.recycleReadyAndDispose();
                } else {
                    ready.recycleReady();
                }
            } catch (RemoteException e) {
                forceDisconnect();
            }
        }

        handoff(false);
    }

    void remoteRecycleReady() {
        handoff(true);
    }

    private void handoff(boolean remoteKnownReady) {
        Input in;
        Output out;
        Recycler recycler;
        synchronized (this) {
            if (remoteKnownReady) {
                mState |= REMOTE_RECYCLE_READY;
            }

            if ((mState & REMOTE_RECYCLE_READY) == 0 ||
                ((in = mRecycledInput) == null) ||
                ((out = mRecycledOutput) == null))
            {
                return;
            }

            recycler = mRecycler;

            mRecycledInput = null;
            mRecycledOutput = null;
        }

        recycler.recycled(newRecycledChannel(in, out));
    }

    static class Input extends PacketInputStream<Input> {
        private volatile RecyclableSocketChannel mChannel;

        Input(InputStream in, RecyclableSocketChannel channel) {
            super(in);
            mChannel = channel;
        }

        private Input() {
        }

        @Override
        public void close() throws IOException {
            RecyclableSocketChannel channel = mChannel;
            if (channel != null) {
                channel.close();
            }
        }

        @Override
        public void disconnect() {
            RecyclableSocketChannel channel = mChannel;
            if (channel != null) {
                channel.disconnect();
            }
        }

        @Override
        protected IOExecutor executor() {
            return mChannel.executor();
        }

        @Override
        protected Input newInstance() {
            return new Input();
        }

        @Override
        protected void recycled(Input newInstance) {
            // Not expected to be null.
            mChannel.inputRecycled(newInstance);
        }

        void setChannel(RecyclableSocketChannel channel) {
            mChannel = channel;
        }
    }

    static class Output extends PacketOutputStream<Output> {
        private volatile RecyclableSocketChannel mChannel;

        Output(OutputStream out, RecyclableSocketChannel channel) {
            super(out);
            mChannel = channel;
        }

        private Output() {
        }

        @Override
        public void close() throws IOException {
            RecyclableSocketChannel channel = mChannel;
            if (channel != null) {
                channel.close();
            }
        }

        @Override
        public void disconnect() {
            RecyclableSocketChannel channel = mChannel;
            if (channel != null) {
                channel.disconnect();
            }
        }

        @Override
        protected Output newInstance() {
            return new Output();
        }

        @Override
        protected void recycled(Output newInstance) {
            // Not expected to be null.
            mChannel.outputRecycled(newInstance);
        }

        void setChannel(RecyclableSocketChannel channel) {
            mChannel = channel;
        }
    }

    public static interface RecycleControl extends Remote {
        @Ordered
        @Asynchronous
        void outputClose() throws RemoteException;

        @Ordered
        @Asynchronous
        @Disposer
        void outputCloseAndDispose() throws RemoteException;

        @Ordered
        @Asynchronous
        void recycleReady() throws RemoteException;

        @Ordered
        @Asynchronous
        @Disposer
        void recycleReadyAndDispose() throws RemoteException;
    }

    private class LocalControl implements RecycleControl, Unreferenced {
        @Override
        public void outputClose() {
            try {
                getOutputStream().outputClose();
            } catch (IOException e) {
                disconnect();
            }
        }

        @Override
        public void outputCloseAndDispose() {
            outputClose();
        }

        @Override
        public void recycleReady() {
            remoteRecycleReady();
        }

        @Override
        public void recycleReadyAndDispose() {
            recycleReady();
        }

        @Override
        public void unreferenced() {
            RecyclableSocketChannel.this.unreferenced();
        }
    }
}
