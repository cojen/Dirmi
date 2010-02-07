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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

import org.cojen.dirmi.util.Wrapper;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public abstract class ClientDatabaseMetaData implements DatabaseMetaData {
    private static final Wrapper<ClientDatabaseMetaData, RemoteDatabaseMetaData> wrapper =
        Wrapper.from(ClientDatabaseMetaData.class, RemoteDatabaseMetaData.class);

    public static ClientDatabaseMetaData from(RemoteDatabaseMetaData md) {
        return wrapper.wrap(md);
    }

    private final RemoteDatabaseMetaData mMetaData;

    protected ClientDatabaseMetaData(RemoteDatabaseMetaData md) {
        mMetaData = md;
    }

    public int getDriverMajorVersion() {
        // FIXME
        return 0;
    }

    public int getDriverMinorVersion() {
        // FIXME
        return 0;
    }

    public ResultSet getProcedures(String catalog, String schemaPattern,
                                   String procedureNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getProcedures(catalog, schemaPattern, procedureNamePattern));
    }

    public ResultSet getProcedureColumns(String catalog,
                                         String schemaPattern,
                                         String procedureNamePattern, 
                                         String columnNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getProcedureColumns(catalog, schemaPattern,
                                           procedureNamePattern, columnNamePattern));
    }

    public ResultSet getTables(String catalog, String schemaPattern,
                               String tableNamePattern, String types[])
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getTables(catalog, schemaPattern, tableNamePattern, types));
    }

    public ResultSet getSchemas() throws SQLException {
        return new ClientResultSet(mMetaData.getSchemas());
    }

    public ResultSet getCatalogs() throws SQLException {
        return new ClientResultSet(mMetaData.getCatalogs());
    }

    public ResultSet getTableTypes() throws SQLException {
        return new ClientResultSet(mMetaData.getTableTypes());
    }

    public ResultSet getColumns(String catalog, String schemaPattern,
                                String tableNamePattern, String columnNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern));
    }

    public ResultSet getColumnPrivileges(String catalog, String schema,
                                         String table, String columnNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getColumnPrivileges(catalog, schema, table, columnNamePattern));
    }

    public ResultSet getTablePrivileges(String catalog, String schemaPattern,
                                        String tableNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getTablePrivileges(catalog, schemaPattern, tableNamePattern));
    }

    public ResultSet getBestRowIdentifier(String catalog, String schema,
                                          String table, int scope, boolean nullable)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getBestRowIdentifier(catalog, schema, table, scope, nullable));
    }
        
    public ResultSet getVersionColumns(String catalog, String schema,
                                       String table)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getVersionColumns(catalog, schema, table));
    }
        
    public ResultSet getPrimaryKeys(String catalog, String schema,
                                    String table)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getPrimaryKeys(catalog, schema, table));
    }

    public ResultSet getImportedKeys(String catalog, String schema,
                                     String table)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getImportedKeys(catalog, schema, table));
    }

    public ResultSet getExportedKeys(String catalog, String schema,
                                     String table)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getExportedKeys(catalog, schema, table));
    }

    public ResultSet getCrossReference(String parentCatalog, String parentSchema,
                                       String parentTable,
                                       String foreignCatalog, String foreignSchema,
                                       String foreignTable)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getCrossReference(parentCatalog, parentSchema, parentTable,
                                         foreignCatalog, foreignSchema, foreignTable));
    }

    public ResultSet getTypeInfo() throws SQLException {
        return new ClientResultSet(mMetaData.getTypeInfo());
    }
        
    public ResultSet getIndexInfo(String catalog, String schema, String table,
                                  boolean unique, boolean approximate)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getIndexInfo(catalog, schema, table, unique, approximate));
    }

    public ResultSet getUDTs(String catalog, String schemaPattern, 
                             String typeNamePattern, int[] types) 
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getUDTs(catalog, schemaPattern, typeNamePattern, types));
    }

    public Connection getConnection() throws SQLException {
        throw new SQLException("FIXME (getConnection)");
    }

    public ResultSet getSuperTypes(String catalog, String schemaPattern, 
                                   String typeNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getSuperTypes(catalog, schemaPattern, typeNamePattern));
    }
    
    public ResultSet getSuperTables(String catalog, String schemaPattern,
                                    String tableNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getSuperTables(catalog, schemaPattern, tableNamePattern));
    }

    public ResultSet getAttributes(String catalog, String schemaPattern,
                                   String typeNamePattern, String attributeNamePattern) 
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getAttributes(catalog, schemaPattern, typeNamePattern,
                                     attributeNamePattern));
    }

    public ResultSet getSchemas(String catalog, String schemaPattern)
        throws SQLException
    {
        return new ClientResultSet(mMetaData.getSchemas(catalog, schemaPattern));
    }
    
    public ResultSet getClientInfoProperties() throws SQLException {
        return new ClientResultSet(mMetaData.getClientInfoProperties());
    }
   
    public ResultSet getFunctions(String catalog, String schemaPattern,
                                  String functionNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getFunctions(catalog, schemaPattern, functionNamePattern));
    }

    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
                                        String functionNamePattern,
                                        String columnNamePattern)
        throws SQLException
    {
        return new ClientResultSet
            (mMetaData.getFunctionColumns(catalog, schemaPattern, functionNamePattern,
                                          columnNamePattern));
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
