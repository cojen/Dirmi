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

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SlowSerializable implements Serializable {
    private final long mSleepMillis;
    private transient Object[] mContents;

    public SlowSerializable(long sleepMillis, Object... contents) {
        mSleepMillis = sleepMillis;
        mContents = contents;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        AbstractTestSuite.sleep(mSleepMillis);
        out.writeInt(mContents.length);
        for (Object obj : mContents) {
            out.writeObject(obj);
        }
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int length = in.readInt();
        Object[] contents = new Object[length];
        for (int i=0; i<length; i++) {
            contents[i] = in.readObject();
        }
        mContents = contents;
    }
}
