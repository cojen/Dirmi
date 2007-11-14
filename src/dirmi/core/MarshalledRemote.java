/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.core;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import dirmi.info.RemoteInfo;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class MarshalledRemote implements Externalizable {
    private static final long serialVersionUID = 1;

    Identifier mObjID;
    Identifier mTypeID;
    RemoteInfo mInfo;

    MarshalledRemote() {
    }

    MarshalledRemote(Identifier objID, Identifier typeID, RemoteInfo info) {
        mObjID = objID;
        mTypeID = typeID;
        mInfo = info;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        mObjID.write(out);
        mTypeID.write(out);
        out.writeObject(mInfo);
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        mObjID = Identifier.read(in);
        mTypeID = Identifier.read(in);
        mInfo = (RemoteInfo) in.readObject();
    }
}
