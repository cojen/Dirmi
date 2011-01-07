/*
 *  Copyright 2006-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.ObjectOutput;

/**
 * Interface describing the low-level output operations used by remote method
 * invocation.
 *
 * @author Brian S O'Neill
 * @see InvocationInput
 */
public interface InvocationOutput extends ObjectOutput, Flushable, Closeable {
    /**
     * Writes the length and contents of the String in a packed format similar
     * to UTF-8.
     *
     * @param str string of any length or null
     */
    void writeUnsharedString(String str) throws IOException;

    /**
     * Writes an unshared Serializable or Remote object.
     */
    void writeUnshared(Object obj) throws IOException;

    /**
     * Writes a sharable Serializable or Remote object.
     */
    void writeObject(Object obj) throws IOException;

    /**
     * Writes the given Throwable with additional remote information.
     */
    void writeThrowable(Throwable t) throws IOException;

    /**
     * Resets the stream for reuse.
     */
    void reset() throws IOException;
}
