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

import java.io.InputStream;
import java.io.Reader;

import java.math.BigDecimal;

import java.rmi.Remote;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLXML;

import java.util.Calendar;

import org.cojen.dirmi.Batched;
import org.cojen.dirmi.RemoteFailure;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@RemoteFailure(exception=SQLException.class)
public interface RemotePreparedStatement extends RemoteStatement {
    ResultSetTransport executeQuery() throws SQLException;

    int executeUpdate() throws SQLException;

    @Batched
    void clearParameters() throws SQLException;

    @Batched
    void setNull(int parameterIndex, int sqlType) throws SQLException;

    @Batched
    void setBoolean(int parameterIndex, boolean x) throws SQLException;

    @Batched
    void setByte(int parameterIndex, byte x) throws SQLException;

    @Batched
    void setShort(int parameterIndex, short x) throws SQLException;

    @Batched
    void setInt(int parameterIndex, int x) throws SQLException;

    @Batched
    void setLong(int parameterIndex, long x) throws SQLException;

    @Batched
    void setFloat(int parameterIndex, float x) throws SQLException;

    @Batched
    void setDouble(int parameterIndex, double x) throws SQLException;

    @Batched
    void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException;

    @Batched
    void setString(int parameterIndex, String x) throws SQLException;

    @Batched
    void setBytes(int parameterIndex, byte[] x) throws SQLException;

    @Batched
    void setDate(int parameterIndex, java.sql.Date x)
        throws SQLException;

    @Batched
    void setTime(int parameterIndex, java.sql.Time x) 
        throws SQLException;

    @Batched
    void setTimestamp(int parameterIndex, java.sql.Timestamp x)
        throws SQLException;

    @Batched
    void setObject(int parameterIndex, Object x, int targetSqlType) 
        throws SQLException;

    @Batched
    void setObject(int parameterIndex, Object x) throws SQLException;

    @Batched
    void setDate(int parameterIndex, java.sql.Date x, Calendar cal)
        throws SQLException;

    @Batched
    void setTime(int parameterIndex, java.sql.Time x, Calendar cal) 
        throws SQLException;

    @Batched
    void setTimestamp(int parameterIndex, java.sql.Timestamp x, Calendar cal)
        throws SQLException;

    @Batched
    void setNull(int parameterIndex, int sqlType, String typeName) 
        throws SQLException;

    @Batched
    void setURL(int parameterIndex, java.net.URL x) throws SQLException;

    @Batched
    void setNString(int parameterIndex, String value) throws SQLException;

    /* FIXME
    void setAsciiStream(int parameterIndex, java.io.InputStream x, int length)
        throws SQLException;

    void setUnicodeStream(int parameterIndex, java.io.InputStream x, 
                          int length) throws SQLException;

    void setBinaryStream(int parameterIndex, java.io.InputStream x, 
                         int length) throws SQLException;

    void setCharacterStream(int parameterIndex,
                            java.io.Reader reader,
                            int length) throws SQLException;
    */

    /* FIXME
    @Batched
    void setRef (int parameterIndex, Ref x) throws SQLException;

    @Batched
    void setBlob (int parameterIndex, Blob x) throws SQLException;

    @Batched
    void setClob (int parameterIndex, Clob x) throws SQLException;

    @Batched
    void setArray (int parameterIndex, Array x) throws SQLException;
    */

    /* FIXME
    @Batched
    void setRowId(int parameterIndex, RowId x) throws SQLException;

    @Batched
    void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException;

    @Batched
    void setNClob(int parameterIndex, NClob value) throws SQLException;

    void setClob(int parameterIndex, Reader reader, long length)
        throws SQLException;

    void setBlob(int parameterIndex, InputStream inputStream, long length)
        throws SQLException;

    void setNClob(int parameterIndex, Reader reader, long length)
        throws SQLException;

    @Batched
    void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException;
    */

    boolean execute() throws SQLException;

    @Batched
    void addBatch() throws SQLException;

    ResultSetMetaDataCopy getMetaData() throws SQLException;

    /* FIXME
    ParameterMetaData getParameterMetaData() throws SQLException;
    */
 
    @Batched
    void setPoolable(boolean poolable) throws SQLException;
        
    boolean isPoolable() throws SQLException;

    @Batched
    void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
        throws SQLException;
}
