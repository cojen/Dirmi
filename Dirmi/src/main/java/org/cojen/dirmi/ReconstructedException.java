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

package org.cojen.dirmi;

/**
 * Thrown by {@link Pipe#readThrowable} if the Throwable was not serializable
 * or depends on classes that are not found. The pipe that throws this
 * exception cannot be used any more and must be closed.
 *
 * @author Brian S O'Neill
 */
public class ReconstructedException extends Exception {
    private static final long serialVersionUID = 1;

    private final Throwable mReconstructCause;

    public ReconstructedException(Throwable reconstructed, Throwable reconstructCause) {
        super(reconstructed);
        mReconstructCause = reconstructCause;
    }

    /**
     * Returns the Throwable which was reconstructed.
     */
    @Override
    public Throwable getCause() {
        return super.getCause();
    }

    /**
     * Returns the cause that forced the Throwable to be reconstructed.
     */
    public Throwable getReconstructCause() {
        return mReconstructCause;
    }
}
