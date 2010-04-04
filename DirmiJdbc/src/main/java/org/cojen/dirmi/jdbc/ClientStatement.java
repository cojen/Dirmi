/*
 *  Copyright 2007-2010 Brian S O'Neill
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class ClientStatement implements Statement {
    private static final Wrapper<ClientStatement, RemoteStatement> wrapper =
        Wrapper.from(ClientStatement.class, RemoteStatement.class);

    public static ClientStatement from(RemoteStatement st) {
        return wrapper.wrap(st);
    }

    private final RemoteStatement mStatement;

    protected ClientStatement(RemoteStatement st) {
        mStatement = st;
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        return new ClientResultSet(mStatement.executeQuery(sql));
    }

    public ResultSet getResultSet() throws SQLException {
        return new ClientResultSet(mStatement.getResultSet());
    } 

    public Connection getConnection() throws SQLException {
        throw unsupported();
    }

    public ResultSet getGeneratedKeys() throws SQLException {
        return new ClientResultSet(mStatement.getGeneratedKeys());
    }

    public void setPoolable(boolean poolable) throws SQLException {
    }

    public boolean isPoolable() throws SQLException {
        return true;
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw unsupported();
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    private static SQLException unsupported() throws SQLException {
        return ClientDriver.unsupported();
    }
}
