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

package dirmi.jdbc;

import java.io.InputStream;
import java.io.Reader;

import java.math.BigDecimal;

import java.util.Calendar;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ClientPreparedStatement extends ClientStatement implements PreparedStatement {
    private final RemotePreparedStatement mStatement;

    public ClientPreparedStatement(RemotePreparedStatement st) {
        super(st);
        mStatement = st;
    }

    public ResultSet executeQuery() throws SQLException {
        return new ClientResultSet(mStatement.executeQuery());
    }

    public int executeUpdate() throws SQLException {
        return mStatement.executeUpdate();
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

    public void setAsciiStream(int parameterIndex, InputStream x)
        throws SQLException
    {
        throw unsupported();
    }

    public void setAsciiStream(int parameterIndex, InputStream x, int length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setAsciiStream(int parameterIndex, InputStream x, long length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setBinaryStream(int parameterIndex, InputStream x)
        throws SQLException
    {
        throw unsupported();
    }

    public void setBinaryStream(int parameterIndex, InputStream x, int length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setBinaryStream(int parameterIndex, InputStream x, long length)
        throws SQLException
    {
        throw unsupported();
    }

    public void clearParameters() throws SQLException {
        mStatement.clearParameters();
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        mStatement.setObject(parameterIndex, x, targetSqlType);
    }

    public void setObject(int parameterIndex, Object x) throws SQLException {
        mStatement.setObject(parameterIndex, x);
    }

    public boolean execute() throws SQLException {
        return mStatement.execute();
    }

    public void addBatch() throws SQLException {
        mStatement.addBatch();
    }

    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void setCharacterStream(int parameterIndex, Reader reader, int length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setCharacterStream(int parameterIndex, Reader reader, long length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw unsupported();
    }

    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw unsupported();
    }

    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw unsupported();
    }

    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw unsupported();
    }

    public ResultSetMetaData getMetaData() throws SQLException {
        return mStatement.getMetaData();
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

    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw unsupported();
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw unsupported();
    }
 
    public void setNString(int parameterIndex, String value) throws SQLException {
        mStatement.setNString(parameterIndex, value);
    }

    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw unsupported();
    }

    public void setNCharacterStream(int parameterIndex, Reader value, long length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw unsupported();
    }

    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw unsupported();
    }

    public void setBlob(int parameterIndex, InputStream inputStream, long length)
        throws SQLException
    {
        throw unsupported();
    }

    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw unsupported();
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

    private static SQLException unsupported() throws SQLException {
        return ClientDriver.unsupported();
    }
}
