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

import java.util.Arrays;

import org.cojen.dirmi.Pipe;

/**
 * Builds up batched responses to write to the client after all input has been drained. This
 * is used by batched methods to build up responses that should be written to back to the
 * client only when a non-batched method is encountered. This ensures that all input has been
 * drained and that writing back to the client won't deadlock.
 *
 * @author Brian S O'Neill
 */
public final class BatchedContext {
    // Returned from a skeleton to indicate that the caller should stop reading requests. This
    // is returned by piped methods.
    public static final Object STOP_READING = new Object();

    private static final Object NULL = new Object();

    public static Object addResponse(Object context, Object response) {
        return doAddResponse(context, response == null ? NULL : response);
    }

    public static Object addException(Object context, Throwable ex) {
        return doAddResponse(context, BatchedException.make(ex));
    }

    public static boolean hasException(Object context) {
        if (context == null) {
            return false;
        } else if (context instanceof BatchedException) {
            return true;
        } else if (context instanceof BatchedContext) {
            return ((BatchedContext) context).hasException();
        } else {
            return false;
        }
    }

    /**
     * @param context must not be null
     */
    public static void writeResponses(Object context, Pipe pipe) throws IOException {
        if (context instanceof BatchedContext) {
            ((BatchedContext) context).writeResponses(pipe);
        } else if (context instanceof BatchedException) {
            pipe.writeObject(((BatchedException) context).getCause());
        } else {
            pipe.writeObject(context == NULL ? null : context);
        }
    }

    private static Object doAddResponse(Object context, Object response) {
        if (context == null) {
            return response;
        } else if (context instanceof BatchedContext) {
            var batched = (BatchedContext) context;
            batched.addResponse(response);
            return batched;
        } else {
            return new BatchedContext(context, response);
        }
    }

    private Object[] mResponses;
    private int mSize;

    private BatchedContext(Object first, Object second) {
        mResponses = new Object[4];
        mResponses[0] = first;
        mResponses[1] = second;
        mSize = 2;
    }

    private void addResponse(Object response) {
        Object[] responses = mResponses;
        int size = mSize;
        if (size >= responses.length) {
            mResponses = responses = Arrays.copyOfRange(responses, 0, responses.length << 1);
        }
        responses[size] = response;
        mSize = size + 1;
    }

    private boolean hasException() {
        // Only need to check the last because no more batched calls should be made after an
        // exception is encountered.
        return mResponses[mSize - 1] instanceof BatchedException;
    }

    private void writeResponses(Pipe pipe) throws IOException {
        Object[] responses = mResponses;
        for (int i=0; i<mSize; i++) {
            Object response = responses[i];
            if (response instanceof BatchedException) {
                pipe.writeObject(((BatchedException) response).getCause());
            } else {
                pipe.writeObject(response == NULL ? null : response);
            }
        }
    }
}
