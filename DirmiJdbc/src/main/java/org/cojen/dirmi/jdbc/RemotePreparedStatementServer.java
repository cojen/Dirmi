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

package org.cojen.dirmi.jdbc;

import java.math.BigDecimal;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import java.util.Calendar;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemotePreparedStatementServer extends RemoteStatementServer
    implements RemotePreparedStatement
{
    private final PreparedStatement mStatement;

    public RemotePreparedStatementServer(PreparedStatement st) {
        super(st);
        mStatement = st;
    }

    public ResultSetTransport executeQuery() throws SQLException {
        return new ResultSetTransport(mStatement.executeQuery());
    }

    public int executeUpdate() throws SQLException {
        return mStatement.executeUpdate();
    }

    public void clearParameters() throws SQLException {
        mStatement.clearParameters();
    }

    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        mStatement.setNull(parameterIndex, sqlType);
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        mStatement.setBoolean(parameterIndex, x);
    }

    public void setByte(int parameterIndex, byte x) throws SQLException {
        mStatement.setByte(parameterIndex, x);
    }

    public void setShort(int parameterIndex, short x) throws SQLException {
        mStatement.setShort(parameterIndex, x);
    }

    public void setInt(int parameterIndex, int x) throws SQLException {
        mStatement.setInt(parameterIndex, x);
    }

    public void setLong(int parameterIndex, long x) throws SQLException {
        mStatement.setLong(parameterIndex, x);
    }

    public void setFloat(int parameterIndex, float x) throws SQLException {
        mStatement.setFloat(parameterIndex, x);
    }

    public void setDouble(int parameterIndex, double x) throws SQLException {
        mStatement.setDouble(parameterIndex, x);
    }

    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        mStatement.setBigDecimal(parameterIndex, x);
    }

    public void setString(int parameterIndex, String x) throws SQLException {
        mStatement.setString(parameterIndex, x);
    }

    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        mStatement.setBytes(parameterIndex, x);
    }

    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        mStatement.setDate(parameterIndex, x);
    }

    public void setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        mStatement.setTime(parameterIndex, x);
    }

    public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        mStatement.setTimestamp(parameterIndex, x);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        mStatement.setObject(parameterIndex, x, targetSqlType);
    }

    public void setObject(int parameterIndex, Object x) throws SQLException {
        mStatement.setObject(parameterIndex, x);
    }

    public void setDate(int parameterIndex, java.sql.Date x, Calendar cal) throws SQLException {
        mStatement.setDate(parameterIndex, x, cal);
    }

    public void setTime(int parameterIndex, java.sql.Time x, Calendar cal) throws SQLException {
        mStatement.setTime(parameterIndex, x, cal);
    }

    public void setTimestamp(int parameterIndex, java.sql.Timestamp x, Calendar cal)
        throws SQLException
    {
        mStatement.setTimestamp(parameterIndex, x, cal);
    }

    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        mStatement.setNull(parameterIndex, sqlType, typeName);
    }

    public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
        mStatement.setURL(parameterIndex, x);
    }

    public void setNString(int parameterIndex, String value) throws SQLException {
        mStatement.setNString(parameterIndex, value);
    }

    public boolean execute() throws SQLException {
        return mStatement.execute();
    }

    public void addBatch() throws SQLException {
        mStatement.addBatch();
    }

    public ResultSetMetaDataCopy getMetaData() throws SQLException {
        return new ResultSetMetaDataCopy(mStatement.getMetaData());
    }

    public void setPoolable(boolean poolable) throws SQLException {
        mStatement.setPoolable(poolable);
    }
        
    public boolean isPoolable() throws SQLException {
        return mStatement.isPoolable();
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
        throws SQLException
    {
        mStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
}
