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

import java.io.Serializable;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable copy of a ResultSetMetaData.
 *
 * @author Brian S O'Neill
 */
public class ResultSetMetaDataCopy implements ResultSetMetaData, Serializable {
    private static final int
        NULLABLE_FLAG = (3 << 0), // requires two bits
        AUTO_INCREMENT_FLAG = (1 << 2),
        CASE_SENSITIVE_FLAG = (1 << 3),
        SEARCHABLE_FLAG = (1 << 4),
        CURRENCY_FLAG = (1 << 5),
        SIGNED_FLAG = (1 << 6),
        READ_ONLY_FLAG = (1 << 7),
        WRITABLE_FLAG = (1 << 8),
        DEFINITELY_WRITABLE_FLAG = (1 << 9);

    private final int mCount;
    private final int[] mFlags;
    private final int[] mDisplaySizes;
    private final String[] mLabels;
    private final String[] mNames;
    private final String[] mSchemaNames;
    private final int[] mPrecisions;
    private final int[] mScales;
    private final String[] mTableNames;
    private final String[] mCatalogNames;
    private final int[] mTypes;
    private final String[] mTypeNames;
    private final String[] mClassNames;

    private Map<String, Integer> mColumnMap;

    public ResultSetMetaDataCopy(ResultSetMetaData md) throws SQLException {
        final int count = md.getColumnCount();
        final int[] flags = new int[count];
        final int[] displaySizes = new int[count];
        final String[] labels = new String[count];
        final String[] names = new String[count];
        final String[] schemaNames = new String[count];
        final int[] precisions = new int[count];
        final int[] scales = new int[count];
        final String[] tableNames = new String[count];
        final String[] catalogNames = new String[count];
        final int[] types = new int[count];
        final String[] typeNames = new String[count];
        final String[] classNames = new String[count];

        for (int i=0; i<count; i++) {
            {
                int flag = md.isNullable(i + 1);
                if (md     .isAutoIncrement(i + 1)) flag |= AUTO_INCREMENT_FLAG;
                if (md     .isCaseSensitive(i + 1)) flag |= CASE_SENSITIVE_FLAG;
                if (md        .isSearchable(i + 1)) flag |= SEARCHABLE_FLAG;
                if (md          .isCurrency(i + 1)) flag |= CURRENCY_FLAG;
                if (md            .isSigned(i + 1)) flag |= SIGNED_FLAG;
                if (md          .isReadOnly(i + 1)) flag |= READ_ONLY_FLAG;
                if (md          .isWritable(i + 1)) flag |= WRITABLE_FLAG;
                if (md.isDefinitelyWritable(i + 1)) flag |= DEFINITELY_WRITABLE_FLAG;
                flags[i] = flag;
            }

            displaySizes[i] = md.getColumnDisplaySize(i + 1);
            labels[i] = md.getColumnLabel(i + 1);
            names[i] = md.getColumnName(i + 1);
            schemaNames[i] = md.getSchemaName(i + 1);
            try {
                precisions[i] = md.getPrecision(i + 1);
            } catch (NumberFormatException e) {
                // Workaround Oracle driver bug.
            }
            scales[i] = md.getScale(i + 1);
            tableNames[i] = md.getTableName(i + 1);
            catalogNames[i] = md.getCatalogName(i + 1);
            types[i] = md.getColumnType(i + 1);
            typeNames[i] = md.getColumnTypeName(i + 1);
            classNames[i] = md.getColumnClassName(i + 1);
        }

        mCount = count;
        mFlags = flags;
        mDisplaySizes = displaySizes;
        mLabels = labels;
        mNames = names;
        mSchemaNames = schemaNames;
        mPrecisions = precisions;
        mScales = scales;
        mTableNames = tableNames;
        mCatalogNames = catalogNames;
        mTypes = types;
        mTypeNames = typeNames;
        mClassNames = classNames;
    }

    public int findColumn(String columnName) throws SQLException {
        Map<String, Integer> columnMap = mColumnMap;
        if (columnMap == null) {
            columnMap = new LinkedHashMap<String, Integer>();
            String[] names = mNames;
            for (int i=0; i<names.length; i++) {
                columnMap.put(names[i], i + 1);
            }
            mColumnMap = columnMap;
        }

        Integer column = columnMap.get(columnName);
        if (column == null) {
            throw new SQLException
                ("Column \"" + columnName + "\" not in result set: " + columnMap.keySet());
        }

        return column;
    }

    public int getColumnCount() {
        return mCount;
    }

    public int isNullable(int column) {
        return mFlags[column - 1] & NULLABLE_FLAG;
    }

    public boolean isAutoIncrement(int column) {
        return (mFlags[column - 1] & AUTO_INCREMENT_FLAG) != 0;
    }

    public boolean isCaseSensitive(int column) {
        return (mFlags[column - 1] & CASE_SENSITIVE_FLAG) != 0;
    }

    public boolean isSearchable(int column) {
        return (mFlags[column - 1] & SEARCHABLE_FLAG) != 0;
    }

    public boolean isCurrency(int column) {
        return (mFlags[column - 1] & CURRENCY_FLAG) != 0;
    }

    public boolean isSigned(int column) {
        return (mFlags[column - 1] & SIGNED_FLAG) != 0;
    }

    public boolean isReadOnly(int column) {
        return (mFlags[column - 1] & READ_ONLY_FLAG) != 0;
    }

    public boolean isWritable(int column) {
        return (mFlags[column - 1] & WRITABLE_FLAG) != 0;
    }

    public boolean isDefinitelyWritable(int column) {
        return (mFlags[column - 1] & DEFINITELY_WRITABLE_FLAG) != 0;
    }

    public int getColumnDisplaySize(int column) {
        return mDisplaySizes[column - 1];
    }

    public String getColumnLabel(int column) {
        return mLabels[column - 1];
    }

    public String getColumnName(int column) {
        return mNames[column - 1];
    }

    public String getSchemaName(int column) {
        return mSchemaNames[column - 1];
    }

    public int getPrecision(int column) {
        return mPrecisions[column - 1];
    }

    public int getScale(int column) {
        return mScales[column - 1];
    }

    public String getTableName(int column) {
        return mTableNames[column - 1];
    }

    public String getCatalogName(int column) {
        return mCatalogNames[column - 1];
    }

    public int getColumnType(int column) {
        return mTypes[column - 1];
    }

    public String getColumnTypeName(int column) {
        return mTypeNames[column - 1];
    }

    public String getColumnClassName(int column) {
        return mClassNames[column - 1];
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("unsupported");
    }

    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
