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

import java.io.IOException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemotePipesServer implements RemotePipes {
    public Pipe basic(Pipe pipe) {
        try {
            int i = pipe.readInt();
            pipe.writeInt(i);
            pipe.flush();
        } catch (IOException e) {
        }
        return null;
    }

    public Pipe echo(Class type, Pipe pipe) {
        try {
            if (type == byte[].class) {
                byte[] bytes = new byte[pipe.readInt()];
                pipe.readFully(bytes);
                pipe.write(bytes);
            } else if (type == boolean.class) {
                pipe.writeBoolean(pipe.readBoolean());
            } else if (type == byte.class) {
                pipe.writeByte(pipe.readByte());
            } else if (type == short.class) {
                pipe.writeShort(pipe.readShort());
            } else if (type == char.class) {
                pipe.writeChar(pipe.readChar());
            } else if (type == int.class) {
                pipe.writeInt(pipe.readInt());
            } else if (type == long.class) {
                pipe.writeLong(pipe.readLong());
            } else if (type == float.class) {
                pipe.writeFloat(pipe.readFloat());
            } else if (type == double.class) {
                pipe.writeDouble(pipe.readDouble());
            } else if (type == String.class) {
                pipe.writeUTF(pipe.readUTF());
            } else if (type == Throwable.class) {
                pipe.writeThrowable(pipe.readThrowable());
            } else if (type == Object.class) {
                pipe.writeObject(pipe.readObject());
            }

            pipe.close();
        } catch (Exception e) {
        }

        return null;
    }

    public Pipe echoNoClose(Pipe pipe, int value) {
        try {
            pipe.writeInt(value);
            pipe.flush();
        } catch (Exception e) {
        }
        return null;
    }

    public Pipe open(Pipe pipe) {
        return null;
    }

    public Pipe requestReply(Pipe pipe) {
        try {
            int i = pipe.readInt();
            pipe.writeInt(i + 1);
            pipe.close();
        } catch (IOException e) {
        }
        return null;
    }
}
