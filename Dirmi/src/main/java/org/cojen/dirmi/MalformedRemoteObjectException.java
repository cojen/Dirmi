/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import java.rmi.RemoteException;

/**
 * Thrown when a remote object cannot be transferred because it has a malformed
 * specification.
 *
 * @author Brian S O'Neill
 */
public class MalformedRemoteObjectException extends RemoteException {
    private static final long serialVersionUID = 1;

    private static String makeMessage(String message, String className) {
        if (className == null) {
            return message;
        }
        return "Defined in " + className + ": " + message;
    }

    private final String mClassName;
    private final Class mClass;

    public MalformedRemoteObjectException(String message, String className) {
        super(makeMessage(message, className));
        mClassName = className;
        mClass = null;
    }

    public MalformedRemoteObjectException(String message, Class clazz) {
        super(makeMessage(message, clazz.getName()));
        mClassName = clazz.getName();
        mClass = clazz;
    }

    /**
     * Returns the malformed class name.
     */
    public String getRemoteClassName() {
        return mClassName;
    }

    /**
     * Returns the malformed class, or null if it cannot be loaded.
     */
    public Class getRemoteClass() {
        return mClass;
    }
}
