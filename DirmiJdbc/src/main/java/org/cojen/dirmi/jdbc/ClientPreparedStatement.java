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

import java.io.InputStream;
import java.io.Reader;

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

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class ClientPreparedStatement extends ClientStatement
    implements PreparedStatement
{
    private static final Wrapper<ClientPreparedStatement, RemotePreparedStatement> wrapper =
        Wrapper.from(ClientPreparedStatement.class, RemotePreparedStatement.class);

    public static ClientPreparedStatement from(RemotePreparedStatement st) {
        return wrapper.wrap(st);
    }

    private final RemotePreparedStatement mStatement;

    protected ClientPreparedStatement(RemotePreparedStatement st) {
        super(st);
        mStatement = st;
    }

    public ResultSet executeQuery() throws SQLException {
        return new ClientResultSet(mStatement.executeQuery());
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

    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw unsupported();
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw unsupported();
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

    private static SQLException unsupported() throws SQLException {
        return ClientDriver.unsupported();
    }
}
