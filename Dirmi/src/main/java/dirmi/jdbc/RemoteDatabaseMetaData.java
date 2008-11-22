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

import java.rmi.Remote;

import java.sql.RowIdLifetime;
import java.sql.SQLException;

import dirmi.RemoteFailure;

/**
 * 
 *
 * @author Brian S O'Neill
 */
@RemoteFailure(exception=SQLException.class)
public interface RemoteDatabaseMetaData extends Remote {
    boolean allProceduresAreCallable() throws SQLException;

    boolean allTablesAreSelectable() throws SQLException;

    String getURL() throws SQLException;

    String getUserName() throws SQLException;

    boolean isReadOnly() throws SQLException;

    boolean nullsAreSortedHigh() throws SQLException;

    boolean nullsAreSortedLow() throws SQLException;

    boolean nullsAreSortedAtStart() throws SQLException;

    boolean nullsAreSortedAtEnd() throws SQLException;

    String getDatabaseProductName() throws SQLException;

    String getDatabaseProductVersion() throws SQLException;

    String getDriverName() throws SQLException;

    String getDriverVersion() throws SQLException;

    boolean usesLocalFiles() throws SQLException;

    boolean usesLocalFilePerTable() throws SQLException;

    boolean supportsMixedCaseIdentifiers() throws SQLException;

    boolean storesUpperCaseIdentifiers() throws SQLException;

    boolean storesLowerCaseIdentifiers() throws SQLException;

    boolean storesMixedCaseIdentifiers() throws SQLException;

    boolean supportsMixedCaseQuotedIdentifiers() throws SQLException;

    boolean storesUpperCaseQuotedIdentifiers() throws SQLException;

    boolean storesLowerCaseQuotedIdentifiers() throws SQLException;

    boolean storesMixedCaseQuotedIdentifiers() throws SQLException;

    String getIdentifierQuoteString() throws SQLException;

    String getSQLKeywords() throws SQLException;

    String getNumericFunctions() throws SQLException;

    String getStringFunctions() throws SQLException;

    String getSystemFunctions() throws SQLException;

    String getTimeDateFunctions() throws SQLException;

    String getSearchStringEscape() throws SQLException;

    String getExtraNameCharacters() throws SQLException;

    boolean supportsAlterTableWithAddColumn() throws SQLException;

    boolean supportsAlterTableWithDropColumn() throws SQLException;

    boolean supportsColumnAliasing() throws SQLException;

    boolean nullPlusNonNullIsNull() throws SQLException;

    boolean supportsConvert() throws SQLException;

    boolean supportsConvert(int fromType, int toType) throws SQLException;

    boolean supportsTableCorrelationNames() throws SQLException;

    boolean supportsDifferentTableCorrelationNames() throws SQLException;

    boolean supportsExpressionsInOrderBy() throws SQLException;

    boolean supportsOrderByUnrelated() throws SQLException;

    boolean supportsGroupBy() throws SQLException;

    boolean supportsGroupByUnrelated() throws SQLException;

    boolean supportsGroupByBeyondSelect() throws SQLException;

    boolean supportsLikeEscapeClause() throws SQLException;

    boolean supportsMultipleResultSets() throws SQLException;

    boolean supportsMultipleTransactions() throws SQLException;

    boolean supportsNonNullableColumns() throws SQLException;

    boolean supportsMinimumSQLGrammar() throws SQLException;

    boolean supportsCoreSQLGrammar() throws SQLException;

    boolean supportsExtendedSQLGrammar() throws SQLException;

    boolean supportsANSI92EntryLevelSQL() throws SQLException;

    boolean supportsANSI92IntermediateSQL() throws SQLException;

    boolean supportsANSI92FullSQL() throws SQLException;

    boolean supportsIntegrityEnhancementFacility() throws SQLException;

    boolean supportsOuterJoins() throws SQLException;

    boolean supportsFullOuterJoins() throws SQLException;

    boolean supportsLimitedOuterJoins() throws SQLException;

    String getSchemaTerm() throws SQLException;

    String getProcedureTerm() throws SQLException;

    String getCatalogTerm() throws SQLException;

    boolean isCatalogAtStart() throws SQLException;

    String getCatalogSeparator() throws SQLException;

    boolean supportsSchemasInDataManipulation() throws SQLException;

    boolean supportsSchemasInProcedureCalls() throws SQLException;

    boolean supportsSchemasInTableDefinitions() throws SQLException;

    boolean supportsSchemasInIndexDefinitions() throws SQLException;

    boolean supportsSchemasInPrivilegeDefinitions() throws SQLException;

    boolean supportsCatalogsInDataManipulation() throws SQLException;

    boolean supportsCatalogsInProcedureCalls() throws SQLException;

    boolean supportsCatalogsInTableDefinitions() throws SQLException;

    boolean supportsCatalogsInIndexDefinitions() throws SQLException;

    boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException;

    boolean supportsPositionedDelete() throws SQLException;

    boolean supportsPositionedUpdate() throws SQLException;

    boolean supportsSelectForUpdate() throws SQLException;

    boolean supportsStoredProcedures() throws SQLException;

    boolean supportsSubqueriesInComparisons() throws SQLException;

    boolean supportsSubqueriesInExists() throws SQLException;

    boolean supportsSubqueriesInIns() throws SQLException;

    boolean supportsSubqueriesInQuantifieds() throws SQLException;

    boolean supportsCorrelatedSubqueries() throws SQLException;

    boolean supportsUnion() throws SQLException;

    boolean supportsUnionAll() throws SQLException;

    boolean supportsOpenCursorsAcrossCommit() throws SQLException;

    boolean supportsOpenCursorsAcrossRollback() throws SQLException;

    boolean supportsOpenStatementsAcrossCommit() throws SQLException;

    boolean supportsOpenStatementsAcrossRollback() throws SQLException;

    int getMaxBinaryLiteralLength() throws SQLException;

    int getMaxCharLiteralLength() throws SQLException;

    int getMaxColumnNameLength() throws SQLException;

    int getMaxColumnsInGroupBy() throws SQLException;

    int getMaxColumnsInIndex() throws SQLException;

    int getMaxColumnsInOrderBy() throws SQLException;

    int getMaxColumnsInSelect() throws SQLException;

    int getMaxColumnsInTable() throws SQLException;

    int getMaxConnections() throws SQLException;

    int getMaxCursorNameLength() throws SQLException;

    int getMaxIndexLength() throws SQLException;

    int getMaxSchemaNameLength() throws SQLException;

    int getMaxProcedureNameLength() throws SQLException;

    int getMaxCatalogNameLength() throws SQLException;

    int getMaxRowSize() throws SQLException;

    boolean doesMaxRowSizeIncludeBlobs() throws SQLException;

    int getMaxStatementLength() throws SQLException;

    int getMaxStatements() throws SQLException;

    int getMaxTableNameLength() throws SQLException;

    int getMaxTablesInSelect() throws SQLException;

    int getMaxUserNameLength() throws SQLException;

    int getDefaultTransactionIsolation() throws SQLException;

    boolean supportsTransactions() throws SQLException;

    boolean supportsTransactionIsolationLevel(int level)
        throws SQLException;

    boolean supportsDataDefinitionAndDataManipulationTransactions()
        throws SQLException;

    boolean supportsDataManipulationTransactionsOnly()
        throws SQLException;

    boolean dataDefinitionCausesTransactionCommit()
        throws SQLException;

    boolean dataDefinitionIgnoredInTransactions()
        throws SQLException;

    ResultSetTransport getProcedures(String catalog, String schemaPattern,
                                     String procedureNamePattern) throws SQLException;

    ResultSetTransport getProcedureColumns(String catalog,
                                           String schemaPattern,
                                           String procedureNamePattern, 
                                           String columnNamePattern) throws SQLException;

    ResultSetTransport getTables(String catalog, String schemaPattern,
                                 String tableNamePattern, String types[]) throws SQLException;

    ResultSetTransport getSchemas() throws SQLException;

    ResultSetTransport getCatalogs() throws SQLException;

    ResultSetTransport getTableTypes() throws SQLException;

    ResultSetTransport getColumns(String catalog, String schemaPattern,
                                  String tableNamePattern, String columnNamePattern)
        throws SQLException;

    ResultSetTransport getColumnPrivileges(String catalog, String schema,
                                           String table, String columnNamePattern) throws SQLException;

    ResultSetTransport getTablePrivileges(String catalog, String schemaPattern,
                                          String tableNamePattern) throws SQLException;

    ResultSetTransport getBestRowIdentifier(String catalog, String schema,
                                            String table, int scope, boolean nullable) throws SQLException;
        
    ResultSetTransport getVersionColumns(String catalog, String schema,
                                         String table) throws SQLException;
        
    ResultSetTransport getPrimaryKeys(String catalog, String schema,
                                      String table) throws SQLException;

    ResultSetTransport getImportedKeys(String catalog, String schema,
                                       String table) throws SQLException;

    ResultSetTransport getExportedKeys(String catalog, String schema,
                                       String table) throws SQLException;

    ResultSetTransport getCrossReference(
                                         String parentCatalog, String parentSchema, String parentTable,
                                         String foreignCatalog, String foreignSchema, String foreignTable
                                         ) throws SQLException;

    ResultSetTransport getTypeInfo() throws SQLException;
        
    ResultSetTransport getIndexInfo(String catalog, String schema, String table,
                                    boolean unique, boolean approximate)
        throws SQLException;

    boolean supportsResultSetType(int type) throws SQLException;

    boolean supportsResultSetConcurrency(int type, int concurrency)
        throws SQLException;

    boolean ownUpdatesAreVisible(int type) throws SQLException;

    boolean ownDeletesAreVisible(int type) throws SQLException;

    boolean ownInsertsAreVisible(int type) throws SQLException;

    boolean othersUpdatesAreVisible(int type) throws SQLException;

    boolean othersDeletesAreVisible(int type) throws SQLException;

    boolean othersInsertsAreVisible(int type) throws SQLException;

    boolean updatesAreDetected(int type) throws SQLException;

    boolean deletesAreDetected(int type) throws SQLException;

    boolean insertsAreDetected(int type) throws SQLException;

    boolean supportsBatchUpdates() throws SQLException;

    ResultSetTransport getUDTs(String catalog, String schemaPattern, 
                               String typeNamePattern, int[] types) 
        throws SQLException;

    boolean supportsSavepoints() throws SQLException;

    boolean supportsNamedParameters() throws SQLException;

    boolean supportsMultipleOpenResults() throws SQLException;

    boolean supportsGetGeneratedKeys() throws SQLException;

    ResultSetTransport getSuperTypes(String catalog, String schemaPattern, 
                                     String typeNamePattern) throws SQLException;
    
    ResultSetTransport getSuperTables(String catalog, String schemaPattern,
                                      String tableNamePattern) throws SQLException;

    ResultSetTransport getAttributes(String catalog, String schemaPattern,
                                     String typeNamePattern, String attributeNamePattern) 
        throws SQLException;

    boolean supportsResultSetHoldability(int holdability) throws SQLException;

    int getResultSetHoldability() throws SQLException;

    int getDatabaseMajorVersion() throws SQLException;

    int getDatabaseMinorVersion() throws SQLException;

    int getJDBCMajorVersion() throws SQLException;

    int getJDBCMinorVersion() throws SQLException;

    int getSQLStateType() throws SQLException;

    boolean locatorsUpdateCopy() throws SQLException;

    boolean supportsStatementPooling() throws SQLException;

    RowIdLifetime getRowIdLifetime() throws SQLException;

    ResultSetTransport getSchemas(String catalog, String schemaPattern) throws SQLException;
    
    boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException;
     
    boolean autoCommitFailureClosesAllResultSets() throws SQLException;

    ResultSetTransport getClientInfoProperties()
        throws SQLException;
   
    ResultSetTransport getFunctions(String catalog, String schemaPattern,
                                    String functionNamePattern)
        throws SQLException;

    ResultSetTransport getFunctionColumns(String catalog, String schemaPattern,
                                          String functionNamePattern,
                                          String columnNamePattern)
        throws SQLException;
}
