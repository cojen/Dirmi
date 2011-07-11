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
import org.cojen.dirmi.Ordered;
import org.cojen.dirmi.Unreferenced;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class RecyclableSocketChannel extends SocketChannel {
    private Recycler mRecycler;
    private RecycleControl mRemoteControl;

    private Input mRecycledInput;
    private Output mRecycledOutput;
    private boolean mRemoteRecycleReady;

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
        RecycleControl remoteControl;
        check: {
            synchronized (this) {
                if (mRecycler != null && (remoteControl = mRemoteControl) != null) {
                    break check;
                }
                // Ensure stub isn't referenced, breaking remote object cycle.
                mRemoteControl = null;
            }
            // Cannot recycle.
            super.close();
            return;
        }

        if (!markClosed()) {
            return;
        }

        try {
            // Instruct remote endpoint to stop writing.
            remoteControl.outputClose();

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

    protected RecyclableSocketChannel newRecycledChannel(Input in, Output out) {
        return new RecyclableSocketChannel(this, in, out);
    }

    void inputRecycled(Input in) {
        RecycleControl ready;
        synchronized (this) {
            mRecycledInput = in;
            ready = mRecycledOutput == null ? null : mRemoteControl;
        }

        if (ready != null) {
            try {
                ready.recycleReady();
            } catch (RemoteException e) {
                forceDisconnect();
            }
        }

        handoff(false);
    }

    void outputRecycled(Output out) {
        RecycleControl ready;
        synchronized (this) {
            mRecycledOutput = out;
            ready = mRecycledInput == null ? null : mRemoteControl;
        }

        if (ready != null) {
            try {
                ready.recycleReady();
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
                mRemoteRecycleReady = true;
            }

            if (!mRemoteRecycleReady ||
                ((in = mRecycledInput) == null) ||
                ((out = mRecycledOutput) == null))
            {
                return;
            }

            recycler = mRecycler;

            mRecycledInput = null;
            mRecycledOutput = null;
            mRemoteRecycleReady = false;
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

        // Keep this method for compatibility with older version.
        @Ordered
        @Asynchronous
        @Deprecated
        void outputCloseAndDispose() throws RemoteException;

        @Ordered
        @Asynchronous
        void recycleReady() throws RemoteException;

        // Keep this method for compatibility with older version.
        @Ordered
        @Asynchronous
        @Deprecated
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
            RecyclableSocketChannel.this.forceDisconnect();
        }
    }
}
