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
import java.io.IOException;
import java.io.ObjectInput;

import java.rmi.RemoteException;

/**
 *
 * @author Brian S O'Neill
 * @see RemoteOutput
 */
public interface RemoteInput extends ObjectInput, Closeable {
    /**
     * Reads the length and of contents the String from a packed format similar
     * to UTF-8.
     */
    String readString() throws IOException;

    Boolean readBooleanObj() throws IOException;

    Byte readByteObj() throws IOException;

    Integer readUnsignedByteObj() throws IOException;

    Short readShortObj() throws IOException;

    Integer readUnsignedShortObj() throws IOException;

    Character readCharObj() throws IOException;

    Integer readIntObj() throws IOException;

    Long readLongObj() throws IOException;

    Float readFloatObj() throws IOException;

    Double readDoubleObj() throws IOException;

    /**
     * Reads OK return marker or throws exception originating from remote
     * server. Any IOException while reading connection is converted to a
     * RemoteException. Because exceptions originating from remote server might
     * be any kind, this method declares throwing Throwable.
     *
     * <p>If remote exception cannot be represented by a local exception class
     * or it cannot be serialized, it is represented as a RemoteException
     * instead, with as much useful information as possible, including server
     * stack trace.
     *
     * @return boolean return value, or false if not applicable
     */
    boolean readOk() throws RemoteException, Throwable;
}
