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

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RemoteDatabaseMetaDataServer implements RemoteDatabaseMetaData {
    private final DatabaseMetaData mMetaData;

    public RemoteDatabaseMetaDataServer(DatabaseMetaData md) {
        mMetaData = md;
    }

    public boolean allProceduresAreCallable() throws SQLException {
        return mMetaData.allProceduresAreCallable();
    }

    public boolean allTablesAreSelectable() throws SQLException {
        return mMetaData.allTablesAreSelectable();
    }

    public String getURL() throws SQLException {
        return mMetaData.getURL();
    }

    public String getUserName() throws SQLException {
        return mMetaData.getUserName();
    }

    public boolean isReadOnly() throws SQLException {
        return mMetaData.isReadOnly();
    }

    public boolean nullsAreSortedHigh() throws SQLException {
        return mMetaData.nullsAreSortedHigh();
    }

    public boolean nullsAreSortedLow() throws SQLException {
        return mMetaData.nullsAreSortedLow();
    }

    public boolean nullsAreSortedAtStart() throws SQLException {
        return mMetaData.nullsAreSortedAtStart();
    }

    public boolean nullsAreSortedAtEnd() throws SQLException {
        return mMetaData.nullsAreSortedAtEnd();
    }

    public String getDatabaseProductName() throws SQLException {
        return mMetaData.getDatabaseProductName();
    }

    public String getDatabaseProductVersion() throws SQLException {
        return mMetaData.getDatabaseProductVersion();
    }

    public String getDriverName() throws SQLException {
        return mMetaData.getDriverName();
    }

    public String getDriverVersion() throws SQLException {
        return mMetaData.getDriverVersion();
    }

    public boolean usesLocalFiles() throws SQLException {
        return mMetaData.usesLocalFiles();
    }

    public boolean usesLocalFilePerTable() throws SQLException {
        return mMetaData.usesLocalFilePerTable();
    }

    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return mMetaData.supportsMixedCaseIdentifiers();
    }

    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return mMetaData.storesUpperCaseIdentifiers();
    }

    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return mMetaData.storesLowerCaseIdentifiers();
    }

    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return mMetaData.storesMixedCaseIdentifiers();
    }

    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return mMetaData.supportsMixedCaseQuotedIdentifiers();
    }

    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return mMetaData.storesUpperCaseQuotedIdentifiers();
    }

    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return mMetaData.storesLowerCaseQuotedIdentifiers();
    }

    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return mMetaData.storesMixedCaseQuotedIdentifiers();
    }

    public String getIdentifierQuoteString() throws SQLException {
        return mMetaData.getIdentifierQuoteString();
    }

    public String getSQLKeywords() throws SQLException {
        return mMetaData.getSQLKeywords();
    }

    public String getNumericFunctions() throws SQLException {
        return mMetaData.getNumericFunctions();
    }

    public String getStringFunctions() throws SQLException {
        return mMetaData.getStringFunctions();
    }

    public String getSystemFunctions() throws SQLException {
        return mMetaData.getSystemFunctions();
    }

    public String getTimeDateFunctions() throws SQLException {
        return mMetaData.getTimeDateFunctions();
    }

    public String getSearchStringEscape() throws SQLException {
        return mMetaData.getSearchStringEscape();
    }

    public String getExtraNameCharacters() throws SQLException {
        return mMetaData.getExtraNameCharacters();
    }

    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return mMetaData.supportsAlterTableWithAddColumn();
    }

    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return mMetaData.supportsAlterTableWithDropColumn();
    }

    public boolean supportsColumnAliasing() throws SQLException {
        return mMetaData.supportsColumnAliasing();
    }

    public boolean nullPlusNonNullIsNull() throws SQLException {
        return mMetaData.nullPlusNonNullIsNull();
    }

    public boolean supportsConvert() throws SQLException {
        return mMetaData.supportsConvert();
    }

    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return mMetaData.supportsConvert();
    }

    public boolean supportsTableCorrelationNames() throws SQLException {
        return mMetaData.supportsTableCorrelationNames();
    }

    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return mMetaData.supportsDifferentTableCorrelationNames();
    }

    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return mMetaData.supportsExpressionsInOrderBy();
    }

    public boolean supportsOrderByUnrelated() throws SQLException {
        return mMetaData.supportsOrderByUnrelated();
    }

    public boolean supportsGroupBy() throws SQLException {
        return mMetaData.supportsGroupBy();
    }

    public boolean supportsGroupByUnrelated() throws SQLException {
        return mMetaData.supportsGroupByUnrelated();
    }

    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return mMetaData.supportsGroupByBeyondSelect();
    }

    public boolean supportsLikeEscapeClause() throws SQLException {
        return mMetaData.supportsLikeEscapeClause();
    }

    public boolean supportsMultipleResultSets() throws SQLException {
        return mMetaData.supportsMultipleResultSets();
    }

    public boolean supportsMultipleTransactions() throws SQLException {
        return mMetaData.supportsMultipleTransactions();
    }

    public boolean supportsNonNullableColumns() throws SQLException {
        return mMetaData.supportsNonNullableColumns();
    }

    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return mMetaData.supportsMinimumSQLGrammar();
    }

    public boolean supportsCoreSQLGrammar() throws SQLException {
        return mMetaData.supportsCoreSQLGrammar();
    }

    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return mMetaData.supportsExtendedSQLGrammar();
    }

    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return mMetaData.supportsANSI92EntryLevelSQL();
    }

    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return mMetaData.supportsANSI92IntermediateSQL();
    }

    public boolean supportsANSI92FullSQL() throws SQLException {
        return mMetaData.supportsANSI92FullSQL();
    }

    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return mMetaData.supportsIntegrityEnhancementFacility();
    }

    public boolean supportsOuterJoins() throws SQLException {
        return mMetaData.supportsOuterJoins();
    }

    public boolean supportsFullOuterJoins() throws SQLException {
        return mMetaData.supportsFullOuterJoins();
    }

    public boolean supportsLimitedOuterJoins() throws SQLException {
        return mMetaData.supportsLimitedOuterJoins();
    }

    public String getSchemaTerm() throws SQLException {
        return mMetaData.getSchemaTerm();
    }

    public String getProcedureTerm() throws SQLException {
        return mMetaData.getProcedureTerm();
    }

    public String getCatalogTerm() throws SQLException {
        return mMetaData.getCatalogTerm();
    }

    public boolean isCatalogAtStart() throws SQLException {
        return mMetaData.isCatalogAtStart();
    }

    public String getCatalogSeparator() throws SQLException {
        return mMetaData.getCatalogSeparator();
    }

    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return mMetaData.supportsSchemasInDataManipulation();
    }

    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return mMetaData.supportsSchemasInProcedureCalls();
    }

    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return mMetaData.supportsSchemasInTableDefinitions();
    }

    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return mMetaData.supportsSchemasInIndexDefinitions();
    }

    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return mMetaData.supportsSchemasInPrivilegeDefinitions();
    }

    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return mMetaData.supportsCatalogsInDataManipulation();
    }

    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return mMetaData.supportsCatalogsInProcedureCalls();
    }

    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return mMetaData.supportsCatalogsInTableDefinitions();
    }

    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return mMetaData.supportsCatalogsInIndexDefinitions();
    }

    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return mMetaData.supportsCatalogsInPrivilegeDefinitions();
    }

    public boolean supportsPositionedDelete() throws SQLException {
        return mMetaData.supportsPositionedDelete();
    }

    public boolean supportsPositionedUpdate() throws SQLException {
        return mMetaData.supportsPositionedUpdate();
    }

    public boolean supportsSelectForUpdate() throws SQLException {
        return mMetaData.supportsSelectForUpdate();
    }

    public boolean supportsStoredProcedures() throws SQLException {
        return mMetaData.supportsStoredProcedures();
    }

    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return mMetaData.supportsSubqueriesInComparisons();
    }

    public boolean supportsSubqueriesInExists() throws SQLException {
        return mMetaData.supportsSubqueriesInExists();
    }

    public boolean supportsSubqueriesInIns() throws SQLException {
        return mMetaData.supportsSubqueriesInIns();
    }

    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return mMetaData.supportsSubqueriesInQuantifieds();
    }

    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return mMetaData.supportsCorrelatedSubqueries();
    }

    public boolean supportsUnion() throws SQLException {
        return mMetaData.supportsUnion();
    }

    public boolean supportsUnionAll() throws SQLException {
        return mMetaData.supportsUnionAll();
    }

    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return mMetaData.supportsOpenCursorsAcrossCommit();
    }

    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return mMetaData.supportsOpenCursorsAcrossRollback();
    }

    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return mMetaData.supportsOpenStatementsAcrossCommit();
    }

    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return mMetaData.supportsOpenStatementsAcrossRollback();
    }

    public int getMaxBinaryLiteralLength() throws SQLException {
        return mMetaData.getMaxBinaryLiteralLength();
    }

    public int getMaxCharLiteralLength() throws SQLException {
        return mMetaData.getMaxCharLiteralLength();
    }

    public int getMaxColumnNameLength() throws SQLException {
        return mMetaData.getMaxColumnNameLength();
    }

    public int getMaxColumnsInGroupBy() throws SQLException {
        return mMetaData.getMaxColumnsInGroupBy();
    }

    public int getMaxColumnsInIndex() throws SQLException {
        return mMetaData.getMaxColumnsInIndex();
    }

    public int getMaxColumnsInOrderBy() throws SQLException {
        return mMetaData.getMaxColumnsInOrderBy();
    }

    public int getMaxColumnsInSelect() throws SQLException {
        return mMetaData.getMaxColumnsInSelect();
    }

    public int getMaxColumnsInTable() throws SQLException {
        return mMetaData.getMaxColumnsInTable();
    }

    public int getMaxConnections() throws SQLException {
        return mMetaData.getMaxConnections();
    }

    public int getMaxCursorNameLength() throws SQLException {
        return mMetaData.getMaxCursorNameLength();
    }

    public int getMaxIndexLength() throws SQLException {
        return mMetaData.getMaxIndexLength();
    }

    public int getMaxSchemaNameLength() throws SQLException {
        return mMetaData.getMaxSchemaNameLength();
    }

    public int getMaxProcedureNameLength() throws SQLException {
        return mMetaData.getMaxProcedureNameLength();
    }

    public int getMaxCatalogNameLength() throws SQLException {
        return mMetaData.getMaxCatalogNameLength();
    }

    public int getMaxRowSize() throws SQLException {
        return mMetaData.getMaxRowSize();
    }

    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return mMetaData.doesMaxRowSizeIncludeBlobs();
    }

    public int getMaxStatementLength() throws SQLException {
        return mMetaData.getMaxStatementLength();
    }

    public int getMaxStatements() throws SQLException {
        return mMetaData.getMaxStatements();
    }

    public int getMaxTableNameLength() throws SQLException {
        return mMetaData.getMaxTableNameLength();
    }

    public int getMaxTablesInSelect() throws SQLException {
        return mMetaData.getMaxTablesInSelect();
    }

    public int getMaxUserNameLength() throws SQLException {
        return mMetaData.getMaxUserNameLength();
    }

    public int getDefaultTransactionIsolation() throws SQLException {
        return mMetaData.getDefaultTransactionIsolation();
    }

    public boolean supportsTransactions() throws SQLException {
        return mMetaData.supportsTransactions();
    }

    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return mMetaData.supportsTransactionIsolationLevel(level);
    }

    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return mMetaData.supportsDataDefinitionAndDataManipulationTransactions();
    }

    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return mMetaData.supportsDataManipulationTransactionsOnly();
    }

    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return mMetaData.dataDefinitionCausesTransactionCommit();
    }

    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return mMetaData.dataDefinitionIgnoredInTransactions();
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

    public boolean supportsResultSetType(int type) throws SQLException {
        return mMetaData.supportsResultSetType(type);
    }

    public boolean supportsResultSetConcurrency(int type, int concurrency)
        throws SQLException
    {
        return mMetaData.supportsResultSetConcurrency(type, concurrency);
    }

    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return mMetaData.ownUpdatesAreVisible(type);
    }

    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return mMetaData.ownDeletesAreVisible(type);
    }

    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return mMetaData.ownInsertsAreVisible(type);
    }

    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return mMetaData.othersUpdatesAreVisible(type);
    }

    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return mMetaData.othersDeletesAreVisible(type);
    }

    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return mMetaData.othersInsertsAreVisible(type);
    }

    public boolean updatesAreDetected(int type) throws SQLException {
        return mMetaData.updatesAreDetected(type);
    }

    public boolean deletesAreDetected(int type) throws SQLException {
        return mMetaData.deletesAreDetected(type);
    }

    public boolean insertsAreDetected(int type) throws SQLException {
        return mMetaData.insertsAreDetected(type);
    }

    public boolean supportsBatchUpdates() throws SQLException {
        return mMetaData.supportsBatchUpdates();
    }

    public ResultSetTransport getUDTs(String catalog, String schemaPattern, 
                                      String typeNamePattern, int[] types) 
        throws SQLException
    {
        return new ResultSetTransport
            (mMetaData.getUDTs(catalog, schemaPattern, typeNamePattern, types));
    }

    public boolean supportsSavepoints() throws SQLException {
        return mMetaData.supportsSavepoints();
    }

    public boolean supportsNamedParameters() throws SQLException {
        return mMetaData.supportsNamedParameters();
    }

    public boolean supportsMultipleOpenResults() throws SQLException {
        return mMetaData.supportsMultipleOpenResults();
    }

    public boolean supportsGetGeneratedKeys() throws SQLException {
        return mMetaData.supportsGetGeneratedKeys();
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

    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return mMetaData.supportsResultSetHoldability(holdability);
    }

    public int getResultSetHoldability() throws SQLException {
        try {
            return mMetaData.getResultSetHoldability();
        } catch (SQLException e) {
            // Driver workaround. Assume safe answer.
            return ResultSet.CLOSE_CURSORS_AT_COMMIT;
        }
    }

    public int getDatabaseMajorVersion() throws SQLException {
        return mMetaData.getDatabaseMajorVersion();
    }

    public int getDatabaseMinorVersion() throws SQLException {
        return mMetaData.getDatabaseMinorVersion();
    }

    public int getJDBCMajorVersion() throws SQLException {
        return mMetaData.getJDBCMajorVersion();
    }

    public int getJDBCMinorVersion() throws SQLException {
        return mMetaData.getJDBCMinorVersion();
    }

    public int getSQLStateType() throws SQLException {
        return mMetaData.getSQLStateType();
    }

    public boolean locatorsUpdateCopy() throws SQLException {
        return mMetaData.locatorsUpdateCopy();
    }

    public boolean supportsStatementPooling() throws SQLException {
        return mMetaData.supportsStatementPooling();
    }
    
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return mMetaData.getRowIdLifetime();
    }

    public ResultSetTransport getSchemas(String catalog, String schemaPattern)
        throws SQLException
    {
        return new ResultSetTransport(mMetaData.getSchemas(catalog, schemaPattern));
    }
    
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return mMetaData.supportsStoredFunctionsUsingCallSyntax();
    }
     
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return mMetaData.autoCommitFailureClosesAllResultSets();
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
