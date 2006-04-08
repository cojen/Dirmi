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
import java.io.Flushable;
import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteOutput extends Flushable, Closeable {
    void write(boolean v) throws IOException;

    void write(byte v) throws IOException;

    void write(short v) throws IOException;

    void write(char v) throws IOException;

    void write(int v) throws IOException;

    void write(long v) throws IOException;

    void write(float v) throws IOException;

    void write(double v) throws IOException;

    /*
    void write(Boolean v) throws IOException;

    void write(Byte v) throws IOException;

    void write(Short v) throws IOException;

    void write(Character v) throws IOException;

    void write(Integer v) throws IOException;

    void write(Long v) throws IOException;

    void write(Float v) throws IOException;

    void write(Double v) throws IOException;

    void write(String str) throws IOException;

    void writeNull() throws IOException;
    */

    /**
     * Write serialized object.
     */
    void write(Object obj) throws IOException;

    /**
     * Write length in variable amount of bytes.
     */
    void writeLength(int length) throws IOException;

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
