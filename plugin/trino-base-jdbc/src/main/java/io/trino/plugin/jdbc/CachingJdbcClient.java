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
package io.trino.plugin.jdbc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.airlift.jmx.CacheStatsMBean;
import io.airlift.units.Duration;
import io.trino.collect.cache.EvictableCacheBuilder;
import io.trino.plugin.base.session.SessionPropertiesProvider;
import io.trino.plugin.jdbc.IdentityCacheMapping.IdentityCacheKey;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.JoinStatistics;
import io.trino.spi.connector.JoinType;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.connector.TableScanRedirectApplicationResult;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.session.PropertyMetadata;
import io.trino.spi.statistics.TableStatistics;
import io.trino.spi.type.Type;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.plugin.jdbc.BaseJdbcConfig.CACHING_DISABLED;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class CachingJdbcClient
        implements JdbcClient
{
    private static final Object NULL_MARKER = new Object();

    private final JdbcClient delegate;
    private final List<PropertyMetadata<?>> sessionProperties;
    // specifies whether missing values should be cached
    private final boolean cacheMissing;
    private final IdentityCacheMapping identityMapping;

    private final Cache<IdentityCacheKey, Set<String>> schemaNamesCache;
    private final Cache<TableNamesCacheKey, List<SchemaTableName>> tableNamesCache;
    private final Cache<TableHandlesByNameCacheKey, Optional<JdbcTableHandle>> tableHandlesByNameCache;
    private final Cache<TableHandlesByQueryCacheKey, JdbcTableHandle> tableHandlesByQueryCache;
    private final Cache<ColumnsCacheKey, List<JdbcColumnHandle>> columnsCache;
    private final Cache<JdbcTableHandle, TableStatistics> statisticsCache;

    @Inject
    public CachingJdbcClient(
            @StatsCollecting JdbcClient delegate,
            Set<SessionPropertiesProvider> sessionPropertiesProviders,
            IdentityCacheMapping identityMapping,
            BaseJdbcConfig config)
    {
        this(delegate, sessionPropertiesProviders, identityMapping, config.getMetadataCacheTtl(), config.isCacheMissing(), config.getCacheMaximumSize());
    }

    public CachingJdbcClient(
            JdbcClient delegate,
            Set<SessionPropertiesProvider> sessionPropertiesProviders,
            IdentityCacheMapping identityMapping,
            Duration metadataCachingTtl,
            boolean cacheMissing,
            long cacheMaximumSize)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
        this.sessionProperties = requireNonNull(sessionPropertiesProviders, "sessionPropertiesProviders is null").stream()
                .flatMap(provider -> provider.getSessionProperties().stream())
                .collect(toImmutableList());
        this.cacheMissing = cacheMissing;
        this.identityMapping = requireNonNull(identityMapping, "identityMapping is null");

        EvictableCacheBuilder<Object, Object> cacheBuilder = EvictableCacheBuilder.newBuilder()
                .expireAfterWrite(metadataCachingTtl.toMillis(), MILLISECONDS)
                .shareNothingWhenDisabled()
                .recordStats();

        if (metadataCachingTtl.equals(CACHING_DISABLED)) {
            // Disables the cache entirely
            cacheBuilder.maximumSize(0);
        }
        else {
            cacheBuilder.maximumSize(cacheMaximumSize);
        }

        schemaNamesCache = cacheBuilder.build();
        tableNamesCache = cacheBuilder.build();
        tableHandlesByNameCache = cacheBuilder.build();
        tableHandlesByQueryCache = cacheBuilder.build();
        columnsCache = cacheBuilder.build();
        statisticsCache = cacheBuilder.build();
    }

    @Override
    public boolean schemaExists(ConnectorSession session, String schema)
    {
        // this method cannot be delegated as that would bypass the cache
        return getSchemaNames(session).contains(schema);
    }

    @Override
    public Set<String> getSchemaNames(ConnectorSession session)
    {
        IdentityCacheKey key = getIdentityKey(session);
        return get(schemaNamesCache, key, () -> delegate.getSchemaNames(session));
    }

    @Override
    public List<SchemaTableName> getTableNames(ConnectorSession session, Optional<String> schema)
    {
        TableNamesCacheKey key = new TableNamesCacheKey(getIdentityKey(session), schema);
        return get(tableNamesCache, key, () -> delegate.getTableNames(session, schema));
    }

    @Override
    public List<JdbcColumnHandle> getColumns(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        if (tableHandle.getColumns().isPresent()) {
            return tableHandle.getColumns().get();
        }
        ColumnsCacheKey key = new ColumnsCacheKey(getIdentityKey(session), getSessionProperties(session), tableHandle.getRequiredNamedRelation().getSchemaTableName());
        return get(columnsCache, key, () -> delegate.getColumns(session, tableHandle));
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        return delegate.toColumnMapping(session, connection, typeHandle);
    }

    @Override
    public List<ColumnMapping> toColumnMappings(ConnectorSession session, List<JdbcTypeHandle> typeHandles)
    {
        return delegate.toColumnMappings(session, typeHandles);
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        return delegate.toWriteMapping(session, type);
    }

    @Override
    public boolean supportsAggregationPushdown(ConnectorSession session, JdbcTableHandle table, List<AggregateFunction> aggregates, Map<String, ColumnHandle> assignments, List<List<ColumnHandle>> groupingSets)
    {
        return delegate.supportsAggregationPushdown(session, table, aggregates, assignments, groupingSets);
    }

    @Override
    public Optional<JdbcExpression> implementAggregation(ConnectorSession session, AggregateFunction aggregate, Map<String, ColumnHandle> assignments)
    {
        return delegate.implementAggregation(session, aggregate, assignments);
    }

    @Override
    public Optional<String> convertPredicate(ConnectorSession session, ConnectorExpression expression, Map<String, ColumnHandle> assignments)
    {
        return delegate.convertPredicate(session, expression, assignments);
    }

    @Override
    public ConnectorSplitSource getSplits(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        return delegate.getSplits(session, tableHandle);
    }

    @Override
    public Connection getConnection(ConnectorSession session, JdbcSplit split)
            throws SQLException
    {
        return delegate.getConnection(session, split);
    }

    @Override
    public void abortReadConnection(Connection connection, ResultSet resultSet)
            throws SQLException
    {
        delegate.abortReadConnection(connection, resultSet);
    }

    @Override
    public PreparedQuery prepareQuery(
            ConnectorSession session,
            JdbcTableHandle table,
            Optional<List<List<JdbcColumnHandle>>> groupingSets,
            List<JdbcColumnHandle> columns,
            Map<String, String> columnExpressions)
    {
        return delegate.prepareQuery(session, table, groupingSets, columns, columnExpressions);
    }

    @Override
    public PreparedStatement buildSql(ConnectorSession session, Connection connection, JdbcSplit split, JdbcTableHandle table, List<JdbcColumnHandle> columns)
            throws SQLException
    {
        return delegate.buildSql(session, connection, split, table, columns);
    }

    @Override
    public Optional<PreparedQuery> implementJoin(
            ConnectorSession session,
            JoinType joinType,
            PreparedQuery leftSource,
            PreparedQuery rightSource,
            List<JdbcJoinCondition> joinConditions,
            Map<JdbcColumnHandle, String> rightAssignments,
            Map<JdbcColumnHandle, String> leftAssignments,
            JoinStatistics statistics)
    {
        return delegate.implementJoin(session, joinType, leftSource, rightSource, joinConditions, rightAssignments, leftAssignments, statistics);
    }

    @Override
    public boolean supportsTopN(ConnectorSession session, JdbcTableHandle handle, List<JdbcSortItem> sortOrder)
    {
        return delegate.supportsTopN(session, handle, sortOrder);
    }

    @Override
    public boolean isTopNGuaranteed(ConnectorSession session)
    {
        return delegate.isTopNGuaranteed(session);
    }

    @Override
    public boolean supportsLimit()
    {
        return delegate.supportsLimit();
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return delegate.isLimitGuaranteed(session);
    }

    @Override
    public Optional<JdbcTableHandle> getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        TableHandlesByNameCacheKey key = new TableHandlesByNameCacheKey(getIdentityKey(session), schemaTableName);
        Optional<JdbcTableHandle> cachedTableHandle = tableHandlesByNameCache.getIfPresent(key);
        //noinspection OptionalAssignedToNull
        if (cachedTableHandle != null) {
            if (cacheMissing || cachedTableHandle.isPresent()) {
                return cachedTableHandle;
            }
            tableHandlesByNameCache.invalidate(key);
        }
        return get(tableHandlesByNameCache, key, () -> delegate.getTableHandle(session, schemaTableName));
    }

    @Override
    public JdbcTableHandle getTableHandle(ConnectorSession session, PreparedQuery preparedQuery)
    {
        TableHandlesByQueryCacheKey key = new TableHandlesByQueryCacheKey(getIdentityKey(session), preparedQuery);
        return get(tableHandlesByQueryCache, key, () -> delegate.getTableHandle(session, preparedQuery));
    }

    @Override
    public void commitCreateTable(ConnectorSession session, JdbcOutputTableHandle handle)
    {
        delegate.commitCreateTable(session, handle);
        invalidateTableCaches(new SchemaTableName(handle.getSchemaName(), handle.getTableName()));
    }

    @Override
    public JdbcOutputTableHandle beginInsertTable(ConnectorSession session, JdbcTableHandle tableHandle, List<JdbcColumnHandle> columns)
    {
        return delegate.beginInsertTable(session, tableHandle, columns);
    }

    @Override
    public void finishInsertTable(ConnectorSession session, JdbcOutputTableHandle handle)
    {
        delegate.finishInsertTable(session, handle);
        onDataChanged(new SchemaTableName(handle.getSchemaName(), handle.getTableName()));
    }

    @Override
    public void dropTable(ConnectorSession session, JdbcTableHandle jdbcTableHandle)
    {
        delegate.dropTable(session, jdbcTableHandle);
        invalidateTableCaches(jdbcTableHandle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void rollbackCreateTable(ConnectorSession session, JdbcOutputTableHandle handle)
    {
        delegate.rollbackCreateTable(session, handle);
    }

    @Override
    public String buildInsertSql(JdbcOutputTableHandle handle, List<WriteFunction> columnWriters)
    {
        return delegate.buildInsertSql(handle, columnWriters);
    }

    @Override
    public Connection getConnection(ConnectorSession session, JdbcOutputTableHandle handle)
            throws SQLException
    {
        return delegate.getConnection(session, handle);
    }

    @Override
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException
    {
        return delegate.getPreparedStatement(connection, sql);
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, JdbcTableHandle handle, TupleDomain<ColumnHandle> tupleDomain)
    {
        checkArgument(tupleDomain.isAll(), "Unexpected non-ALL constraint: %s", tupleDomain);
        return getTableStatistics(session, handle);
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, JdbcTableHandle handle)
    {
        // TODO depend on Identity when needed
        TableStatistics cachedStatistics = statisticsCache.getIfPresent(handle);
        if (cachedStatistics != null) {
            if (cacheMissing || !cachedStatistics.equals(TableStatistics.empty())) {
                return cachedStatistics;
            }
            statisticsCache.invalidate(handle);
        }
        return get(statisticsCache, handle, () -> delegate.getTableStatistics(session, handle));
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        delegate.createSchema(session, schemaName);
        invalidateSchemasCache();
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        delegate.dropSchema(session, schemaName);
        invalidateSchemasCache();
    }

    @Override
    public void renameSchema(ConnectorSession session, String schemaName, String newSchemaName)
    {
        delegate.renameSchema(session, schemaName, newSchemaName);
        invalidateSchemasCache();
    }

    @Override
    public Optional<String> getTableComment(ResultSet resultSet)
            throws SQLException
    {
        return delegate.getTableComment(resultSet);
    }

    @Override
    public void setTableComment(ConnectorSession session, JdbcTableHandle handle, Optional<String> comment)
    {
        delegate.setTableComment(session, handle, comment);
        invalidateTableCaches(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void setColumnComment(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Optional<String> comment)
    {
        delegate.setColumnComment(session, handle, column, comment);
        invalidateColumnsCache(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void addColumn(ConnectorSession session, JdbcTableHandle handle, ColumnMetadata column)
    {
        delegate.addColumn(session, handle, column);
        invalidateColumnsCache(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void dropColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        delegate.dropColumn(session, handle, column);
        invalidateColumnsCache(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void renameColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        delegate.renameColumn(session, handle, jdbcColumn, newColumnName);
        invalidateColumnsCache(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void renameTable(ConnectorSession session, JdbcTableHandle handle, SchemaTableName newTableName)
    {
        delegate.renameTable(session, handle, newTableName);
        invalidateTableCaches(handle.asPlainTable().getSchemaTableName());
        invalidateTableCaches(newTableName);
    }

    @Override
    public void setTableProperties(ConnectorSession session, JdbcTableHandle handle, Map<String, Optional<Object>> properties)
    {
        delegate.setTableProperties(session, handle, properties);
        invalidateTableCaches(handle.asPlainTable().getSchemaTableName());
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        delegate.createTable(session, tableMetadata);
        invalidateTableCaches(tableMetadata.getTable());
    }

    @Override
    public JdbcOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        return delegate.beginCreateTable(session, tableMetadata);
    }

    @Override
    public Optional<SystemTable> getSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        return delegate.getSystemTable(session, tableName);
    }

    @Override
    public String quoted(String name)
    {
        return delegate.quoted(name);
    }

    @Override
    public String quoted(RemoteTableName remoteTableName)
    {
        return delegate.quoted(remoteTableName);
    }

    @Override
    public Map<String, Object> getTableProperties(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        return delegate.getTableProperties(session, tableHandle);
    }

    @Override
    public Optional<TableScanRedirectApplicationResult> getTableScanRedirection(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        return delegate.getTableScanRedirection(session, tableHandle);
    }

    public void onDataChanged(SchemaTableName table)
    {
        invalidateCache(statisticsCache, key -> key.references(table));
    }

    /**
     * @deprecated {@link JdbcTableHandle}  is not a good representation of the table. For example, we don't want
     * to distinguish between "a plan table" and "table with selected columns", or "a table with a constraint" here.
     * Use {@link #onDataChanged(SchemaTableName)}, which avoids these ambiguities.
     */
    @Deprecated
    public void onDataChanged(JdbcTableHandle handle)
    {
        invalidateCache(statisticsCache, key -> key.equals(handle));
    }

    @Override
    public OptionalLong delete(ConnectorSession session, JdbcTableHandle handle)
    {
        OptionalLong deletedRowsCount = delegate.delete(session, handle);
        onDataChanged(handle.getRequiredNamedRelation().getSchemaTableName());
        return deletedRowsCount;
    }

    @Override
    public void truncateTable(ConnectorSession session, JdbcTableHandle handle)
    {
        delegate.truncateTable(session, handle);
        onDataChanged(handle.getRequiredNamedRelation().getSchemaTableName());
    }

    @Managed
    public void flushCache()
    {
        schemaNamesCache.invalidateAll();
        tableNamesCache.invalidateAll();
        tableHandlesByNameCache.invalidateAll();
        tableHandlesByQueryCache.invalidateAll();
        columnsCache.invalidateAll();
        statisticsCache.invalidateAll();
    }

    private IdentityCacheKey getIdentityKey(ConnectorSession session)
    {
        return identityMapping.getRemoteUserCacheKey(session);
    }

    private Map<String, Object> getSessionProperties(ConnectorSession session)
    {
        return sessionProperties.stream()
                .map(property -> Map.entry(property.getName(), getSessionProperty(session, property)))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Object getSessionProperty(ConnectorSession session, PropertyMetadata<?> property)
    {
        return firstNonNull(session.getProperty(property.getName(), property.getJavaType()), NULL_MARKER);
    }

    private void invalidateSchemasCache()
    {
        schemaNamesCache.invalidateAll();
    }

    private void invalidateTableCaches(SchemaTableName schemaTableName)
    {
        // TODO https://github.com/trinodb/trino/issues/12526: invalidate tableHandlesByNameCache for handles derived from opaque queries
        invalidateColumnsCache(schemaTableName);
        invalidateCache(tableHandlesByNameCache, key -> key.tableName.equals(schemaTableName));
        tableHandlesByQueryCache.invalidateAll();
        invalidateCache(tableNamesCache, key -> key.schemaName.equals(Optional.of(schemaTableName.getSchemaName())));
        invalidateCache(statisticsCache, key -> key.references(schemaTableName));
    }

    private void invalidateColumnsCache(SchemaTableName table)
    {
        invalidateCache(columnsCache, key -> key.table.equals(table));
    }

    @VisibleForTesting
    CacheStats getTableNamesCacheStats()
    {
        return tableNamesCache.stats();
    }

    @VisibleForTesting
    CacheStats getColumnsCacheStats()
    {
        return columnsCache.stats();
    }

    @VisibleForTesting
    CacheStats getStatisticsCacheStats()
    {
        return statisticsCache.stats();
    }

    private static <T, V> void invalidateCache(Cache<T, V> cache, Predicate<T> filterFunction)
    {
        Set<T> cacheKeys = cache.asMap().keySet().stream()
                .filter(filterFunction)
                .collect(toImmutableSet());

        cache.invalidateAll(cacheKeys);
    }

    private static final class ColumnsCacheKey
    {
        private final IdentityCacheKey identity;
        private final SchemaTableName table;
        private final Map<String, Object> sessionProperties;

        private ColumnsCacheKey(IdentityCacheKey identity, Map<String, Object> sessionProperties, SchemaTableName table)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.sessionProperties = ImmutableMap.copyOf(requireNonNull(sessionProperties, "sessionProperties is null"));
            this.table = requireNonNull(table, "table is null");
        }

        public IdentityCacheKey getIdentity()
        {
            return identity;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ColumnsCacheKey that = (ColumnsCacheKey) o;
            return Objects.equals(identity, that.identity) &&
                    Objects.equals(sessionProperties, that.sessionProperties) &&
                    Objects.equals(table, that.table);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, sessionProperties, table);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("identity", identity)
                    .add("sessionProperties", sessionProperties)
                    .add("table", table)
                    .toString();
        }
    }

    private static final class TableHandlesByNameCacheKey
    {
        private final IdentityCacheKey identity;
        private final SchemaTableName tableName;

        private TableHandlesByNameCacheKey(IdentityCacheKey identity, SchemaTableName tableName)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.tableName = requireNonNull(tableName, "tableName is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TableHandlesByNameCacheKey that = (TableHandlesByNameCacheKey) o;
            return Objects.equals(identity, that.identity) &&
                    Objects.equals(tableName, that.tableName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, tableName);
        }
    }

    private static final class TableHandlesByQueryCacheKey
    {
        private final IdentityCacheKey identity;
        private final PreparedQuery preparedQuery;

        private TableHandlesByQueryCacheKey(IdentityCacheKey identity, PreparedQuery preparedQuery)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.preparedQuery = requireNonNull(preparedQuery, "preparedQuery is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TableHandlesByQueryCacheKey that = (TableHandlesByQueryCacheKey) o;
            return Objects.equals(identity, that.identity) &&
                    Objects.equals(preparedQuery, that.preparedQuery);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, preparedQuery);
        }
    }

    private static final class TableNamesCacheKey
    {
        private final IdentityCacheKey identity;
        private final Optional<String> schemaName;

        private TableNamesCacheKey(IdentityCacheKey identity, Optional<String> schemaName)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.schemaName = requireNonNull(schemaName, "schemaName is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TableNamesCacheKey that = (TableNamesCacheKey) o;
            return Objects.equals(identity, that.identity) &&
                    Objects.equals(schemaName, that.schemaName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, schemaName);
        }
    }

    private static <K, V> V get(Cache<K, V> cache, K key, Callable<V> loader)
    {
        try {
            return cache.get(key, loader);
        }
        catch (UncheckedExecutionException e) {
            throwIfInstanceOf(e.getCause(), TrinoException.class);
            throw e;
        }
        catch (ExecutionException e) {
            throwIfInstanceOf(e.getCause(), TrinoException.class);
            throw new UncheckedExecutionException(e);
        }
    }

    @Managed
    @Nested
    public CacheStatsMBean getSchemaNamesStats()
    {
        return new CacheStatsMBean(schemaNamesCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getTableNamesCache()
    {
        return new CacheStatsMBean(tableNamesCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getTableHandlesByNameCache()
    {
        return new CacheStatsMBean(tableHandlesByNameCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getTableHandlesByQueryCache()
    {
        return new CacheStatsMBean(tableHandlesByQueryCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getColumnsCache()
    {
        return new CacheStatsMBean(columnsCache);
    }

    @Managed
    @Nested
    public CacheStatsMBean getStatisticsCache()
    {
        return new CacheStatsMBean(statisticsCache);
    }
}
