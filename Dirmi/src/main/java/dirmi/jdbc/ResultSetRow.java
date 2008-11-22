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

import java.io.Serializable;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static java.sql.Types.*;

/**
 * Serializable copy of a ResultSet row. Only simple supported types are provided.
 *
 * @author Brian S O'Neill
 */
public class ResultSetRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int[] mMissingColumns;
    private final Object[] mColumnValues;

    public ResultSetRow(ResultSet rs, ResultSetMetaData md) throws SQLException {
        final int columns = md.getColumnCount();

        int[] missingColumns = new int[(columns + 31) >> 5];
        Object[] columnValues = new Object[columns];

        for (int i=0; i<columns; i++) {
            int type = md.getColumnType(i + 1);
            String typeName = md.getColumnTypeName(i + 1);
            type = correctType(type, typeName);
            if (isSimpleSupportedType(type)) {
                columnValues[i] = rs.getObject(i + 1);
            } else if (type == BLOB) {
                Blob blob = rs.getBlob(i + 1);
                // FIXME: convert to RemoteBlobServer
                columnValues[i] = blob == null ? null : blob;
            } else if (type == CLOB) {
                Clob clob = rs.getClob(i + 1);
                // FIXME: convert to RemoteClobServer
                columnValues[i] = clob == null ? null : clob;
            } else {
                missingColumns[i >> 5] |= (1 << (i & 0x1f));
            }
        }

        mMissingColumns = missingColumns;
        mColumnValues = columnValues;
    }

    private static int correctType(int type, String typeName) {
        switch (type) {
        case OTHER:
            if ("BLOB".equalsIgnoreCase(typeName)) {
                type = BLOB;
            } else if ("CLOB".equalsIgnoreCase(typeName)) {
                type = CLOB;
            } else if ("FLOAT".equalsIgnoreCase(typeName)) {
                type = FLOAT;
            } else if ("TIMESTAMP".equalsIgnoreCase(typeName)) {
                type = TIMESTAMP;
            } else if (typeName.toUpperCase().contains("TIMESTAMP")) {
                type = TIMESTAMP;
            }
            break;
        case LONGVARBINARY:
            if ("BLOB".equalsIgnoreCase(typeName)) {
                type = BLOB;
            }
            break;
        case LONGVARCHAR:
            if ("CLOB".equalsIgnoreCase(typeName)) {
                type = CLOB;
            }
            break;
        }

        return type;
    }

    private static boolean isSimpleSupportedType(int type) {
        switch (type) {
        case BIT: case TINYINT: case SMALLINT: case INTEGER: case BIGINT:
        case FLOAT: case REAL: case DOUBLE: case NUMERIC: case DECIMAL:
        case VARCHAR: case LONGVARCHAR: case DATE: case TIME: case TIMESTAMP:
        case BINARY: case VARBINARY: case LONGVARBINARY: case NULL:
        case BOOLEAN: case NCHAR: case NVARCHAR: case LONGNVARCHAR: case CHAR:
            return true;
        }

        return false;
    }

    public boolean areAllProvided() {
        for (int bits : mMissingColumns) {
            if (bits != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isProvided(int columnIndex) {
        columnIndex--;
        return (mMissingColumns[columnIndex >> 5] & (1 << columnIndex & 0x1f)) == 0;
    }

    public Object getValue(int columnIndex) throws SQLException {
        Object obj = mColumnValues[columnIndex - 1];
        if (obj == null && !isProvided(columnIndex)) {
            throw new SQLException("Column at " + columnIndex + " is missing");
        }
        return obj;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(mColumnValues);
    }
}

