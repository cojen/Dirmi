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

import java.rmi.RemoteException;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;

import java.util.Properties;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ClientConnection implements Connection {
    private final RemoteConnection mConnection;

    public ClientConnection(RemoteConnection con) {
        mConnection = con;
    }

    public Statement createStatement() throws SQLException {
        return new ClientStatement(mConnection.createStatement());
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new ClientPreparedStatement(mConnection.prepareStatement(sql));
    }

    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLException("FIXME (prepareCall)");
    }

    public String nativeSQL(String sql) throws SQLException {
        return mConnection.nativeSQL(sql);
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        mConnection.setAutoCommit(autoCommit);
    }

    public boolean getAutoCommit() throws SQLException {
        return mConnection.getAutoCommit();
    }

    public void commit() throws SQLException {
        mConnection.commit();
    }

    public void rollback() throws SQLException {
        mConnection.rollback();
    }

    public void close() throws SQLException {
        mConnection.close();
    }

    public boolean isClosed() throws SQLException {
        return mConnection.isClosed();
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        return new ClientDatabaseMetaData(mConnection.getMetaData());
    }

    public void setReadOnly(boolean readOnly) throws SQLException {
        mConnection.setReadOnly(readOnly);
    }

    public boolean isReadOnly() throws SQLException {
        return mConnection.isReadOnly();
    }

    public void setCatalog(String catalog) throws SQLException {
        mConnection.setCatalog(catalog);
    }

    public String getCatalog() throws SQLException {
        return mConnection.getCatalog();
    }

    public void setTransactionIsolation(int level) throws SQLException {
        mConnection.setTransactionIsolation(level);
    }

    public int getTransactionIsolation() throws SQLException {
        return mConnection.getTransactionIsolation();
    }

    public SQLWarning getWarnings() throws SQLException {
        return mConnection.getWarnings();
    }

    public void clearWarnings() throws SQLException {
        mConnection.clearWarnings();
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency) 
        throws SQLException
    {
        return new ClientStatement
            (mConnection.createStatement(resultSetType, resultSetConcurrency));
    }

    public PreparedStatement prepareStatement(String sql, int resultSetType, 
                                              int resultSetConcurrency)
        throws SQLException
    {
        return new ClientPreparedStatement
            (mConnection.prepareStatement(sql, resultSetType, resultSetConcurrency));
    }

    public CallableStatement prepareCall(String sql, int resultSetType, 
                                         int resultSetConcurrency)
        throws SQLException
    {
        throw new SQLException("FIXME (prepareCall)");
    }

    public java.util.Map<String,Class<?>> getTypeMap() throws SQLException {
        return mConnection.getTypeMap();
    }

    public void setTypeMap(java.util.Map<String,Class<?>> map) throws SQLException {
        mConnection.setTypeMap(map);
    }

    public void setHoldability(int holdability) throws SQLException {
        mConnection.setHoldability(holdability);
    }

    public int getHoldability() throws SQLException {
        return mConnection.getHoldability();
    }

    public Savepoint setSavepoint() throws SQLException {
        throw new SQLException("FIXME (setSavepoint)");
    }

    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLException("FIXME (setSavepoint)");
    }

    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLException("FIXME (rollback)");
    }

    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLException("FIXME (releaseSavepoint)");
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency, 
                                     int resultSetHoldability)
        throws SQLException
    {
        return new ClientStatement
            (mConnection.createStatement(resultSetType, resultSetConcurrency,
                                         resultSetHoldability));
    }

    public PreparedStatement prepareStatement(String sql, int resultSetType, 
                                              int resultSetConcurrency, int resultSetHoldability)
        throws SQLException
    {
        return new ClientPreparedStatement
            (mConnection.prepareStatement
             (sql, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    public CallableStatement prepareCall(String sql, int resultSetType, 
                                         int resultSetConcurrency, 
                                         int resultSetHoldability)
        throws SQLException
    {
        throw new SQLException("FIXME (prepareCall)");
    }

    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
        throws SQLException
    {
        return new ClientPreparedStatement
            (mConnection.prepareStatement(sql, autoGeneratedKeys));
    }

    public PreparedStatement prepareStatement(String sql, int columnIndexes[])
        throws SQLException
    {
        return new ClientPreparedStatement(mConnection.prepareStatement(sql, columnIndexes));
    }

    public PreparedStatement prepareStatement(String sql, String columnNames[])
        throws SQLException
    {
        return new ClientPreparedStatement(mConnection.prepareStatement(sql, columnNames));
    }

    public Clob createClob() throws SQLException {
        throw new SQLException("FIXME (createClob)");
    }

    public Blob createBlob() throws SQLException {
        throw new SQLException("FIXME (createBlob)");
    }
    
    public NClob createNClob() throws SQLException {
        throw unsupported();
    }

    public SQLXML createSQLXML() throws SQLException {
        throw unsupported();
    }

    public boolean isValid(int timeout) throws SQLException {
        return mConnection.isValid(timeout);
    }

    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        try {
            mConnection.setClientInfo(name, value);
        } catch (RemoteException e) {
            SQLClientInfoException ex = new SQLClientInfoException();
            ex.initCause(e);
            throw ex;
        }
    }
        
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        try {
            mConnection.setClientInfo(properties);
        } catch (RemoteException e) {
            SQLClientInfoException ex = new SQLClientInfoException();
            ex.initCause(e);
            throw ex;
        }
    }
        
    public String getClientInfo(String name) throws SQLException {
        return mConnection.getClientInfo(name);
    }
        
    public Properties getClientInfo() throws SQLException {
        return mConnection.getClientInfo();
    }      

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw unsupported();
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw unsupported();
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
