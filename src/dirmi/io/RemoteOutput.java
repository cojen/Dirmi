/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi.io;

import java.io.Closeable;
import java.io.Externalizable;
import java.io.Flushable;
import java.io.IOException;
import java.io.ObjectOutput;

/**
 *
 * @author Brian S O'Neill
 * @see RemoteInput
 */
public interface RemoteOutput extends ObjectOutput, Flushable, Closeable {
    /**
     * Writes the raw float bits as a 32-bit integer.
     */
    void writeFloat(float v) throws IOException;

    /**
     * Writes the raw double bits as a 64-bit integer.
     */
    void writeDouble(double v) throws IOException;

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
     * Writes OK marker, indicating method completed with no exceptions.
     */
    void writeOk() throws IOException;

    /**
     * Writes OK marker combined with boolean return value.
     */
    void writeOk(boolean result) throws IOException;

    /**
     * Writes not-OK marker, followed by the given Throwable.
     */
    void writeThrowable(Throwable t) throws IOException;
}
