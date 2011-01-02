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

import java.util.ArrayList;
import java.util.List;

import java.sql.SQLException;

import org.junit.Assert;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteFaceServer implements RemoteFace, SessionAware {
    private String mMessage;

    public synchronized void doIt() {
        mMessage = "done";
    }

    public synchronized String getMessage() {
        return mMessage;
    }

    public synchronized void receive(String message) {
        mMessage = message;
    }

    public Remote echo(Remote obj) {
        return obj;
    }

    public <T> T echoObject(T obj) {
        return obj;
    }

    public List<String> calculate(int param, Integer p2, String message, List<String> params)
        throws RemoteException
    {
        List<String> list = new ArrayList<String>();
        list.add(String.valueOf(param));
        list.add(String.valueOf(p2));
        list.add(message);
        list.addAll(params);
        return list;
    }

    public void fail(int[] params) {
        if (params == null) {
            throw new IllegalArgumentException("no params");
        }
    }

    public void sessionAccess() {
        try {
            SessionAccess.obtain(this);
            Assert.fail();
        } catch (IllegalArgumentException e) {
        }

        Link session = SessionAccess.current();
        Assert.assertNotNull(session);
    }

    public String[] executeQuery(String sql) throws SQLException {
        if (sql == null) {
            throw new SQLException("no query");
        }
        return new String[] {sql, "row 1", "row 2", "row 3"};
    }

    public String[] executeQuery2(String sql) throws SQLException {
        return executeQuery(sql);
    }

    public String[] executeQuery3(String sql) {
        return new String[] {sql, "row 1", "row 2", "row 3"};
    }

    public String[] executeQuery4(String sql) {
        if (sql == null) {
            throw new RuntimeException("no query");
        }
        return new String[] {sql, "row 1", "row 2", "row 3"};
    }
}
