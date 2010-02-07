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

import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class RemotePreparedStatementServer extends RemoteStatementServer
    implements RemotePreparedStatement
{
    private static final Wrapper<RemotePreparedStatementServer, PreparedStatement> wrapper =
        Wrapper.from(RemotePreparedStatementServer.class, PreparedStatement.class);

    public static RemotePreparedStatementServer from(PreparedStatement st) {
        return wrapper.wrap(st);
    }

    private final PreparedStatement mStatement;

    protected RemotePreparedStatementServer(PreparedStatement st) {
        super(st);
        mStatement = st;
    }

    public ResultSetTransport executeQuery() throws SQLException {
        return new ResultSetTransport(mStatement.executeQuery());
    }

    public ResultSetMetaDataCopy getMetaData() throws SQLException {
        return new ResultSetMetaDataCopy(mStatement.getMetaData());
    }
}
