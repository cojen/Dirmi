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

import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteInput extends Closeable {
    boolean readBoolean() throws IOException;

    byte readByte() throws IOException;

    short readShort() throws IOException;

    char readChar() throws IOException;

    int readInt() throws IOException;

    long readLong() throws IOException;

    float readFloat() throws IOException;

    double readDouble() throws IOException;

    /*
    boolean readBooleanObj() throws IOException;

    Byte readByteObj() throws IOException;

    Short readShortObj() throws IOException;

    Character readCharacterObj() throws IOException;

    Integer readIntegerObj() throws IOException;

    Long readLongObj() throws IOException;

    Float readFloatObj() throws IOException;

    Double readDoubleObj() throws IOException;

    String readString() throws IOException;
    */

    Object readObject() throws IOException, ClassNotFoundException;

    /**
     * Reads length value or null.
     */
    Integer readLength() throws IOException;

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
     * @return boolean return value, if applicable
     */
    boolean readOk() throws RemoteException, Throwable;
}
