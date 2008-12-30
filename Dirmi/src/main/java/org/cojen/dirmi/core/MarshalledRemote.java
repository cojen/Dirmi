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

package org.cojen.dirmi.core;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.cojen.dirmi.info.RemoteInfo;

import org.cojen.dirmi.util.VersionedIdentifier;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class MarshalledRemote implements Marshalled, Externalizable {
    private static final long serialVersionUID = 1;

    VersionedIdentifier mObjID;
    VersionedIdentifier mTypeID;
    RemoteInfo mInfo;

    // Need public constructor for Externalizable.
    public MarshalledRemote() {
    }

    MarshalledRemote(VersionedIdentifier objID, VersionedIdentifier typeID, RemoteInfo info) {
        mObjID = objID;
        mTypeID = typeID;
        mInfo = info;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        mObjID.writeWithNextVersion(out);
        mTypeID.writeWithNextVersion(out);
        out.writeObject(mInfo);
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        mObjID = VersionedIdentifier.readAndUpdateRemoteVersion(in);
        mTypeID = VersionedIdentifier.readAndUpdateRemoteVersion(in);
        mInfo = (RemoteInfo) in.readObject();
    }
}
