/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.connector;

import io.airlift.slice.Slice;
import io.trino.spi.Experimental;
import io.trino.spi.TrinoException;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.Variable;
import io.trino.spi.function.AggregationFunctionMetadata;
import io.trino.spi.function.BoundSignature;
import io.trino.spi.function.FunctionDependencyDeclaration;
import io.trino.spi.function.FunctionId;
import io.trino.spi.function.FunctionMetadata;
import io.trino.spi.function.SchemaFunctionName;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.ptf.ConnectorTableFunctionHandle;
import io.trino.spi.security.GrantInfo;
import io.trino.spi.security.Privilege;
import io.trino.spi.security.RoleGrant;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.statistics.TableStatistics;
import io.trino.spi.statistics.TableStatisticsMetadata;
import io.trino.spi.type.Type;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.expression.StandardFunctions.AND_FUNCTION_NAME;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.toUnmodifiableList;

public interface ConnectorMetadata
{
    String MODIFYING_ROWS_MESSAGE = "This connector does not support modifying table rows";

    /**
     * Checks if a schema exists. The connector may have schemas that exist
     * but are not enumerable via {@link #listSchemaNames}.
     */
    default boolean schemaExists(ConnectorSession session, String schemaName)
    {
        if (!schemaName.equals(schemaName.toLowerCase(ENGLISH))) {
            // Currently, Trino schemas are always lowercase, so this one cannot exist (https://github.com/trinodb/trino/issues/17)
            return false;
        }
        return listSchemaNames(session).stream()
                // Lower-casing is done by callers of listSchemaNames (see MetadataManager)
                .map(schema -> schema.toLowerCase(ENGLISH))
                .anyMatch(schemaName::equals);
    }

    /**
     * Returns the schemas provided by this connector.
     */
    default List<String> listSchemaNames(ConnectorSession session)
    {
        return emptyList();
    }

    /**
     * Returns a table handle for the specified table name, or {@code null} if {@code tableName} relation does not exist
     * or is not a table (e.g. is a view, or a materialized view).
     *
     * @throws TrinoException implementation can throw this exception when {@code tableName} refers to a table that
     * cannot be queried.
     * @see #getView(ConnectorSession, SchemaTableName)
     * @see #getMaterializedView(ConnectorSession, SchemaTableName)
     */
    @Nullable
    default ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return null;
    }

    /**
     * Returns a table handle for the specified table name and version, or {@code null} if {@code tableName} relation does not exist
     * or is not a table (e.g. is a view, or a materialized view).
     *
     * @throws TrinoException implementation can throw this exception when {@code tableName} refers to a table that
     * cannot be queried.
     * @see #getView(ConnectorSession, SchemaTableName)
     * @see #getMaterializedView(ConnectorSession, SchemaTableName)
     */
    @Nullable
    default ConnectorTableHandle getTableHandle(
            ConnectorSession session,
            SchemaTableName tableName,
            Optional<ConnectorTableVersion> startVersion,
            Optional<ConnectorTableVersion> endVersion)
    {
        ConnectorTableHandle tableHandle = getTableHandle(session, tableName);
        if (tableHandle == null) {
            // Not found
            return null;
        }
        if (startVersion.isEmpty() && endVersion.isEmpty()) {
            return tableHandle;
        }
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support versioned tables");
    }

    /**
     * Create initial handle for execution of table procedure. The handle will be used through planning process. It will be converted to final
     * handle used for execution via @{link {@link ConnectorMetadata#beginTableExecute}
     *
     * <p/>
     * If connector does not support execution with retries, the method should throw:
     * <pre>
     *     new TrinoException(NOT_SUPPORTED, "This connector does not support query retries")
     * </pre>
     * unless {@code retryMode} is set to {@code NO_RETRIES}.
     */
    default Optional<ConnectorTableExecuteHandle> getTableHandleForExecute(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            String procedureName,
            Map<String, Object> executeProperties,
            RetryMode retryMode)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support table procedures");
    }

    default Optional<ConnectorTableLayout> getLayoutForTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle)
    {
        return Optional.empty();
    }

    /**
     * Begin execution of table procedure
     */
    default BeginTableExecuteResult<ConnectorTableExecuteHandle, ConnectorTableHandle> beginTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle, ConnectorTableHandle updatedSourceTableHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getTableHandleForExecute() is implemented without beginTableExecute()");
    }

    /**
     * Finish table execute
     *
     * @param fragments all fragments returned by {@link ConnectorPageSink#finish()}
     */
    default void finishTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle, Collection<Slice> fragments, List<Object> tableExecuteState)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getTableHandleForExecute() is implemented without finishTableExecute()");
    }

    /**
     * Execute a {@link TableProcedureExecutionMode#coordinatorOnly() coordinator-only} table procedure.
     */
    default void executeTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata executeTableExecute() is not implemented");
    }

    /**
     * Returns the system table for the specified table name, if one exists.
     * The system tables handled via this method differ form those returned by {@link Connector#getSystemTables()}.
     * The former mechanism allows dynamic resolution of system tables, while the latter is
     * based on static list of system tables built during startup.
     */
    default Optional<SystemTable> getSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        return Optional.empty();
    }

    /**
     * Return a table handle whose partitioning is converted to the provided partitioning handle,
     * but otherwise identical to the provided table handle.
     * The provided table handle must be one that the connector can transparently convert to from
     * the original partitioning handle associated with the provided table handle,
     * as promised by {@link #getCommonPartitioningHandle}.
     */
    default ConnectorTableHandle makeCompatiblePartitioning(ConnectorSession session, ConnectorTableHandle tableHandle, ConnectorPartitioningHandle partitioningHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getCommonPartitioningHandle() is implemented without makeCompatiblePartitioning()");
    }

    /**
     * Return a partitioning handle which the connector can transparently convert both {@code left} and {@code right} into.
     */
    default Optional<ConnectorPartitioningHandle> getCommonPartitioningHandle(ConnectorSession session, ConnectorPartitioningHandle left, ConnectorPartitioningHandle right)
    {
        if (left.equals(right)) {
            return Optional.of(left);
        }
        return Optional.empty();
    }

    /**
     * Return schema table name for the specified table handle.
     * This method is useful when requiring only {@link SchemaTableName} without other objects.
     *
     * @throws RuntimeException if table handle is no longer valid
     */
    @Deprecated // ... and optimized implementations already removed
    default SchemaTableName getSchemaTableName(ConnectorSession session, ConnectorTableHandle table)
    {
        return getTableSchema(session, table).getTable();
    }

    /**
     * Return table schema definition for the specified table handle.
     * This method is useful when getting full table metadata is expensive.
     *
     * @throws RuntimeException if table handle is no longer valid
     */
    default ConnectorTableSchema getTableSchema(ConnectorSession session, ConnectorTableHandle table)
    {
        return getTableMetadata(session, table).getTableSchema();
    }

    /**
     * Return the metadata for the specified table handle.
     *
     * @throws RuntimeException if table handle is no longer valid
     */
    default ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getTableHandle() is implemented without getTableMetadata()");
    }

    default Optional<Object> getInfo(ConnectorTableHandle table)
    {
        return Optional.empty();
    }

    /**
     * List table, view and materialized view names, possibly filtered by schema. An empty list is returned if none match.
     * An empty list is returned also when schema name does not refer to an existing schema.
     */
    default List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        return emptyList();
    }

    /**
     * Gets all of the columns on the specified table, or an empty map if the columns cannot be enumerated.
     *
     * @throws RuntimeException if table handle is no longer valid
     */
    default Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getTableHandle() is implemented without getColumnHandles()");
    }

    /**
     * Gets the metadata for the specified table column.
     *
     * @throws RuntimeException if table or column handles are no longer valid
     */
    default ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getTableHandle() is implemented without getColumnMetadata()");
    }

    /**
     * Gets the metadata for all columns that match the specified table prefix.
     *
     * @deprecated use {@link #streamTableColumns} which handles redirected tables
     */
    @Deprecated
    default Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        return emptyMap();
    }

    /**
     * Gets the metadata for all columns that match the specified table prefix. Redirected table names are included, but
     * the column metadata for them is not.
     */
    default Iterator<TableColumnsMetadata> streamTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        return listTableColumns(session, prefix).entrySet().stream()
                .map(entry -> TableColumnsMetadata.forTable(entry.getKey(), entry.getValue()))
                .iterator();
    }

    /**
     * Get statistics for table.
     */
    default TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return TableStatistics.empty();
    }

    /**
     * Creates a schema.
     */
    default void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, TrinoPrincipal owner)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating schemas");
    }

    /**
     * Drops the specified schema.
     *
     * @throws TrinoException with {@code SCHEMA_NOT_EMPTY} if the schema is not empty
     */
    default void dropSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping schemas");
    }

    /**
     * Renames the specified schema.
     */
    default void renameSchema(ConnectorSession session, String source, String target)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming schemas");
    }

    /**
     * Sets the user/role on the specified schema.
     */
    default void setSchemaAuthorization(ConnectorSession session, String schemaName, TrinoPrincipal principal)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting an owner on a schema");
    }

    /**
     * Creates a table using the specified table metadata.
     *
     * @throws TrinoException with {@code ALREADY_EXISTS} if the table already exists and {@param ignoreExisting} is not set
     */
    default void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables");
    }

    /**
     * Drops the specified table
     *
     * @throws RuntimeException if the table cannot be dropped or table handle is no longer valid
     */
    default void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping tables");
    }

    /**
     * Truncates the specified table
     *
     * @throws RuntimeException if the table cannot be dropped or table handle is no longer valid
     */
    default void truncateTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support truncating tables");
    }

    /**
     * Rename the specified table
     */
    default void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables");
    }

    /**
     * Set properties to the specified table
     */
    default void setTableProperties(ConnectorSession session, ConnectorTableHandle tableHandle, Map<String, Optional<Object>> properties)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting table properties");
    }

    /**
     * Comments to the specified table
     */
    default void setTableComment(ConnectorSession session, ConnectorTableHandle tableHandle, Optional<String> comment)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting table comments");
    }

    /**
     * Comments to the specified view
     */
    default void setViewComment(ConnectorSession session, SchemaTableName viewName, Optional<String> comment)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting view comments");
    }

    /**
     * Comments to the specified view column.
     */
    default void setViewColumnComment(ConnectorSession session, SchemaTableName viewName, String columnName, Optional<String> comment)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting view column comments");
    }

    /**
     * Comments to the specified column
     */
    default void setColumnComment(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column, Optional<String> comment)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting column comments");
    }

    /**
     * Add the specified column
     */
    default void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns");
    }

    /**
     * Set the specified column type
     */
    @Experimental(eta = "2023-04-01")
    default void setColumnType(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting column types");
    }

    /**
     * Sets the user/role on the specified table.
     */
    default void setTableAuthorization(ConnectorSession session, SchemaTableName tableName, TrinoPrincipal principal)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting an owner on a table");
    }

    /**
     * Rename the specified column
     */
    default void renameColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle source, String target)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming columns");
    }

    /**
     * Drop the specified column
     */
    default void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping columns");
    }

    /**
     * Drop the specified field, potentially nested, from a row.
     *
     * @param fieldPath path to a field within the column, without leading column name.
     */
    @Experimental(eta = "2023-05-01") // TODO add support for rows inside arrays and maps and for anonymous row fields
    default void dropField(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column, List<String> fieldPath)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping fields");
    }

    /**
     * Get the physical layout for a new table.
     */
    default Optional<ConnectorTableLayout> getNewTableLayout(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        return Optional.empty();
    }

    /**
     * Get the physical layout for inserting into an existing table.
     */
    default Optional<ConnectorTableLayout> getInsertLayout(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        ConnectorTableProperties properties = getTableProperties(session, tableHandle);
        return properties.getTablePartitioning()
                .map(partitioning -> {
                    Map<ColumnHandle, String> columnNamesByHandle = getColumnHandles(session, tableHandle).entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
                    List<String> partitionColumns = partitioning.getPartitioningColumns().stream()
                            .map(columnNamesByHandle::get)
                            .collect(toUnmodifiableList());

                    return new ConnectorTableLayout(partitioning.getPartitioningHandle(), partitionColumns);
                });
    }

    /**
     * Describes statistics that must be collected during a write.
     */
    default TableStatisticsMetadata getStatisticsCollectionMetadataForWrite(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        return TableStatisticsMetadata.empty();
    }

    /**
     * Describe statistics that must be collected during a statistics collection
     */
    default ConnectorAnalyzeMetadata getStatisticsCollectionMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, Map<String, Object> analyzeProperties)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support analyze");
    }

    /**
     * Begin statistics collection
     */
    default ConnectorTableHandle beginStatisticsCollection(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getStatisticsCollectionMetadata() is implemented without beginStatisticsCollection()");
    }

    /**
     * Finish statistics collection
     */
    default void finishStatisticsCollection(ConnectorSession session, ConnectorTableHandle tableHandle, Collection<ComputedStatistics> computedStatistics)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata beginStatisticsCollection() is implemented without finishStatisticsCollection()");
    }

    /**
     * Begin the atomic creation of a table with data.
     *
     * <p/>
     * If connector does not support execution with retries, the method should throw:
     * <pre>
     *     new TrinoException(NOT_SUPPORTED, "This connector does not support query retries")
     * </pre>
     * unless {@code retryMode} is set to {@code NO_RETRIES}.
     */
    default ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorTableLayout> layout, RetryMode retryMode)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables with data");
    }

    /**
     * Finish a table creation with data after the data is written.
     */
    default Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata beginCreateTable() is implemented without finishCreateTable()");
    }

    /**
     * Start a query. This notification is triggered before any other metadata access.
     */
    default void beginQuery(ConnectorSession session) {}

    /**
     * Cleanup after a query. This is the very last notification after the query finishes, whether it succeeds or fails.
     * An exception thrown in this method will not affect the result of the query.
     */
    default void cleanupQuery(ConnectorSession session) {}

    /**
     * Begin insert query.
     *
     * <p/>
     * If connector does not support execution with retries, the method should throw:
     * <pre>
     *     new TrinoException(NOT_SUPPORTED, "This connector does not support query retries")
     * </pre>
     * unless {@code retryMode} is set to {@code NO_RETRIES}.
     */
    default ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns, RetryMode retryMode)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support inserts");
    }

    /**
     * @return whether connector handles missing columns during insert
     */
    default boolean supportsMissingColumnsOnInsert()
    {
        return false;
    }

    /**
     * Finish insert query
     */
    default Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata beginInsert() is implemented without finishInsert()");
    }

    /**
     * Returns true if materialized view refresh should be delegated to connector using {@link ConnectorMetadata#refreshMaterializedView}
     */
    default boolean delegateMaterializedViewRefreshToConnector(ConnectorSession session, SchemaTableName viewName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support materialized views");
    }

    /**
     * Refresh materialized view
     */
    default CompletableFuture<?> refreshMaterializedView(ConnectorSession session, SchemaTableName viewName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support materialized views");
    }

    /**
     * Begin materialized view query.
     *
     * <p/>
     * If connector does not support execution with retries, the method should throw:
     * <pre>
     *     new TrinoException(NOT_SUPPORTED, "This connector does not support query retries")
     * </pre>
     * unless {@code retryMode} is set to {@code NO_RETRIES}.
     */
    default ConnectorInsertTableHandle beginRefreshMaterializedView(ConnectorSession session, ConnectorTableHandle tableHandle, List<ConnectorTableHandle> sourceTableHandles, RetryMode retryMode)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support materialized views");
    }

    /**
     * Finish materialized view query
     */
    default Optional<ConnectorOutputMetadata> finishRefreshMaterializedView(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            ConnectorInsertTableHandle insertHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics,
            List<ConnectorTableHandle> sourceTableHandles)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata beginRefreshMaterializedView() is implemented without finishRefreshMaterializedView()");
    }

    /**
     * Return the row change paradigm supported by the connector on the table.
     */
    default RowChangeParadigm getRowChangeParadigm(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(NOT_SUPPORTED, MODIFYING_ROWS_MESSAGE);
    }

    /**
     * Get the column handle that will generate row IDs for the merge operation.
     * These IDs will be passed to the {@link ConnectorMergeSink#storeMergedRows}
     * method of the {@link ConnectorMergeSink} that created them.
     */
    default ColumnHandle getMergeRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(NOT_SUPPORTED, MODIFYING_ROWS_MESSAGE);
    }

    /**
     * Get the physical layout for updated or deleted rows of a MERGE operation.
     * Inserted rows are handled by {@link #getInsertLayout}.
     * This layout always uses the {@link #getMergeRowIdColumnHandle merge row ID column}.
     */
    default Optional<ConnectorPartitioningHandle> getUpdateLayout(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return Optional.empty();
    }

    /**
     * Do whatever is necessary to start an MERGE query, returning the {@link ConnectorMergeTableHandle}
     * instance that will be passed to the PageSink, and to the {@link #finishMerge} method.
     */
    default ConnectorMergeTableHandle beginMerge(ConnectorSession session, ConnectorTableHandle tableHandle, RetryMode retryMode)
    {
        throw new TrinoException(NOT_SUPPORTED, MODIFYING_ROWS_MESSAGE);
    }

    /**
     * Finish a merge query
     *
     * @param session The session
     * @param tableHandle A ConnectorMergeTableHandle for the table that is the target of the merge
     * @param fragments All fragments returned by the merge plan
     * @param computedStatistics Statistics for the table, meaningful only to the connector that produced them.
     */
    default void finishMerge(ConnectorSession session, ConnectorMergeTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata beginMerge() is implemented without finishMerge()");
    }

    /**
     * Create the specified view. The view definition is intended to
     * be serialized by the connector for permanent storage.
     */
    default void createView(ConnectorSession session, SchemaTableName viewName, ConnectorViewDefinition definition, boolean replace)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating views");
    }

    /**
     * Rename the specified view
     */
    default void renameView(ConnectorSession session, SchemaTableName source, SchemaTableName target)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming views");
    }

    /**
     * Sets the user/role on the specified view.
     */
    default void setViewAuthorization(ConnectorSession session, SchemaTableName viewName, TrinoPrincipal principal)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting an owner on a view");
    }

    /**
     * Drop the specified view.
     */
    default void dropView(ConnectorSession session, SchemaTableName viewName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping views");
    }

    /**
     * List view names, possibly filtered by schema. An empty list is returned if none match.
     * An empty list is returned also when schema name does not refer to an existing schema.
     */
    default List<SchemaTableName> listViews(ConnectorSession session, Optional<String> schemaName)
    {
        return emptyList();
    }

    /**
     * Gets the definitions of views, possibly filtered by schema.
     * This optional method may be implemented by connectors that can support fetching
     * view data in bulk. It is used to implement {@code information_schema.views}.
     */
    default Map<SchemaTableName, ConnectorViewDefinition> getViews(ConnectorSession session, Optional<String> schemaName)
    {
        Map<SchemaTableName, ConnectorViewDefinition> views = new HashMap<>();
        for (SchemaTableName name : listViews(session, schemaName)) {
            getView(session, name).ifPresent(view -> views.put(name, view));
        }
        return views;
    }

    /**
     * Gets the view data for the specified view name. Returns {@link Optional#empty()} if {@code viewName}
     * relation does not or is not a view (e.g. is a table, or a materialized view).
     *
     * @see #getTableHandle(ConnectorSession, SchemaTableName)
     * @see #getMaterializedView(ConnectorSession, SchemaTableName)
     */
    default Optional<ConnectorViewDefinition> getView(ConnectorSession session, SchemaTableName viewName)
    {
        return Optional.empty();
    }

    /**
     * Gets the schema properties for the specified schema.
     */
    default Map<String, Object> getSchemaProperties(ConnectorSession session, CatalogSchemaName schemaName)
    {
        return Map.of();
    }

    /**
     * Get the schema properties for the specified schema.
     */
    default Optional<TrinoPrincipal> getSchemaOwner(ConnectorSession session, CatalogSchemaName schemaName)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down a delete operation into the connector. If a connector
     * can execute a delete for the table handle on its own, it should return a
     * table handle, which will be passed back to {@link #executeDelete} during
     * query executing to actually execute the delete.
     */
    default Optional<ConnectorTableHandle> applyDelete(ConnectorSession session, ConnectorTableHandle handle)
    {
        return Optional.empty();
    }

    /**
     * Execute the delete operation on the handle returned from {@link #applyDelete}.
     */
    default OptionalLong executeDelete(ConnectorSession session, ConnectorTableHandle handle)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata applyDelete() is implemented without executeDelete()");
    }

    /**
     * Try to locate a table index that can lookup results by indexableColumns and provide the requested outputColumns.
     */
    default Optional<ConnectorResolvedIndex> resolveIndex(ConnectorSession session, ConnectorTableHandle tableHandle, Set<ColumnHandle> indexableColumns, Set<ColumnHandle> outputColumns, TupleDomain<ColumnHandle> tupleDomain)
    {
        return Optional.empty();
    }

    /**
     * List available functions.
     */
    default Collection<FunctionMetadata> listFunctions(ConnectorSession session, String schemaName)
    {
        return List.of();
    }

    /**
     * Get all functions with specified name.
     */
    default Collection<FunctionMetadata> getFunctions(ConnectorSession session, SchemaFunctionName name)
    {
        return List.of();
    }

    /**
     * Return the function with the specified id.
     */
    default FunctionMetadata getFunctionMetadata(ConnectorSession session, FunctionId functionId)
    {
        throw new IllegalArgumentException("Unknown function " + functionId);
    }

    /**
     * Returns the aggregation metadata for the aggregation function with the specified id.
     */
    default AggregationFunctionMetadata getAggregationFunctionMetadata(ConnectorSession session, FunctionId functionId)
    {
        throw new IllegalArgumentException("Unknown function " + functionId);
    }

    /**
     * Returns the dependencies of the function with the specified id.
     */
    default FunctionDependencyDeclaration getFunctionDependencies(ConnectorSession session, FunctionId functionId, BoundSignature boundSignature)
    {
        throw new IllegalArgumentException("Unknown function " + functionId);
    }

    /**
     * Does the specified role exist.
     */
    default boolean roleExists(ConnectorSession session, String role)
    {
        return listRoles(session).contains(role);
    }

    /**
     * Creates the specified role.
     *
     * @param grantor represents the principal specified by WITH ADMIN statement
     */
    default void createRole(ConnectorSession session, String role, Optional<TrinoPrincipal> grantor)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support create role");
    }

    /**
     * Drops the specified role.
     */
    default void dropRole(ConnectorSession session, String role)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support drop role");
    }

    /**
     * List available roles.
     */
    default Set<String> listRoles(ConnectorSession session)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * List role grants for a given principal, not recursively.
     */
    default Set<RoleGrant> listRoleGrants(ConnectorSession session, TrinoPrincipal principal)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * Grants the specified roles to the specified grantees
     *
     * @param grantor represents the principal specified by GRANTED BY statement
     */
    default void grantRoles(ConnectorSession connectorSession, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * Revokes the specified roles from the specified grantees
     *
     * @param grantor represents the principal specified by GRANTED BY statement
     */
    default void revokeRoles(ConnectorSession connectorSession, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * List applicable roles, including the transitive grants, for the specified principal
     */
    default Set<RoleGrant> listApplicableRoles(ConnectorSession session, TrinoPrincipal principal)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * List applicable roles, including the transitive grants, in given session
     */
    default Set<String> listEnabledRoles(ConnectorSession session)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support roles");
    }

    /**
     * Grants the specified privilege to the specified user on the specified schema
     */
    default void grantSchemaPrivileges(ConnectorSession session, String schemaName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support grants on schemas");
    }

    /**
     * Denys the specified privilege to the specified user on the specified schema
     */
    default void denySchemaPrivileges(ConnectorSession session, String schemaName, Set<Privilege> privileges, TrinoPrincipal grantee)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support denys on schemas");
    }

    /**
     * Revokes the specified privilege on the specified schema from the specified user
     */
    default void revokeSchemaPrivileges(ConnectorSession session, String schemaName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support revokes on schemas");
    }

    /**
     * Grants the specified privilege to the specified user on the specified table
     */
    default void grantTablePrivileges(ConnectorSession session, SchemaTableName tableName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support grants on tables");
    }

    /**
     * Denys the specified privilege to the specified user on the specified table
     */
    default void denyTablePrivileges(ConnectorSession session, SchemaTableName tableName, Set<Privilege> privileges, TrinoPrincipal grantee)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support denys on tables");
    }

    /**
     * Revokes the specified privilege on the specified table from the specified user
     */
    default void revokeTablePrivileges(ConnectorSession session, SchemaTableName tableName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support revokes on tables");
    }

    /**
     * List the table privileges granted to the specified grantee for the tables that have the specified prefix considering the selected session role
     */
    default List<GrantInfo> listTablePrivileges(ConnectorSession session, SchemaTablePrefix prefix)
    {
        return emptyList();
    }

    default ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        return new ConnectorTableProperties();
    }

    /**
     * Attempt to push down the provided limit into the table.
     * <p>
     * Connectors can indicate whether they don't support limit pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method to be called multiple times
     * during the optimization of a given query.
     * <p>
     * <b>Note</b>: it's critical for connectors to return Optional.empty() if calling this method has no effect for that
     * invocation, even if the connector generally supports limit pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * </p>
     * <p>
     * If the connector could benefit from the information but can't guarantee that it will be able to produce
     * fewer rows than the provided limit, it should return a non-empty result containing a new handle for the
     * derived table and the "limit guaranteed" flag set to false.
     * <p>
     * If the connector can guarantee it will produce fewer rows than the provided limit, it should return a
     * non-empty result with the "limit guaranteed" flag set to true.
     */
    default Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(ConnectorSession session, ConnectorTableHandle handle, long limit)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the provided constraint into the table.
     * <p>
     * Connectors can indicate whether they don't support predicate pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method to be called multiple times
     * during the optimization of a given query.
     * <p>
     * <b>Note</b>: it's critical for connectors to return Optional.empty() if calling this method has no effect for that
     * invocation, even if the connector generally supports pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * </p>
     * <p>
     * <b>Note</b>: Implementation must not maintain reference to {@code constraint}'s {@link Constraint#predicate()} after the
     * call returns.
     * </p>
     *
     * @param constraint constraint to be applied to the table. {@link Constraint#getSummary()} is guaranteed not to be {@link TupleDomain#isNone() none}.
     */
    default Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
    {
        if (constraint.getSummary().getDomains().isEmpty()) {
            throw new IllegalArgumentException("constraint summary is NONE");
        }
        return Optional.empty();
    }

    /**
     * Attempt to push down the provided projections into the table.
     * <p>
     * Connectors can indicate whether they don't support projection pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method to be called multiple times
     * during the optimization of a given query.
     * <p>
     * <b>Note</b>: it's critical for connectors to return Optional.empty() if calling this method has no effect for that
     * invocation, even if the connector generally supports pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * </p>
     * <p>
     * If the method returns a result, the list of projections in the result *replaces* the existing ones, and the
     * list of assignments is the new set of columns exposed by the derived table.
     * <p>
     * As an example, given the following plan:
     *
     * <pre>
     * - project
     *     x = f1(a, b)
     *     y = f2(a, b)
     *     z = f3(a, b)
     *   - scan (TH0)
     *       a = CH0
     *       b = CH1
     *       c = CH2
     * </pre>
     * <p>
     * The optimizer would call this method with the following arguments:
     *
     * <pre>
     * handle = TH0
     * projections = [
     *     f1(a, b)
     *     f2(a, b)
     *     f3(a, b)
     * ]
     * assignments = [
     *     a = CH0
     *     b = CH1
     *     c = CH2
     * ]
     * </pre>
     * <p>
     * Assuming the connector knows how to handle f1(...) and f2(...), it would return:
     *
     * <pre>
     * handle = TH1
     * projections = [
     *     v2
     *     v3
     *     f3(v0, v1)
     * ]
     * assignments = [
     *     v0 = CH0
     *     v1 = CH1
     *     v2 = CH3  (synthetic column for f1(CH0, CH1))
     *     v3 = CH4  (synthetic column for f2(CH0, CH1))
     * ]
     * </pre>
     */
    default Optional<ProjectionApplicationResult<ConnectorTableHandle>> applyProjection(ConnectorSession session, ConnectorTableHandle handle, List<ConnectorExpression> projections, Map<String, ColumnHandle> assignments)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the sampling into the table.
     * <p>
     * Connectors can indicate whether they don't support sample pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method to be called multiple times
     * during the optimization of a given query.
     * <p>
     * <b>Note</b>: it's critical for connectors to return Optional.empty() if calling this method has no effect for that
     * invocation, even if the connector generally supports sample pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * </p>
     */
    default Optional<SampleApplicationResult<ConnectorTableHandle>> applySample(ConnectorSession session, ConnectorTableHandle handle, SampleType sampleType, double sampleRatio)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the aggregates into the table.
     * <p>
     * Connectors can indicate whether they don't support aggregate pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method may be called multiple times.
     * </p>
     * <b>Note</b>: it's critical for connectors to return {@link Optional#empty()} if calling this method has no effect for that
     * invocation, even if the connector generally supports pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * <p>
     * If the method returns a result, the list of assignments in the result will be merged with existing assignments. The projections
     * returned by the method must have the same order as the given input list of aggregates.
     * </p>
     * As an example, given the following plan:
     *
     * <pre>
     *  - aggregation  (GROUP BY c)
     *          variable0 = agg_fn1(a)
     *          variable1 = agg_fn2(b, 2)
     *          variable2 = c
     *          - scan (TH0)
     *              a = CH0
     *              b = CH1
     *              c = CH2
     * </pre>
     * <p>
     * The optimizer would call this method with the following arguments:
     *
     * <pre>
     *      handle = TH0
     *      aggregates = [
     *              { functionName=agg_fn1, outputType = «some Trino type» inputs = [{@link Variable} a]} ,
     *              { functionName=agg_fn2, outputType = «some Trino type» inputs = [{@link Variable} b, {@link Constant} 2]}
     *      ]
     *      groupingSets=[[{@link ColumnHandle} CH2]]
     *      assignments = {a = CH0, b = CH1, c = CH2}
     * </pre>
     * </p>
     * <p>
     * Assuming the connector knows how to handle {@code agg_fn1(...)} and {@code agg_fn2(...)}, it would return:
     * <pre>
     *
     * {@link AggregationApplicationResult} {
     *      handle = TH1
     *      projections = [{@link Variable} synthetic_name0, {@link Variable} synthetic_name1] -- <b>The order in the list must be same as input list of aggregates</b>
     *      assignments = {
     *          synthetic_name0 = CH3 (synthetic column for agg_fn1(a))
     *          synthetic_name1 = CH4 (synthetic column for agg_fn2(b,2))
     *      }
     * }
     * </pre>
     * <p>
     * if the connector only knows how to handle {@code agg_fn1(...)}, but not {@code agg_fn2}, it should return {@link Optional#empty()}.
     *
     * <p>
     * Another example is where the connector wants to handle the aggregate function by pointing to a pre-materialized table.
     * In this case the input can stay same as in the above example and the connector can return
     * <pre>
     * {@link AggregationApplicationResult} {
     *      handle = TH1 (could capture information about which pre-materialized table to use)
     *      projections = [{@link Variable} synthetic_name0, {@link Variable} synthetic_name1] -- <b>The order in the list must be same as input list of aggregates</b>
     *      assignments = {
     *          synthetic_name0 = CH3 (reference to the column in pre-materialized table that has agg_fn1(a) calculated)
     *          synthetic_name1 = CH4 (reference to the column in pre-materialized table that has agg_fn2(b,2) calculated)
     *          synthetic_name2 = CH5 (reference to the column in pre-materialized table that has the group by column c)
     *      }
     *      groupingColumnMapping = {
     *          CH2 -> CH5 (CH2 in the original assignment should now be replaced by CH5 in the new assignment)
     *      }
     * }
     * </pre>
     * </p>
     */
    default Optional<AggregationApplicationResult<ConnectorTableHandle>> applyAggregation(
            ConnectorSession session,
            ConnectorTableHandle handle,
            List<AggregateFunction> aggregates,
            Map<String, ColumnHandle> assignments,
            List<List<ColumnHandle>> groupingSets)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the join operation.
     * <p>
     * Connectors can indicate whether they don't support join pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method may be called multiple times.
     * </p>
     * <b>Warning</b>: this is an experimental API and it will change in the future.
     * <p>
     * Join condition conjuncts are passed via joinConditions list. For current implementation connector may
     * assume that leftExpression and rightExpression in each of the conjucts are instances of {@link Variable}.
     * This may be relaxed in the future.
     * </p>
     * <p>
     * The leftAssignments and rightAssignments lists provide mappings from variable names, used in joinConditions to input tables column handles.
     * It is guaranteed that all the required mappings will be provided but not necessarily *all* the column handles of tables which are join inputs.
     * </p>
     * <p>
     * Table statistics for left, right table as well as estimated statistics for join are provided via statistics parameter.
     * Those can be used by connector to assess if performing join pushdown is expected to improve query performance.
     * </p>
     *
     * <p>
     * If the method returns a result the returned table handle will be used in place of join and input table scans.
     * Returned result must provide mapping from old column handles to new ones via leftColumnHandles and rightColumnHandles fields of the result.
     * It is required that mapping is provided for *all* column handles exposed previously by both left and right join sources.
     * </p>
     */
    default Optional<JoinApplicationResult<ConnectorTableHandle>> applyJoin(
            ConnectorSession session,
            JoinType joinType,
            ConnectorTableHandle left,
            ConnectorTableHandle right,
            ConnectorExpression joinCondition,
            Map<String, ColumnHandle> leftAssignments,
            Map<String, ColumnHandle> rightAssignments,
            JoinStatistics statistics)
    {
        List<JoinCondition> conditions;
        if (joinCondition instanceof Call call && AND_FUNCTION_NAME.equals(call.getFunctionName())) {
            conditions = new ArrayList<>(call.getArguments().size());
            for (ConnectorExpression argument : call.getArguments()) {
                if (Constant.TRUE.equals(argument)) {
                    continue;
                }
                Optional<JoinCondition> condition = JoinCondition.from(argument, leftAssignments.keySet(), rightAssignments.keySet());
                if (condition.isEmpty()) {
                    // We would need to add a FilterNode on top of the result
                    return Optional.empty();
                }
                conditions.add(condition.get());
            }
        }
        else {
            Optional<JoinCondition> condition = JoinCondition.from(joinCondition, leftAssignments.keySet(), rightAssignments.keySet());
            if (condition.isEmpty()) {
                return Optional.empty();
            }
            conditions = List.of(condition.get());
        }
        return applyJoin(
                session,
                joinType,
                left,
                right,
                conditions,
                leftAssignments,
                rightAssignments,
                statistics);
    }

    @Deprecated
    default Optional<JoinApplicationResult<ConnectorTableHandle>> applyJoin(
            ConnectorSession session,
            JoinType joinType,
            ConnectorTableHandle left,
            ConnectorTableHandle right,
            List<JoinCondition> joinConditions,
            Map<String, ColumnHandle> leftAssignments,
            Map<String, ColumnHandle> rightAssignments,
            JoinStatistics statistics)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the TopN into the table scan.
     * <p>
     * Connectors can indicate whether they don't support topN pushdown or that the action had no effect
     * by returning {@link Optional#empty()}. Connectors should expect this method may be called multiple times.
     * </p>
     * <b>Note</b>: it's critical for connectors to return {@link Optional#empty()} if calling this method has no effect for that
     * invocation, even if the connector generally supports topN pushdown. Doing otherwise can cause the optimizer
     * to loop indefinitely.
     * <p>
     * If the connector can handle TopN Pushdown and guarantee it will produce no more rows than requested then it should return a
     * non-empty result with "topN guaranteed" flag set to true.
     */
    default Optional<TopNApplicationResult<ConnectorTableHandle>> applyTopN(
            ConnectorSession session,
            ConnectorTableHandle handle,
            long topNCount,
            List<SortItem> sortItems,
            Map<String, ColumnHandle> assignments)
    {
        return Optional.empty();
    }

    /**
     * Attempt to push down the table function invocation into the connector.
     * <p>
     * Connectors can indicate whether they don't support table function invocation pushdown or that the action had no
     * effect by returning {@link Optional#empty()}. Connectors should expect this method may be called multiple times.
     * <p>
     * If the method returns a result, the returned table handle will be used in place of the table function invocation.
     */
    default Optional<TableFunctionApplicationResult<ConnectorTableHandle>> applyTableFunction(ConnectorSession session, ConnectorTableFunctionHandle handle)
    {
        return Optional.empty();
    }

    /**
     * Allows the connector to reject the table scan produced by the planner.
     * <p>
     * Connectors can choose to reject a query based on the table scan potentially being too expensive, for example
     * if no filtering is done on a partition column.
     * <p>
     */
    default void validateScan(ConnectorSession session, ConnectorTableHandle handle) {}

    /**
     * Create the specified materialized view. The view definition is intended to
     * be serialized by the connector for permanent storage.
     *
     * @throws TrinoException with {@code ALREADY_EXISTS} if the object already exists and {@param ignoreExisting} is not set
     */
    default void createMaterializedView(ConnectorSession session, SchemaTableName viewName, ConnectorMaterializedViewDefinition definition, boolean replace, boolean ignoreExisting)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating materialized views");
    }

    /**
     * Drop the specified materialized view.
     */
    default void dropMaterializedView(ConnectorSession session, SchemaTableName viewName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping materialized views");
    }

    /**
     * List materialized view names, possibly filtered by schema. An empty list is returned if none match.
     * An empty list is returned also when schema name does not refer to an existing schema.
     */
    default List<SchemaTableName> listMaterializedViews(ConnectorSession session, Optional<String> schemaName)
    {
        return List.of();
    }

    /**
     * Gets the definitions of materialized views, possibly filtered by schema.
     * This optional method may be implemented by connectors that can support fetching
     * view data in bulk. It is used to populate {@code information_schema.columns}.
     */
    default Map<SchemaTableName, ConnectorMaterializedViewDefinition> getMaterializedViews(ConnectorSession session, Optional<String> schemaName)
    {
        Map<SchemaTableName, ConnectorMaterializedViewDefinition> materializedViews = new HashMap<>();
        for (SchemaTableName name : listMaterializedViews(session, schemaName)) {
            getMaterializedView(session, name).ifPresent(view -> materializedViews.put(name, view));
        }
        return materializedViews;
    }

    /**
     * Gets the materialized view data for the specified materialized view name. Returns {@link Optional#empty()}
     * if {@code viewName} relation does not or is not a materialized view (e.g. is a table, or a view).
     *
     * @see #getTableHandle(ConnectorSession, SchemaTableName)
     * @see #getView(ConnectorSession, SchemaTableName)
     */
    default Optional<ConnectorMaterializedViewDefinition> getMaterializedView(ConnectorSession session, SchemaTableName viewName)
    {
        return Optional.empty();
    }

    /**
     * The method is used by the engine to determine if a materialized view is current with respect to the tables it depends on.
     *
     * @throws MaterializedViewNotFoundException when materialized view is not found
     */
    default MaterializedViewFreshness getMaterializedViewFreshness(ConnectorSession session, SchemaTableName name)
    {
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "ConnectorMetadata getMaterializedView() is implemented without getMaterializedViewFreshness()");
    }

    /**
     * Rename the specified materialized view
     */
    default void renameMaterializedView(ConnectorSession session, SchemaTableName source, SchemaTableName target)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming materialized views");
    }

    /**
     * Sets the properties of the specified materialized view
     */
    default void setMaterializedViewProperties(ConnectorSession session, SchemaTableName viewName, Map<String, Optional<Object>> properties)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting materialized view properties");
    }

    default Optional<TableScanRedirectApplicationResult> applyTableScanRedirect(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return Optional.empty();
    }

    /**
     * Redirects table to other table which may or may not be in the same catalog.
     * Currently the engine tries to do redirection only for table reads and metadata listing.
     * <p>
     * Also consider implementing streamTableColumns to support redirection for listing.
     */
    default Optional<CatalogSchemaTableName> redirectTable(ConnectorSession session, SchemaTableName tableName)
    {
        return Optional.empty();
    }

    default boolean supportsReportingWrittenBytes(ConnectorSession session, SchemaTableName schemaTableName, Map<String, Object> tableProperties)
    {
        return false;
    }

    default boolean supportsReportingWrittenBytes(ConnectorSession session, ConnectorTableHandle connectorTableHandle)
    {
        return false;
    }
}
