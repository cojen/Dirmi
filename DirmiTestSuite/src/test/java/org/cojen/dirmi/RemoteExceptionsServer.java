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

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteExceptionsServer implements RemoteExceptions {
    public void stuff() throws NonSerializableException {
        throw new NonSerializableException();
    }

    public void stuff2(String message) throws NonSerializableException {
        throw new NonSerializableException(message);
    }

    public void stuff3(String message, String message2) throws NonSerializableException {
        NonSerializableException t = new NonSerializableException(message);
        t.initCause(new RuntimeException(message2));
        throw t;
    }

    public void stuff4() throws NonSerializableException2 {
        throw new NonSerializableException2((String) null, null);
    }

    public void stuff5(String message) throws NonSerializableException2 {
        throw new NonSerializableException2(message, null);
    }

    public void stuff6(String message, String message2) throws NonSerializableException2 {
        Throwable cause = new RuntimeException(message2);
        throw new NonSerializableException2(message, cause);
    }

    public void stuff7() throws NonSerializableException3 {
        throw new NonSerializableException3((String) null);
    }

    public void stuff8(String message) throws NonSerializableException3 {
        throw new NonSerializableException3(message);
    }

    public void stuff9(String message, String message2) throws NonSerializableException3 {
        NonSerializableException3 t = new NonSerializableException3(message);
        t.initCause(new RuntimeException(message2));
        throw t;
    }

    public void stuffa() throws NonSerializableException4 {
        throw new NonSerializableException4();
    }

    public void stuffb(String message) throws NonSerializableException4 {
        throw new NonSerializableException4(message);
    }

    public void stuffc(String message, String message2) throws NonSerializableException4 {
        NonSerializableException4 t = new NonSerializableException4(message);
        t.initCause(new RuntimeException(message2));
        throw t;
    }
}
