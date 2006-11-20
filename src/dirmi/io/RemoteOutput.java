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
    void writeString(String str) throws IOException;

    /**
     * Writes an unshared Boolean object.
     */
    void writeBooleanObj(Boolean v) throws IOException;

    /**
     * Writes an unshared Byte object.
     */
    void writeByteObj(Byte v) throws IOException;

    /**
     * Writes an unshared Short object.
     */
    void writeShortObj(Short v) throws IOException;

    /**
     * Writes an unshared Character object.
     */
    void writeCharObj(Character v) throws IOException;

    /**
     * Writes an unshared Integer object.
     */
    void writeIntObj(Integer v) throws IOException;

    /**
     * Writes an unshared Long object.
     */
    void writeLongObj(Long v) throws IOException;

    /**
     * Writes an unshared Float object.
     */
    void writeFloatObj(Float v) throws IOException;

    /**
     * Writes an unshared Double object.
     */
    void writeDoubleObj(Double v) throws IOException;

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
