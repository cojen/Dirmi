/*
 *  Copyright 2008 Brian S O'Neill
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

import dirmi.MalformedRemoteObjectException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class MarshalledIntrospectionFailure implements Marshalled, Externalizable {
    private static final long serialVersionUID = 1;

    private String mMessage;
    private String mClassName;
    private Class mClass;

    // Need public constructor for Externalizable.
    public MarshalledIntrospectionFailure() {
    }

    MarshalledIntrospectionFailure(String message, String className) {
        mMessage = message;
        mClassName = className;
        mClass = null;
    }

    MarshalledIntrospectionFailure(String message, Class clazz) {
        mMessage = message;
        mClassName = clazz.getName();
        mClass = clazz;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(mMessage);
        out.writeObject(mClassName);
        out.writeObject(mClass);
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        mMessage = (String) in.readObject();
        mClassName = (String) in.readObject();
        try {
            mClass = (Class) in.readObject();
        } catch (ClassNotFoundException e) {
            mClass = null;
        }
    }

    public MalformedRemoteObjectException toException() {
        if (mClass == null) {
            return new MalformedRemoteObjectException(mMessage, mClassName);
        } else {
            return new MalformedRemoteObjectException(mMessage, mClass);
        }
    }
}

