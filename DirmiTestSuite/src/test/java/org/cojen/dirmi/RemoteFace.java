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

import java.util.List;

import java.sql.SQLException;

/**
 * Remote interface for testing basic Dirmi features.
 *
 * @author Brian S O'Neill
 */
public interface RemoteFace extends Remote {
    void doIt() throws RemoteException;

    String getMessage() throws RemoteException;

    void receive(String message) throws RemoteException;

    Remote echo(Remote obj) throws RemoteException;

    <T> T echoObject(T obj) throws RemoteException;

    List<String> calculate(int param, Integer p2, String message, List<String> params)
        throws RemoteException;

    void fail(int[] params) throws RemoteException, IllegalArgumentException;

    String[] executeQuery(String sql) throws RemoteException, SQLException;

    @RemoteFailure(exception=SQLException.class)
    String[] executeQuery2(String sql) throws SQLException;

    @RemoteFailure(exception=SQLException.class, declared=false)
    String[] executeQuery3(String sql);

    @RemoteFailure(exception=RuntimeException.class)
    String[] executeQuery4(String sql);
}
