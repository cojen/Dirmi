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

import java.rmi.Remote;
import java.rmi.RemoteException;

import org.cojen.dirmi.CallMode;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemotePipes extends Remote {
    @Asynchronous
    Pipe basic(Pipe pipe) throws RemoteException;

    @Asynchronous
    Pipe echo(Class type, Pipe pipe) throws RemoteException;

    @Asynchronous
    Pipe echoNoClose(Pipe pipe, int value) throws RemoteException;

    @Asynchronous
    Pipe open(Pipe pipe) throws RemoteException;

    @Asynchronous(CallMode.REQUEST_REPLY)
    Pipe requestReply(Pipe pipe) throws RemoteException;

    @Asynchronous(CallMode.REQUEST_REPLY)
    Pipe requestOnly(Pipe pipe, boolean readEOF) throws RemoteException;

    void setRequestValue(int value) throws RemoteException;

    int getRequestValue() throws RemoteException;

    @Asynchronous(CallMode.REQUEST_REPLY)
    Pipe replyOnly(Pipe pipe) throws RemoteException;
}
