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

package org.cojen.dirmi;

import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class LockedOutputStream extends OutputStream {
    private final OutputStream mOut;
    private final ReentrantLock mLock;

    LockedOutputStream(OutputStream out) {
        mOut = out;
        mLock = new ReentrantLock();
    }

    final void lock() {
        mLock.lock();
    }

    final void unlock() {
        mLock.unlock();
    }

    @Override
    public void write(int b) throws IOException {
        lock();
        try {
            mOut.write(b);
        } finally {
            unlock();
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        lock();
        try {
            mOut.write(bytes);
        } finally {
            unlock();
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        lock();
        try {
            mOut.write(bytes, offset, length);
        } finally {
            unlock();
        }
    }

    @Override
    public void close() throws IOException {
        mOut.close();

    }
}
