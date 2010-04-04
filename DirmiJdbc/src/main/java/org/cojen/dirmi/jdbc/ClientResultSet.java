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
import java.io.IOException;
import java.io.Reader;

import java.math.BigDecimal;

import java.rmi.RemoteException;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;

import java.util.Calendar;
import java.util.Map;

import org.cojen.dirmi.Pipe;
import org.cojen.dirmi.ReconstructedException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ClientResultSet implements ResultSet {
    private final ResultSetTransport mTransport;
    private final Pipe mRowPipe;

    private boolean mClosed;
    private ResultSetRow mActiveRow;

    private boolean mWasNull;

    public ClientResultSet(ResultSetTransport t) throws SQLException {
        mTransport = t;
        try {
            mRowPipe = t.getFetcher().fetch(null);
        } catch (RemoteException e) {
            throw new SQLException(e);
        }
    }

    public void close() throws SQLException {
        mClosed = true;
        mActiveRow = null;
        try {
            mRowPipe.close();
        } catch (IOException e) {
            // Ignore
        }
        try {
            mTransport.close();
        } catch (RemoteException e) {
            throw new SQLException(e);
        }
    }

    public boolean isClosed() throws SQLException {
        return mClosed;
    }

    public ResultSetMetaData getMetaData() throws SQLException {
        return mTransport.getMetaData();
    }

    public Statement getStatement() throws SQLException {
        return null;
    }

    public SQLWarning getWarnings() throws SQLException {
        try {
            return mTransport.getFetcher().getWarnings();
        } catch (RemoteException e) {
            throw new SQLException(e);
        }
    }

    public void clearWarnings() throws SQLException {
        try {
            mTransport.getFetcher().clearWarnings();
        } catch (RemoteException e) {
            throw new SQLException(e);
        }
    }

    public String getCursorName() throws SQLException {
        try {
            return mTransport.getFetcher().getCursorName();
        } catch (RemoteException e) {
            throw new SQLException(e);
        }
    }

    public boolean next() throws SQLException {
        if (mClosed) {
            return false;
        }
        try {
            if ((mActiveRow = (ResultSetRow) mRowPipe.readObject()) == null) {
                SQLException e = (SQLException) mRowPipe.readThrowable();
                if (e != null) {
                    silentClose();
                    throw e;
                }
                return false;
            }
            return true;
        } catch (ReconstructedException e) {
            silentClose();
            throw new SQLException(e.getCause());
        } catch (IOException e) {
            silentClose();
            throw new SQLException(e);
        } catch (ClassNotFoundException e) {
            silentClose();
            throw new SQLException(e);
        }
    }

    public int findColumn(String columnName) throws SQLException {
        return mTransport.getMetaData().findColumn(columnName);
    }

    public boolean wasNull() throws SQLException {
        return mWasNull;
    }

    public Object getObject(int columnIndex) throws SQLException {
        if (mClosed) {
            throw new SQLException("Result set closed");
        }

        if (mActiveRow == null) {
            throw new SQLException("No active row. Call \"next\" method.");
        }

        Object obj = mActiveRow.getValue(columnIndex);
        mWasNull = obj == null;

        return obj;
    }

    public Object getObject(String columnName) throws SQLException {
        return getObject(findColumn(columnName));
    }

    public String getString(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        return obj == null ? null : obj.toString();
    }

    public boolean getBoolean(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return false;
        } else if (obj instanceof Boolean) {
            return (Boolean) obj;
        }
        throw unsupportedType("boolean", obj);
    }

    public byte getByte(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Byte) {
            return (Byte) obj;
        } else if (obj instanceof String) {
            try {
                return Byte.parseByte((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            try {
                return ((BigDecimal) obj).byteValueExact();
            } catch (ArithmeticException e) {
            }
        } else if (obj instanceof Short) {
            short val = (Short) obj;
            if (((byte) val) == val) {
                return (byte) val;
            }
        } else if (obj instanceof Integer) {
            int val = (Integer) obj;
            if (((byte) val) == val) {
                return (byte) val;
            }
        } else if (obj instanceof Long) {
            long val = (Long) obj;
            if (((byte) val) == val) {
                return (byte) val;
            }
        } else if (obj instanceof Float) {
            float val = (Float) obj;
            if (((byte) val) == val) {
                return (byte) val;
            }
        } else if (obj instanceof Double) {
            double val = (Double) obj;
            if (((byte) val) == val) {
                return (byte) val;
            }
        }

        throw unsupportedType("byte", obj);
    }

    public short getShort(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Short) {
            return (Short) obj;
        } else if (obj instanceof String) {
            try {
                return Short.parseShort((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            try {
                return ((BigDecimal) obj).shortValueExact();
            } catch (ArithmeticException e) {
            }
        } else if (obj instanceof Byte) {
            return (short) ((Byte) obj);
        } else if (obj instanceof Integer) {
            int val = (Integer) obj;
            if (((short) val) == val) {
                return (short) val;
            }
        } else if (obj instanceof Long) {
            long val = (Long) obj;
            if (((short) val) == val) {
                return (short) val;
            }
        } else if (obj instanceof Float) {
            float val = (Float) obj;
            if (((short) val) == val) {
                return (short) val;
            }
        } else if (obj instanceof Double) {
            double val = (Double) obj;
            if (((short) val) == val) {
                return (short) val;
            }
        }

        throw unsupportedType("short", obj);
    }

    public int getInt(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Integer) {
            return (Integer) obj;
        } else if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            try {
                return ((BigDecimal) obj).intValueExact();
            } catch (ArithmeticException e) {
            }
        } else if (obj instanceof Byte) {
            return (int) ((Byte) obj);
        } else if (obj instanceof Short) {
            return (int) ((Short) obj);
        } else if (obj instanceof Long) {
            long val = (Long) obj;
            if (((int) val) == val) {
                return (int) val;
            }
        } else if (obj instanceof Float) {
            float val = (Float) obj;
            if (((int) val) == val) {
                return (int) val;
            }
        } else if (obj instanceof Double) {
            double val = (Double) obj;
            if (((int) val) == val) {
                return (int) val;
            }
        }

        throw unsupportedType("int", obj);
    }

    public long getLong(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Long) {
            return (Long) obj;
        } else if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            try {
                return ((BigDecimal) obj).longValueExact();
            } catch (ArithmeticException e) {
            }
        } else if (obj instanceof Byte) {
            return (long) ((Byte) obj);
        } else if (obj instanceof Short) {
            return (long) ((Short) obj);
        } else if (obj instanceof Integer) {
            return (long) ((Integer) obj);
        } else if (obj instanceof Float) {
            float val = (Float) obj;
            if (((long) val) == val) {
                return (long) val;
            }
        } else if (obj instanceof Double) {
            double val = (Double) obj;
            if (((long) val) == val) {
                return (long) val;
            }
        }

        throw unsupportedType("long", obj);
    }

    public float getFloat(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Float) {
            return (Float) obj;
        } else if (obj instanceof String) {
            try {
                return Float.parseFloat((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal) obj).floatValue();
        } else if (obj instanceof Byte) {
            return (float) ((Byte) obj);
        } else if (obj instanceof Short) {
            return (float) ((Short) obj);
        } else if (obj instanceof Integer) {
            int val = (Integer) obj;
            if (((float) val) == val) {
                return (float) val;
            }
        } else if (obj instanceof Long) {
            long val = (Long) obj;
            if (((float) val) == val) {
                return (float) val;
            }
        } else if (obj instanceof Double) {
            double val = (Double) obj;
            if (((float) val) == val) {
                return (float) val;
            }
        }

        throw unsupportedType("float", obj);
    }

    public double getDouble(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return 0;
        } else if (obj instanceof Double) {
            return (Double) obj;
        } else if (obj instanceof String) {
            try {
                return Double.parseDouble((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal) obj).doubleValue();
        } else if (obj instanceof Byte) {
            return (double) ((Byte) obj);
        } else if (obj instanceof Short) {
            return (double) ((Short) obj);
        } else if (obj instanceof Integer) {
            return (double) ((Integer) obj);
        } else if (obj instanceof Float) {
            return (double) ((Float) obj);
        } else if (obj instanceof Long) {
            long val = (Long) obj;
            if (((double) val) == val) {
                return (double) val;
            }
        }

        throw unsupportedType("double", obj);
    }

    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        } else if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        } else if (obj instanceof String) {
            try {
                return new BigDecimal((String) obj);
            } catch (NumberFormatException e) {
            }
        } else if (obj instanceof Byte) {
            return new BigDecimal((int) (Byte) obj);
        } else if (obj instanceof Short) {
            return new BigDecimal((int) (Short) obj);
        } else if (obj instanceof Integer) {
            return new BigDecimal((int) (Integer) obj);
        } else if (obj instanceof Long) {
            return new BigDecimal((long) (Long) obj);
        } else if (obj instanceof Float) {
            return new BigDecimal((double) (Float) obj);
        } else if (obj instanceof Double) {
            return new BigDecimal((double) (Double) obj);
        }

        throw unsupportedType(BigDecimal.class.getName(), obj);
    }

    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        throw unsupported();
    }

    public byte[] getBytes(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        } else if (obj instanceof byte[]) {
            return (byte[]) obj;
        }

        throw unsupportedType("byte[]", obj);
    }

    public java.sql.Date getDate(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        } else if (obj instanceof java.sql.Date) {
            return (java.sql.Date) obj;
        } else if (obj instanceof java.util.Date) {
            return new java.sql.Date(((java.util.Date) obj).getTime());
        }

        throw unsupportedType(java.sql.Date.class.getName(), obj);
    }

    public java.sql.Date getDate(int columnIndex, Calendar cal) throws SQLException {
        // FIXME: support time zone
        return getDate(columnIndex);
    }

    public java.sql.Time getTime(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        } else if (obj instanceof java.sql.Time) {
            return (java.sql.Time) obj;
        } else if (obj instanceof java.util.Date) {
            return new java.sql.Time(((java.util.Date) obj).getTime());
        }

        throw unsupportedType(java.sql.Time.class.getName(), obj);
    }

    public java.sql.Time getTime(int columnIndex, Calendar cal) throws SQLException {
        // FIXME: support time zone
        return getTime(columnIndex);
    }

    public java.sql.Timestamp getTimestamp(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
        } else if (obj instanceof java.sql.Timestamp) {
            return (java.sql.Timestamp) obj;
        } else if (obj instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) obj).getTime());
        }

        throw unsupportedType(java.sql.Timestamp.class.getName(), obj);
    }

    public java.sql.Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        // FIXME: support time zone
        return getTimestamp(columnIndex);
    }

    public Blob getBlob(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
            // FIXME: RemoteBlob
        } else if (obj instanceof Blob) {
            return (Blob) obj;
        }

        throw unsupportedType(Blob.class.getName(), obj);
    }

    public Clob getClob(int columnIndex) throws SQLException {
        Object obj = getObject(columnIndex);
        if (obj == null) {
            return null;
            // FIXME: RemoteClob
        } else if (obj instanceof Clob) {
            return (Clob) obj;
        }

        throw unsupportedType(Clob.class.getName(), obj);
    }

    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        throw new SQLException("FIXME (getAsciiStream)");
    }

    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        throw new SQLException("FIXME (getBinaryStream)");
    }

    public Reader getCharacterStream(int columnIndex) throws SQLException {
        throw new SQLException("FIXME (getCharacterStream)");
    }

    public Array getArray(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public NClob getNClob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public String getNString(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public Object getObject(int columnIndex, Map<String,Class<?>> map)
        throws SQLException
    {
        throw unsupported();
    }

    public Ref getRef(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public java.net.URL getURL(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public RowId getRowId(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw unsupported();
    }

    public String getString(String columnName) throws SQLException {
        return getString(findColumn(columnName));
    }

    public boolean getBoolean(String columnName) throws SQLException {
        return getBoolean(findColumn(columnName));
    }

    public byte getByte(String columnName) throws SQLException {
        return getByte(findColumn(columnName));
    }

    public short getShort(String columnName) throws SQLException {
        return getShort(findColumn(columnName));
    }

    public int getInt(String columnName) throws SQLException {
        return getInt(findColumn(columnName));
    }

    public long getLong(String columnName) throws SQLException {
        return getLong(findColumn(columnName));
    }

    public float getFloat(String columnName) throws SQLException {
        return getFloat(findColumn(columnName));
    }

    public double getDouble(String columnName) throws SQLException {
        return getDouble(findColumn(columnName));
    }

    public BigDecimal getBigDecimal(String columnName, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnName), scale);
    }

    public byte[] getBytes(String columnName) throws SQLException {
        return getBytes(findColumn(columnName));
    }

    public java.sql.Date getDate(String columnName) throws SQLException {
        return getDate(findColumn(columnName));
    }

    public java.sql.Time getTime(String columnName) throws SQLException {
        return getTime(findColumn(columnName));
    }

    public java.sql.Timestamp getTimestamp(String columnName) throws SQLException {
        return getTimestamp(findColumn(columnName));
    }

    public InputStream getAsciiStream(String columnName) throws SQLException {
        return getAsciiStream(findColumn(columnName));
    }

    public InputStream getUnicodeStream(String columnName) throws SQLException {
        return getUnicodeStream(findColumn(columnName));
    }

    public InputStream getBinaryStream(String columnName) throws SQLException {
        return getBinaryStream(findColumn(columnName));
    }

    public Reader getCharacterStream(String columnName) throws SQLException {
        return getCharacterStream(findColumn(columnName));
    }

    public BigDecimal getBigDecimal(String columnName) throws SQLException {
        return getBigDecimal(findColumn(columnName));
    }

    public boolean isBeforeFirst() throws SQLException {
        throw unsupported();
    }

    public boolean isAfterLast() throws SQLException {
        throw unsupported();
    }
 
    public boolean isFirst() throws SQLException {
        throw unsupported();
    }
 
    public boolean isLast() throws SQLException {
        // FIXME: call isLast on active row (if any)
        throw unsupported();
    }

    public void beforeFirst() throws SQLException {
        throw unsupported();
    }

    public void afterLast() throws SQLException {
        throw unsupported();
    }

    public boolean first() throws SQLException {
        throw unsupported();
    }

    public boolean last() throws SQLException {
        throw unsupported();
    }

    public int getRow() throws SQLException {
        throw unsupported();
    }

    public boolean absolute( int row ) throws SQLException {
        throw unsupported();
    }

    public boolean relative( int rows ) throws SQLException {
        throw unsupported();
    }

    public boolean previous() throws SQLException {
        throw unsupported();
    }

    public void setFetchDirection(int direction) throws SQLException {
        if (direction == FETCH_REVERSE) {
            throw unsupported();
        }
    }

    public int getFetchDirection() throws SQLException {
        return FETCH_FORWARD;
    }

    public void setFetchSize(int rows) throws SQLException {
    }

    public int getFetchSize() throws SQLException {
        return 0;
    }

    public int getType() throws SQLException {
        return TYPE_FORWARD_ONLY;
    }

    public int getConcurrency() throws SQLException {
        return CONCUR_READ_ONLY;
    }

    public Object getObject(String columnName, Map<String,Class<?>> map)
        throws SQLException
    {
        return getObject(findColumn(columnName), map);
    }

    public Ref getRef(String columnName) throws SQLException {
        return getRef(findColumn(columnName));
    }

    public Blob getBlob(String columnName) throws SQLException {
        return getBlob(findColumn(columnName));
    }

    public Clob getClob(String columnName) throws SQLException {
        return getClob(findColumn(columnName));
    }

    public Array getArray(String columnName) throws SQLException {
        return getArray(findColumn(columnName));
    }

    public java.sql.Date getDate(String columnName, Calendar cal) throws SQLException {
        return getDate(findColumn(columnName), cal);
    }

    public java.sql.Time getTime(String columnName, Calendar cal) throws SQLException {
        return getTime(findColumn(columnName), cal);
    }

    public java.sql.Timestamp getTimestamp(String columnName, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnName), cal);
    }

    public java.net.URL getURL(String columnName) throws SQLException {
        return getURL(findColumn(columnName));
    }

    public RowId getRowId(String columnName) throws SQLException {
        return getRowId(findColumn(columnName));
    }

    public NClob getNClob(String columnName) throws SQLException {
        return getNClob(findColumn(columnName));
    }

    public SQLXML getSQLXML(String columnName) throws SQLException {
        return getSQLXML(findColumn(columnName));
    }

    public String getNString(String columnName) throws SQLException {
        return getNString(findColumn(columnName));
    }

    public Reader getNCharacterStream(String columnName) throws SQLException {
        return getNCharacterStream(findColumn(columnName));
    }

    public int getHoldability() throws SQLException {
        throw new SQLException("FIXME (getHoldability)");
    }

    public boolean rowUpdated() throws SQLException {
        throw unsupported();
    }

    public boolean rowInserted() throws SQLException {
        throw unsupported();
    }
   
    public boolean rowDeleted() throws SQLException {
        throw unsupported();
    }

    public void insertRow() throws SQLException {
        throw unsupported();
    }

    public void updateRow() throws SQLException {
        throw unsupported();
    }

    public void deleteRow() throws SQLException {
        throw unsupported();
    }

    public void refreshRow() throws SQLException {
        throw unsupported();
    }

    public void cancelRowUpdates() throws SQLException {
        throw unsupported();
    }

    public void moveToInsertRow() throws SQLException {
        throw unsupported();
    }

    public void moveToCurrentRow() throws SQLException {
        throw unsupported();
    }

    public void updateNull(int columnIndex) throws SQLException {
        throw unsupported();
    }  

    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw unsupported();
    }

    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw unsupported();
    }

    public void updateShort(int columnIndex, short x) throws SQLException {
        throw unsupported();
    }

    public void updateInt(int columnIndex, int x) throws SQLException {
        throw unsupported();
    }

    public void updateLong(int columnIndex, long x) throws SQLException {
        throw unsupported();
    }

    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw unsupported();
    }

    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw unsupported();
    }

    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw unsupported();
    }

    public void updateString(int columnIndex, String x) throws SQLException {
        throw unsupported();
    }

    public void updateBytes(int columnIndex, byte x[]) throws SQLException {
        throw unsupported();
    }

    public void updateDate(int columnIndex, java.sql.Date x) throws SQLException {
        throw unsupported();
    }

    public void updateTime(int columnIndex, java.sql.Time x) throws SQLException {
        throw unsupported();
    }

    public void updateTimestamp(int columnIndex, java.sql.Timestamp x) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(int columnIndex, 
                                  InputStream x, 
                                  int length) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(int columnIndex, 
                                  InputStream x, 
                                  long length) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(int columnIndex, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(String columnName, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(int columnIndex, 
                                   InputStream x,
                                   int length) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(int columnIndex, 
                                   InputStream x,
                                   long length) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(int columnIndex, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(String columnName, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(String columnName, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(int columnIndex, Reader reader, int length)
        throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(int columnIndex, Reader reader, long length)
        throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(String columnName, Reader reader, int length)
        throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(String columnName, Reader reader, long length)
        throws SQLException {
        throw unsupported();
    }

    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw unsupported();
    }

    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw unsupported();
    }

    public void updateNull(String columnName) throws SQLException {
        throw unsupported();
    }  

    public void updateBoolean(String columnName, boolean x) throws SQLException {
        throw unsupported();
    }

    public void updateByte(String columnName, byte x) throws SQLException {
        throw unsupported();
    }

    public void updateShort(String columnName, short x) throws SQLException {
        throw unsupported();
    }

    public void updateInt(String columnName, int x) throws SQLException {
        throw unsupported();
    }

    public void updateLong(String columnName, long x) throws SQLException {
        throw unsupported();
    }

    public void updateFloat(String columnName, float x) throws SQLException {
        throw unsupported();
    }

    public void updateDouble(String columnName, double x) throws SQLException {
        throw unsupported();
    }

    public void updateBigDecimal(String columnName, BigDecimal x) throws SQLException {
        throw unsupported();
    }

    public void updateString(String columnName, String x) throws SQLException {
        throw unsupported();
    }

    public void updateBytes(String columnName, byte x[]) throws SQLException {
        throw unsupported();
    }

    public void updateDate(String columnName, java.sql.Date x) throws SQLException {
        throw unsupported();
    }

    public void updateTime(String columnName, java.sql.Time x) throws SQLException {
        throw unsupported();
    }

    public void updateTimestamp(String columnName, java.sql.Timestamp x) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(String columnName, 
                                  InputStream x, 
                                  int length) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(String columnName, 
                                  InputStream x, 
                                  long length) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(String columnName, 
                                   InputStream x,
                                   int length) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(String columnName, 
                                   InputStream x,
                                   long length) throws SQLException {
        throw unsupported();
    }

    public void updateObject(String columnName, Object x, int scaleOrLength) throws SQLException {
        throw unsupported();
    }

    public void updateObject(String columnName, Object x) throws SQLException {
        throw unsupported();
    }

    public void updateRef(int columnIndex, java.sql.Ref x) throws SQLException {
        throw unsupported();
    }
    
    public void updateRef(String columnName, java.sql.Ref x) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(int columnIndex, java.sql.Blob x) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(String columnName, java.sql.Blob x) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(int columnIndex, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(String columnName, InputStream in) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(int columnIndex, InputStream in, long length) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(String columnName, InputStream in, long length) throws SQLException {
        throw unsupported();
    }

    public void updateClob(int columnIndex, java.sql.Clob x) throws SQLException {
        throw unsupported();
    }

    public void updateClob(String columnName, java.sql.Clob x) throws SQLException {
        throw unsupported();
    }

    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateClob(String columnName, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void updateClob(String columnName, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void updateArray(int columnIndex, java.sql.Array x) throws SQLException {
        throw unsupported();
    }

    public void updateArray(String columnName, java.sql.Array x) throws SQLException {
        throw unsupported();
    }
    
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw unsupported();
    }

    public void updateRowId(String columnName, RowId x) throws SQLException {
        throw unsupported();
    }

    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw unsupported();
    }

    public void updateNString(String columnName, String nString) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(String columnName, NClob nClob) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(String columnName, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void updateNClob(String columnName, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw unsupported();
    }

    public void updateSQLXML(String columnName, SQLXML xmlObject) throws SQLException {
        throw unsupported();
    }

    public void updateNCharacterStream(int columnIndex,
                                       Reader x,
                                       long length) throws SQLException {
        throw unsupported();
    }
    
    public void updateNCharacterStream(String columnName,
                                       Reader reader,
                                       long length) throws SQLException {
        throw unsupported();
    }

    public void updateNCharacterStream(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    public void updateNCharacterStream(String columnName, Reader reader) throws SQLException {
        throw unsupported();
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw unsupported();
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    private void silentClose() {
        try {
            close();
        } catch (SQLException e2) {
            // Ignore
        }
    }

    private static SQLException unsupported() throws SQLException {
        return ClientDriver.unsupported();
    }

    private static SQLException unsupportedType(String typeName, Object obj) {
        String objType = obj == null ? null : obj.getClass().getName();

        String message = "Cannot convert " + obj + " (a " + objType + ") to type " + typeName;
        return new SQLException(message);
    }
}
