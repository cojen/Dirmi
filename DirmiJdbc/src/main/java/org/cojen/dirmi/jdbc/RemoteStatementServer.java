/*
 *  Copyright 2007-2009 Brian S O'Neill
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

package org.cojen.dirmi.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class RemoteStatementServer implements RemoteStatement {
    private static final Wrapper<RemoteStatementServer, Statement> wrapper =
        Wrapper.from(RemoteStatementServer.class, Statement.class);

    public static RemoteStatementServer from(Statement st) {
        return wrapper.wrap(st);
    }

    static final int DEFAULT_FETCH_SIZE = 1000;

    private final Statement mStatement;
    private int mFetchSize = DEFAULT_FETCH_SIZE;

    protected RemoteStatementServer(Statement st) {
        mStatement = st;
    }

    public ResultSetTransport executeQuery(String sql) throws SQLException {
        mStatement.setFetchSize(mFetchSize);
        return new ResultSetTransport(mStatement.executeQuery(sql));
    }

    public ResultSetTransport getResultSet() throws SQLException {
        return new ResultSetTransport(mStatement.getResultSet());
    }

    public void setFetchSize(int rows) throws SQLException {
        if (rows <= 0) {
            rows = DEFAULT_FETCH_SIZE;
        }
        mFetchSize = rows;
    }
  
    public int getFetchSize() throws SQLException {
        return mFetchSize;
    }

    public ResultSetTransport getGeneratedKeys() throws SQLException {
        mStatement.setFetchSize(mFetchSize);
        return new ResultSetTransport(mStatement.getGeneratedKeys());
    }
}
