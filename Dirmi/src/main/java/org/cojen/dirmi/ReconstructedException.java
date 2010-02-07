/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import java.rmi.RemoteException;

/**
 * Thrown by a remote method or {@link Pipe#readThrowable} if the Throwable was
 * not serializable or depends on classes that are not found. If a pipe was
 * used, it cannot be used any more and must be closed.
 *
 * @author Brian S O'Neill
 */
public class ReconstructedException extends RemoteException {
    private static final long serialVersionUID = 1;

    private static String makeMessage(Throwable reconstructed, Throwable reconstructCause) {
        StringBuilder b = new StringBuilder("Reconstructed exception of type ")
            .append(reconstructed.getClass().getName());

        if (reconstructed.getMessage() != null) {
            b.append(" with message \"").append(reconstructed.getMessage()).append('"');
        }

        return b.append("; ").append(reconstructCause).toString();
    }

    private static String makeMessage(String reconstructedClassName, String message,
                                      Throwable reconstructCause)
    {
        StringBuilder b = new StringBuilder("Unable to reconstruct exception of type ")
            .append(reconstructedClassName);

        if (message != null) {
            b.append(" with message \"").append(message).append('"');
        }

        return b.append("; ").append(reconstructCause).toString();
    }

    private final Throwable mReconstructCause;

    public ReconstructedException(Throwable reconstructCause, Throwable reconstructed) {
        super(makeMessage(reconstructed, reconstructCause), reconstructed);
        mReconstructCause = reconstructCause;
    }

    public ReconstructedException(Throwable reconstructCause,
                                  String reconstructedClassName, String message, Throwable cause)
    {
        super(makeMessage(reconstructedClassName, message, reconstructCause), cause);
        mReconstructCause = reconstructCause;
    }

    /**
     * Returns the Throwable which was reconstructed, or null if not possible.
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
