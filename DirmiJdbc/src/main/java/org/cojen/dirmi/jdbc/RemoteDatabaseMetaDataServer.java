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

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class RemoteDatabaseMetaDataServer implements RemoteDatabaseMetaData {
    private static final Wrapper<RemoteDatabaseMetaDataServer, DatabaseMetaData> wrapper =
        Wrapper.from(RemoteDatabaseMetaDataServer.class, DatabaseMetaData.class);

    public static RemoteDatabaseMetaData from(DatabaseMetaData md) {
        return wrapper.wrap(md);
    }

    private final DatabaseMetaData mMetaData;

    protected RemoteDatabaseMetaDataServer(DatabaseMetaData md) {
        mMetaData = md;
    }

    public ResultSetTransport getProcedures(String catalog, String schemaPattern,
                                            String procedureNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getProcedures(catalog, schemaPattern, procedureNamePattern));
    }

    public ResultSetTransport getProcedureColumns(String catalog,
                                                  String schemaPattern,
                                                  String procedureNamePattern, 
                                                  String columnNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getProcedureColumns(catalog, schemaPattern,
                                           procedureNamePattern, columnNamePattern));
    }

    public ResultSetTransport getTables(String catalog, String schemaPattern,
                                        String tableNamePattern, String types[])
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getTables(catalog, schemaPattern, tableNamePattern, types));
    }

    public ResultSetTransport getSchemas() throws SQLException {
        return new ResultSetTransport(mMetaData.getSchemas());
    }

    public ResultSetTransport getCatalogs() throws SQLException {
        return new ResultSetTransport(mMetaData.getCatalogs());
    }

    public ResultSetTransport getTableTypes() throws SQLException {
        return new ResultSetTransport(mMetaData.getTableTypes());
    }

    public ResultSetTransport getColumns(String catalog, String schemaPattern,
                                         String tableNamePattern, String columnNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern));
    }

    public ResultSetTransport getColumnPrivileges(String catalog, String schema,
                                                  String table, String columnNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getColumnPrivileges(catalog, schema, table, columnNamePattern));
    }

    public ResultSetTransport getTablePrivileges(String catalog, String schemaPattern,
                                                 String tableNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getTablePrivileges(catalog, schemaPattern, tableNamePattern));
    }

    public ResultSetTransport getBestRowIdentifier(String catalog, String schema,
                                                   String table, int scope, boolean nullable)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getBestRowIdentifier(catalog, schema, table, scope, nullable));
    }
        
    public ResultSetTransport getVersionColumns(String catalog, String schema,
                                                String table)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getVersionColumns(catalog, schema, table));
    }
        
    public ResultSetTransport getPrimaryKeys(String catalog, String schema,
                                             String table)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getPrimaryKeys(catalog, schema, table));
    }

    public ResultSetTransport getImportedKeys(String catalog, String schema,
                                              String table)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getImportedKeys(catalog, schema, table));
    }

    public ResultSetTransport getExportedKeys(String catalog, String schema,
                                              String table)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getExportedKeys(catalog, schema, table));
    }

    public ResultSetTransport getCrossReference(String parentCatalog, String parentSchema,
                                                String parentTable,
                                                String foreignCatalog, String foreignSchema,
                                                String foreignTable)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getCrossReference(parentCatalog, parentSchema, parentTable,
                                         foreignCatalog, foreignSchema, foreignTable));
    }

    public ResultSetTransport getTypeInfo() throws SQLException {
        return new ResultSetTransport(mMetaData.getTypeInfo());
    }
        
    public ResultSetTransport getIndexInfo(String catalog, String schema, String table,
                                           boolean unique, boolean approximate)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getIndexInfo(catalog, schema, table, unique, approximate));
    }

    public ResultSetTransport getUDTs(String catalog, String schemaPattern, 
                                      String typeNamePattern, int[] types) 
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getUDTs(catalog, schemaPattern, typeNamePattern, types));
    }

    public ResultSetTransport getSuperTypes(String catalog, String schemaPattern, 
                                            String typeNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getSuperTypes(catalog, schemaPattern, typeNamePattern));
    }
    
    public ResultSetTransport getSuperTables(String catalog, String schemaPattern,
                                             String tableNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getSuperTables(catalog, schemaPattern, tableNamePattern));
    }

    public ResultSetTransport getAttributes(String catalog, String schemaPattern,
                                            String typeNamePattern, String attributeNamePattern) 
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getAttributes(catalog, schemaPattern, typeNamePattern,
                                     attributeNamePattern));
    }

    public int getResultSetHoldability() throws SQLException {
        try {
            return mMetaData.getResultSetHoldability();
        } catch (SQLException e) {
            // Driver workaround. Assume safe answer.
            return ResultSet.CLOSE_CURSORS_AT_COMMIT;
        }
    }

    public ResultSetTransport getSchemas(String catalog, String schemaPattern)
        throws SQLException
    {
        return new ResultSetTransport(mMetaData.getSchemas(catalog, schemaPattern));
    }
    
    public ResultSetTransport getClientInfoProperties() throws SQLException {
        return new ResultSetTransport(mMetaData.getClientInfoProperties());
    }
   
    public ResultSetTransport getFunctions(String catalog, String schemaPattern,
                                           String functionNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getFunctions(catalog, schemaPattern, functionNamePattern));
    }

    public ResultSetTransport getFunctionColumns(String catalog, String schemaPattern,
                                                 String functionNamePattern,
                                                 String columnNamePattern)
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getFunctionColumns(catalog, schemaPattern, functionNamePattern,
                                          columnNamePattern));
    }
}
