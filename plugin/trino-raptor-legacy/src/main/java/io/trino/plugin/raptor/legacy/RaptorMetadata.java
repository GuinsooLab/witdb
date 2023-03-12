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
package io.trino.plugin.raptor.legacy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.plugin.raptor.legacy.metadata.ColumnInfo;
import io.trino.plugin.raptor.legacy.metadata.Distribution;
import io.trino.plugin.raptor.legacy.metadata.MetadataDao;
import io.trino.plugin.raptor.legacy.metadata.ShardDelta;
import io.trino.plugin.raptor.legacy.metadata.ShardInfo;
import io.trino.plugin.raptor.legacy.metadata.ShardManager;
import io.trino.plugin.raptor.legacy.metadata.Table;
import io.trino.plugin.raptor.legacy.metadata.TableColumn;
import io.trino.plugin.raptor.legacy.metadata.ViewResult;
import io.trino.plugin.raptor.legacy.systemtables.ColumnRangesSystemTable;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMergeTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorPartitioningHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTablePartitioning;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.RetryMode;
import io.trino.spi.connector.RowChangeParadigm;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.connector.ViewNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.type.Type;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.toOptional;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.plugin.raptor.legacy.RaptorBucketFunction.validateBucketType;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.BUCKET_NUMBER_COLUMN_NAME;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.SHARD_UUID_COLUMN_NAME;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.SHARD_UUID_COLUMN_TYPE;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.bucketNumberColumnHandle;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.isHiddenColumn;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.mergeRowIdHandle;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.shardUuidColumnHandle;
import static io.trino.plugin.raptor.legacy.RaptorErrorCode.RAPTOR_ERROR;
import static io.trino.plugin.raptor.legacy.RaptorSessionProperties.getExternalBatchId;
import static io.trino.plugin.raptor.legacy.RaptorSessionProperties.getOneSplitPerBucketThreshold;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.BUCKETED_ON_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.BUCKET_COUNT_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.DISTRIBUTION_NAME_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.ORDERING_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.ORGANIZED_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.TEMPORAL_COLUMN_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.getBucketColumns;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.getBucketCount;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.getDistributionName;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.getSortColumns;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.getTemporalColumn;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.isOrganized;
import static io.trino.plugin.raptor.legacy.systemtables.ColumnRangesSystemTable.getSourceTable;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.daoTransaction;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.onDemandDao;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.runIgnoringConstraintViolation;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.runTransaction;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.connector.RetryMode.NO_RETRIES;
import static io.trino.spi.connector.RowChangeParadigm.DELETE_ROW_AND_INSERT_ROW;
import static io.trino.spi.connector.SortOrder.ASC_NULLS_FIRST;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static java.lang.String.format;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

public class RaptorMetadata
        implements ConnectorMetadata
{
    private static final Logger log = Logger.get(RaptorMetadata.class);

    private static final JsonCodec<ShardInfo> SHARD_INFO_CODEC = jsonCodec(ShardInfo.class);
    private static final JsonCodec<ShardDelta> SHARD_DELTA_CODEC = jsonCodec(ShardDelta.class);

    private static final JsonCodec<ConnectorViewDefinition> VIEW_CODEC =
            new JsonCodecFactory(new ObjectMapperProvider()).jsonCodec(ConnectorViewDefinition.class);

    private final Jdbi dbi;
    private final MetadataDao dao;
    private final ShardManager shardManager;
    private final LongConsumer beginDeleteForTableId;

    private final AtomicReference<Long> currentTransactionId = new AtomicReference<>();

    public RaptorMetadata(Jdbi dbi, ShardManager shardManager)
    {
        this(dbi, shardManager, tableId -> {});
    }

    public RaptorMetadata(Jdbi dbi, ShardManager shardManager, LongConsumer beginDeleteForTableId)
    {
        this.dbi = requireNonNull(dbi, "dbi is null");
        this.dao = onDemandDao(dbi, MetadataDao.class);
        this.shardManager = requireNonNull(shardManager, "shardManager is null");
        this.beginDeleteForTableId = requireNonNull(beginDeleteForTableId, "beginDeleteForTableId is null");
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return dao.listSchemaNames();
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return getTableHandle(tableName);
    }

    private RaptorTableHandle getTableHandle(SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        Table table = dao.getTableInformation(tableName.getSchemaName(), tableName.getTableName());
        if (table == null) {
            return null;
        }
        List<TableColumn> tableColumns = dao.listTableColumns(table.getTableId());
        checkArgument(!tableColumns.isEmpty(), "Table '%s' does not have any columns", tableName);

        return new RaptorTableHandle(
                tableName.getSchemaName(),
                tableName.getTableName(),
                table.getTableId(),
                table.getDistributionId(),
                table.getDistributionName(),
                table.getBucketCount(),
                table.isOrganized(),
                TupleDomain.all(),
                table.getDistributionId().map(shardManager::getBucketAssignments));
    }

    @Override
    public Optional<SystemTable> getSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        return getSourceTable(tableName)
                .map(this::getTableHandle)
                .map(handle -> new ColumnRangesSystemTable(handle, dbi));
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        RaptorTableHandle handle = (RaptorTableHandle) tableHandle;
        SchemaTableName tableName = new SchemaTableName(handle.getSchemaName(), handle.getTableName());
        List<TableColumn> tableColumns = dao.listTableColumns(handle.getTableId());
        if (tableColumns.isEmpty()) {
            throw new TableNotFoundException(tableName);
        }

        ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        SortedMap<Integer, String> bucketing = new TreeMap<>();
        SortedMap<Integer, String> ordering = new TreeMap<>();

        for (TableColumn column : tableColumns) {
            if (column.isTemporal()) {
                properties.put(TEMPORAL_COLUMN_PROPERTY, column.getColumnName());
            }
            column.getBucketOrdinal().ifPresent(bucketOrdinal -> bucketing.put(bucketOrdinal, column.getColumnName()));
            column.getSortOrdinal().ifPresent(sortOrdinal -> ordering.put(sortOrdinal, column.getColumnName()));
        }

        if (!bucketing.isEmpty()) {
            properties.put(BUCKETED_ON_PROPERTY, ImmutableList.copyOf(bucketing.values()));
        }
        if (!ordering.isEmpty()) {
            properties.put(ORDERING_PROPERTY, ImmutableList.copyOf(ordering.values()));
        }

        handle.getBucketCount().ifPresent(bucketCount -> properties.put(BUCKET_COUNT_PROPERTY, bucketCount));
        handle.getDistributionName().ifPresent(distributionName -> properties.put(DISTRIBUTION_NAME_PROPERTY, distributionName));
        // Only display organization property if set
        if (handle.isOrganized()) {
            properties.put(ORGANIZED_PROPERTY, true);
        }

        List<ColumnMetadata> columns = tableColumns.stream()
                .map(TableColumn::toColumnMetadata)
                .collect(toCollection(ArrayList::new));

        columns.add(hiddenColumn(SHARD_UUID_COLUMN_NAME, SHARD_UUID_COLUMN_TYPE));

        if (handle.isBucketed()) {
            columns.add(hiddenColumn(BUCKET_NUMBER_COLUMN_NAME, INTEGER));
        }

        return new ConnectorTableMetadata(tableName, columns, properties.buildOrThrow());
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        // Deduplicate with set because state may change concurrently
        return ImmutableSet.<SchemaTableName>builder()
                .addAll(dao.listTables(schemaName.orElse(null)))
                .addAll(listViews(session, schemaName))
                .build().asList();
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        ImmutableMap.Builder<String, ColumnHandle> builder = ImmutableMap.builder();
        for (TableColumn tableColumn : dao.listTableColumns(raptorTableHandle.getTableId())) {
            builder.put(tableColumn.getColumnName(), getRaptorColumnHandle(tableColumn));
        }

        RaptorColumnHandle uuidColumn = shardUuidColumnHandle();
        builder.put(uuidColumn.getColumnName(), uuidColumn);

        if (raptorTableHandle.isBucketed()) {
            RaptorColumnHandle bucketNumberColumn = bucketNumberColumnHandle();
            builder.put(bucketNumberColumn.getColumnName(), bucketNumberColumn);
        }

        return builder.buildOrThrow();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        RaptorColumnHandle column = (RaptorColumnHandle) columnHandle;

        if (isHiddenColumn(column.getColumnId())) {
            return hiddenColumn(column.getColumnName(), column.getColumnType());
        }

        return new ColumnMetadata(column.getColumnName(), column.getColumnType());
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");

        ImmutableListMultimap.Builder<SchemaTableName, ColumnMetadata> columns = ImmutableListMultimap.builder();
        for (TableColumn tableColumn : dao.listTableColumns(prefix.getSchema().orElse(null), prefix.getTable().orElse(null))) {
            ColumnMetadata columnMetadata = new ColumnMetadata(tableColumn.getColumnName(), tableColumn.getDataType());
            columns.put(tableColumn.getTable(), columnMetadata);
        }
        return Multimaps.asMap(columns.build());
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
    {
        RaptorTableHandle table = (RaptorTableHandle) handle;
        TupleDomain<RaptorColumnHandle> newDomain = constraint.getSummary().transformKeys(RaptorColumnHandle.class::cast);

        if (newDomain.equals(table.getConstraint())) {
            return Optional.empty();
        }

        return Optional.of(new ConstraintApplicationResult<>(
                new RaptorTableHandle(table.getSchemaName(),
                        table.getTableName(),
                        table.getTableId(),
                        table.getDistributionId(),
                        table.getDistributionName(),
                        table.getBucketCount(),
                        table.isOrganized(),
                        newDomain.intersect(table.getConstraint()),
                        table.getBucketAssignments()),
                constraint.getSummary(),
                false));
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle handle)
    {
        RaptorTableHandle table = (RaptorTableHandle) handle;

        if (table.getPartitioningHandle().isEmpty()) {
            return new ConnectorTableProperties();
        }

        List<RaptorColumnHandle> bucketColumnHandles = getBucketColumnHandles(table.getTableId());

        RaptorPartitioningHandle partitioning = table.getPartitioningHandle().get();

        boolean oneSplitPerBucket = table.getBucketCount().getAsInt() >= getOneSplitPerBucketThreshold(session);

        return new ConnectorTableProperties(
                TupleDomain.all(),
                Optional.of(new ConnectorTablePartitioning(
                        partitioning,
                        ImmutableList.copyOf(bucketColumnHandles))),
                oneSplitPerBucket ? Optional.of(ImmutableSet.copyOf(bucketColumnHandles)) : Optional.empty(),
                Optional.empty(),
                ImmutableList.of());
    }

    @Override
    public Optional<ConnectorTableLayout> getNewTableLayout(ConnectorSession session, ConnectorTableMetadata metadata)
    {
        ImmutableMap.Builder<String, RaptorColumnHandle> map = ImmutableMap.builder();
        long columnId = 1;
        for (ColumnMetadata column : metadata.getColumns()) {
            map.put(column.getName(), new RaptorColumnHandle(column.getName(), columnId, column.getType()));
            columnId++;
        }

        Optional<DistributionInfo> distribution = getOrCreateDistribution(map.buildOrThrow(), metadata.getProperties());
        if (distribution.isEmpty()) {
            return Optional.empty();
        }

        List<String> partitionColumns = distribution.get().getBucketColumns().stream()
                .map(RaptorColumnHandle::getColumnName)
                .collect(toList());

        long distributionId = distribution.get().getDistributionId();
        List<String> bucketAssignments = shardManager.getBucketAssignments(distributionId);
        ConnectorPartitioningHandle partitioning = new RaptorPartitioningHandle(distributionId, bucketAssignments);

        return Optional.of(new ConnectorTableLayout(partitioning, partitionColumns));
    }

    private Optional<DistributionInfo> getOrCreateDistribution(Map<String, RaptorColumnHandle> columnHandleMap, Map<String, Object> properties)
    {
        OptionalInt bucketCount = getBucketCount(properties);
        List<RaptorColumnHandle> bucketColumnHandles = getBucketColumnHandles(getBucketColumns(properties), columnHandleMap);

        if (bucketCount.isPresent() && bucketColumnHandles.isEmpty()) {
            throw new TrinoException(INVALID_TABLE_PROPERTY, format("Must specify '%s' along with '%s'", BUCKETED_ON_PROPERTY, BUCKET_COUNT_PROPERTY));
        }
        if (bucketCount.isEmpty() && !bucketColumnHandles.isEmpty()) {
            throw new TrinoException(INVALID_TABLE_PROPERTY, format("Must specify '%s' along with '%s'", BUCKET_COUNT_PROPERTY, BUCKETED_ON_PROPERTY));
        }
        ImmutableList.Builder<Type> bucketColumnTypes = ImmutableList.builder();
        for (RaptorColumnHandle column : bucketColumnHandles) {
            validateBucketType(column.getColumnType());
            bucketColumnTypes.add(column.getColumnType());
        }

        long distributionId;
        String distributionName = getDistributionName(properties);
        if (distributionName != null) {
            if (bucketColumnHandles.isEmpty()) {
                throw new TrinoException(INVALID_TABLE_PROPERTY, format("Must specify '%s' along with '%s'", BUCKETED_ON_PROPERTY, DISTRIBUTION_NAME_PROPERTY));
            }

            Distribution distribution = dao.getDistribution(distributionName);
            if (distribution == null) {
                if (bucketCount.isEmpty()) {
                    throw new TrinoException(INVALID_TABLE_PROPERTY, "Distribution does not exist and bucket count is not specified");
                }
                distribution = getOrCreateDistribution(distributionName, bucketColumnTypes.build(), bucketCount.getAsInt());
            }
            distributionId = distribution.getId();

            if (bucketCount.isPresent() && (distribution.getBucketCount() != bucketCount.getAsInt())) {
                throw new TrinoException(INVALID_TABLE_PROPERTY, "Bucket count must match distribution");
            }
            if (!distribution.getColumnTypes().equals(bucketColumnTypes.build())) {
                throw new TrinoException(INVALID_TABLE_PROPERTY, "Bucket column types must match distribution");
            }
        }
        else if (bucketCount.isPresent()) {
            String types = Distribution.serializeColumnTypes(bucketColumnTypes.build());
            distributionId = dao.insertDistribution(null, types, bucketCount.getAsInt());
        }
        else {
            return Optional.empty();
        }

        shardManager.createBuckets(distributionId, bucketCount.getAsInt());

        return Optional.of(new DistributionInfo(distributionId, bucketCount.getAsInt(), bucketColumnHandles));
    }

    private Distribution getOrCreateDistribution(String name, List<Type> columnTypes, int bucketCount)
    {
        String types = Distribution.serializeColumnTypes(columnTypes);
        runIgnoringConstraintViolation(() -> dao.insertDistribution(name, types, bucketCount));

        Distribution distribution = dao.getDistribution(name);
        if (distribution == null) {
            throw new TrinoException(RAPTOR_ERROR, "Distribution does not exist after insert");
        }
        return distribution;
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        Optional<ConnectorTableLayout> layout = getNewTableLayout(session, tableMetadata);
        finishCreateTable(session, beginCreateTable(session, tableMetadata, layout, NO_RETRIES), ImmutableList.of(), ImmutableList.of());
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        RaptorTableHandle raptorHandle = (RaptorTableHandle) tableHandle;
        shardManager.dropTable(raptorHandle.getTableId());
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        RaptorTableHandle table = (RaptorTableHandle) tableHandle;
        runTransaction(dbi, handle -> {
            MetadataDao dao = handle.attach(MetadataDao.class);
            dao.renameTable(table.getTableId(), newTableName.getSchemaName(), newTableName.getTableName());
            return null;
        });
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        if (column.getComment() != null) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns with comments");
        }

        RaptorTableHandle table = (RaptorTableHandle) tableHandle;

        // Always add new columns to the end.
        List<TableColumn> existingColumns = dao.listTableColumns(table.getSchemaName(), table.getTableName());
        TableColumn lastColumn = existingColumns.get(existingColumns.size() - 1);
        long columnId = lastColumn.getColumnId() + 1;
        int ordinalPosition = lastColumn.getOrdinalPosition() + 1;

        String type = column.getType().getTypeId().getId();
        daoTransaction(dbi, MetadataDao.class, dao -> {
            dao.insertColumn(table.getTableId(), columnId, column.getName(), ordinalPosition, type, null, null);
            dao.updateTableVersion(table.getTableId(), session.getStart().toEpochMilli());
        });

        shardManager.addColumn(table.getTableId(), new ColumnInfo(columnId, column.getType()));
    }

    @Override
    public void renameColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle source, String target)
    {
        RaptorTableHandle table = (RaptorTableHandle) tableHandle;
        RaptorColumnHandle sourceColumn = (RaptorColumnHandle) source;
        daoTransaction(dbi, MetadataDao.class, dao -> {
            dao.renameColumn(table.getTableId(), sourceColumn.getColumnId(), target);
            dao.updateTableVersion(table.getTableId(), session.getStart().toEpochMilli());
        });
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        RaptorTableHandle table = (RaptorTableHandle) tableHandle;
        RaptorColumnHandle raptorColumn = (RaptorColumnHandle) column;

        List<TableColumn> existingColumns = dao.listTableColumns(table.getSchemaName(), table.getTableName());
        if (existingColumns.size() <= 1) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot drop the only column in a table");
        }
        long maxColumnId = existingColumns.stream().mapToLong(TableColumn::getColumnId).max().getAsLong();
        if (raptorColumn.getColumnId() == maxColumnId) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot drop the column which has the largest column ID in the table");
        }

        if (getBucketColumnHandles(table.getTableId()).contains(column)) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot drop bucket columns");
        }

        Optional.ofNullable(dao.getTemporalColumnId(table.getTableId())).ifPresent(tempColumnId -> {
            if (raptorColumn.getColumnId() == tempColumnId) {
                throw new TrinoException(NOT_SUPPORTED, "Cannot drop the temporal column");
            }
        });

        if (getSortColumnHandles(table.getTableId()).contains(raptorColumn)) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot drop sort columns");
        }

        daoTransaction(dbi, MetadataDao.class, dao -> {
            dao.dropColumn(table.getTableId(), raptorColumn.getColumnId());
            dao.updateTableVersion(table.getTableId(), session.getStart().toEpochMilli());
        });

        // TODO: drop column from index table
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorTableLayout> layout, RetryMode retryMode)
    {
        if (retryMode != NO_RETRIES) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support query retries");
        }
        if (tableMetadata.getComment().isPresent()) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables with table comment");
        }

        if (viewExists(session, tableMetadata.getTable())) {
            throw new TrinoException(ALREADY_EXISTS, "View already exists: " + tableMetadata.getTable());
        }

        Optional<RaptorPartitioningHandle> partitioning = layout
                .map(ConnectorTableLayout::getPartitioning)
                .map(Optional::get)
                .map(RaptorPartitioningHandle.class::cast);

        ImmutableList.Builder<RaptorColumnHandle> columnHandles = ImmutableList.builder();
        ImmutableList.Builder<Type> columnTypes = ImmutableList.builder();

        long columnId = 1;
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.getComment() != null) {
                throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables with column comment");
            }
            columnHandles.add(new RaptorColumnHandle(column.getName(), columnId, column.getType()));
            columnTypes.add(column.getType());
            columnId++;
        }
        Map<String, RaptorColumnHandle> columnHandleMap = Maps.uniqueIndex(columnHandles.build(), RaptorColumnHandle::getColumnName);

        List<RaptorColumnHandle> sortColumnHandles = getSortColumnHandles(getSortColumns(tableMetadata.getProperties()), columnHandleMap);
        Optional<RaptorColumnHandle> temporalColumnHandle = getTemporalColumnHandle(getTemporalColumn(tableMetadata.getProperties()), columnHandleMap);

        if (temporalColumnHandle.isPresent()) {
            RaptorColumnHandle column = temporalColumnHandle.get();
            if (!column.getColumnType().equals(TIMESTAMP_MILLIS) && !column.getColumnType().equals(DATE)) {
                throw new TrinoException(NOT_SUPPORTED, "Temporal column must be of type timestamp or date: " + column.getColumnName());
            }
        }

        boolean organized = isOrganized(tableMetadata.getProperties());
        if (organized) {
            if (temporalColumnHandle.isPresent()) {
                throw new TrinoException(NOT_SUPPORTED, "Table with temporal columns cannot be organized");
            }
            if (sortColumnHandles.isEmpty()) {
                throw new TrinoException(NOT_SUPPORTED, "Table organization requires an ordering");
            }
        }

        long transactionId = shardManager.beginTransaction();

        setTransactionId(transactionId);

        Optional<DistributionInfo> distribution = partitioning.map(handle ->
                getDistributionInfo(handle.getDistributionId(), columnHandleMap, tableMetadata.getProperties()));

        return new RaptorOutputTableHandle(
                transactionId,
                tableMetadata.getTable().getSchemaName(),
                tableMetadata.getTable().getTableName(),
                columnHandles.build(),
                columnTypes.build(),
                sortColumnHandles,
                nCopies(sortColumnHandles.size(), ASC_NULLS_FIRST),
                temporalColumnHandle,
                distribution.map(info -> OptionalLong.of(info.getDistributionId())).orElse(OptionalLong.empty()),
                distribution.map(info -> OptionalInt.of(info.getBucketCount())).orElse(OptionalInt.empty()),
                organized,
                distribution.map(DistributionInfo::getBucketColumns).orElse(ImmutableList.of()));
    }

    private DistributionInfo getDistributionInfo(long distributionId, Map<String, RaptorColumnHandle> columnHandleMap, Map<String, Object> properties)
    {
        Distribution distribution = dao.getDistribution(distributionId);
        if (distribution == null) {
            throw new TrinoException(RAPTOR_ERROR, "Distribution ID does not exist: " + distributionId);
        }
        List<RaptorColumnHandle> bucketColumnHandles = getBucketColumnHandles(getBucketColumns(properties), columnHandleMap);
        return new DistributionInfo(distributionId, distribution.getBucketCount(), bucketColumnHandles);
    }

    private static Optional<RaptorColumnHandle> getTemporalColumnHandle(String temporalColumn, Map<String, RaptorColumnHandle> columnHandleMap)
    {
        if (temporalColumn == null) {
            return Optional.empty();
        }

        RaptorColumnHandle handle = columnHandleMap.get(temporalColumn);
        if (handle == null) {
            throw new TrinoException(NOT_FOUND, "Temporal column does not exist: " + temporalColumn);
        }
        return Optional.of(handle);
    }

    private static List<RaptorColumnHandle> getSortColumnHandles(List<String> sortColumns, Map<String, RaptorColumnHandle> columnHandleMap)
    {
        ImmutableList.Builder<RaptorColumnHandle> columnHandles = ImmutableList.builder();
        for (String column : sortColumns) {
            if (!columnHandleMap.containsKey(column)) {
                throw new TrinoException(NOT_FOUND, "Ordering column does not exist: " + column);
            }
            columnHandles.add(columnHandleMap.get(column));
        }
        return columnHandles.build();
    }

    private static List<RaptorColumnHandle> getBucketColumnHandles(List<String> bucketColumns, Map<String, RaptorColumnHandle> columnHandleMap)
    {
        ImmutableList.Builder<RaptorColumnHandle> columnHandles = ImmutableList.builder();
        for (String column : bucketColumns) {
            if (!columnHandleMap.containsKey(column)) {
                throw new TrinoException(NOT_FOUND, "Bucketing column does not exist: " + column);
            }
            columnHandles.add(columnHandleMap.get(column));
        }
        return columnHandles.build();
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle outputTableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        RaptorOutputTableHandle table = (RaptorOutputTableHandle) outputTableHandle;
        long transactionId = table.getTransactionId();
        long updateTime = session.getStart().toEpochMilli();

        long newTableId = runTransaction(dbi, dbiHandle -> {
            MetadataDao dao = dbiHandle.attach(MetadataDao.class);

            Long distributionId = table.getDistributionId().isPresent() ? table.getDistributionId().getAsLong() : null;
            // TODO: update default value of organization_enabled to true
            long tableId = dao.insertTable(table.getSchemaName(), table.getTableName(), true, table.isOrganized(), distributionId, updateTime);

            List<RaptorColumnHandle> sortColumnHandles = table.getSortColumnHandles();
            List<RaptorColumnHandle> bucketColumnHandles = table.getBucketColumnHandles();

            for (int i = 0; i < table.getColumnTypes().size(); i++) {
                RaptorColumnHandle column = table.getColumnHandles().get(i);

                int columnId = i + 1;
                String type = table.getColumnTypes().get(i).getTypeId().getId();
                Integer sortPosition = sortColumnHandles.contains(column) ? sortColumnHandles.indexOf(column) : null;
                Integer bucketPosition = bucketColumnHandles.contains(column) ? bucketColumnHandles.indexOf(column) : null;

                dao.insertColumn(tableId, columnId, column.getColumnName(), i, type, sortPosition, bucketPosition);

                if (table.getTemporalColumnHandle().isPresent() && table.getTemporalColumnHandle().get().equals(column)) {
                    dao.updateTemporalColumnId(tableId, columnId);
                }
            }

            return tableId;
        });

        List<ColumnInfo> columns = table.getColumnHandles().stream().map(ColumnInfo::fromHandle).collect(toList());

        OptionalLong temporalColumnId = table.getTemporalColumnHandle().map(RaptorColumnHandle::getColumnId)
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());

        // TODO: refactor this to avoid creating an empty table on failure
        shardManager.createTable(newTableId, columns, table.getBucketCount().isPresent(), temporalColumnId);
        shardManager.commitShards(transactionId, newTableId, columns, parseFragments(fragments), Optional.empty(), updateTime);

        clearRollback();

        return Optional.empty();
    }

    @Override
    public RaptorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns, RetryMode retryMode)
    {
        if (retryMode != NO_RETRIES) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support query retries");
        }

        RaptorTableHandle handle = (RaptorTableHandle) tableHandle;
        long tableId = handle.getTableId();

        ImmutableList.Builder<RaptorColumnHandle> columnHandlesBuilder = ImmutableList.builder();
        ImmutableList.Builder<Type> columnTypes = ImmutableList.builder();
        for (TableColumn column : dao.listTableColumns(tableId)) {
            columnHandlesBuilder.add(new RaptorColumnHandle(column.getColumnName(), column.getColumnId(), column.getDataType()));
            columnTypes.add(column.getDataType());
        }

        long transactionId = shardManager.beginTransaction();

        setTransactionId(transactionId);

        Optional<String> externalBatchId = getExternalBatchId(session);
        List<RaptorColumnHandle> sortColumnHandles = getSortColumnHandles(tableId);
        List<RaptorColumnHandle> bucketColumnHandles = getBucketColumnHandles(tableId);

        ImmutableList<RaptorColumnHandle> columnHandles = columnHandlesBuilder.build();
        Optional<RaptorColumnHandle> temporalColumnHandle = Optional.ofNullable(dao.getTemporalColumnId(tableId))
                .map(temporalColumnId -> getOnlyElement(columnHandles.stream()
                        .filter(columnHandle -> columnHandle.getColumnId() == temporalColumnId)
                        .collect(toList())));

        return new RaptorInsertTableHandle(
                transactionId,
                tableId,
                columnHandles,
                columnTypes.build(),
                externalBatchId,
                sortColumnHandles,
                nCopies(sortColumnHandles.size(), ASC_NULLS_FIRST),
                handle.getBucketCount(),
                bucketColumnHandles,
                temporalColumnHandle);
    }

    private List<RaptorColumnHandle> getSortColumnHandles(long tableId)
    {
        return dao.listSortColumns(tableId).stream()
                .map(this::getRaptorColumnHandle)
                .collect(toList());
    }

    private List<RaptorColumnHandle> getBucketColumnHandles(long tableId)
    {
        return dao.listBucketColumns(tableId).stream()
                .map(this::getRaptorColumnHandle)
                .collect(toList());
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        RaptorInsertTableHandle handle = (RaptorInsertTableHandle) insertHandle;
        long transactionId = handle.getTransactionId();
        long tableId = handle.getTableId();
        Optional<String> externalBatchId = handle.getExternalBatchId();
        List<ColumnInfo> columns = handle.getColumnHandles().stream().map(ColumnInfo::fromHandle).collect(toList());
        long updateTime = session.getStart().toEpochMilli();

        Collection<ShardInfo> shards = parseFragments(fragments);
        log.info("Committing insert into tableId %s (queryId: %s, shards: %s, columns: %s)", handle.getTableId(), session.getQueryId(), shards.size(), columns.size());
        shardManager.commitShards(transactionId, tableId, columns, shards, externalBatchId, updateTime);

        clearRollback();

        return Optional.empty();
    }

    private void finishDelete(ConnectorSession session, RaptorTableHandle tableHandle, long transactionId, Collection<Slice> fragments)
    {
        long tableId = tableHandle.getTableId();

        List<ColumnInfo> columns = getColumnHandles(session, tableHandle).values().stream()
                .map(RaptorColumnHandle.class::cast)
                .map(ColumnInfo::fromHandle).collect(toList());

        Set<UUID> oldShardUuids = new HashSet<>();
        List<ShardInfo> newShards = new ArrayList<>();

        for (Slice fragment : fragments) {
            ShardDelta delta = SHARD_DELTA_CODEC.fromJson(fragment.getBytes());
            for (UUID uuid : delta.getOldShardUuids()) {
                verify(oldShardUuids.add(uuid), "duplicate old shard: %s", uuid);
            }
            newShards.addAll(delta.getNewShards());
        }

        OptionalLong updateTime = OptionalLong.of(session.getStart().toEpochMilli());

        log.info("Finishing update for tableId %s (removed: %s, new: %s)", tableId, oldShardUuids.size(), newShards.size());
        shardManager.replaceShardUuids(transactionId, tableId, columns, oldShardUuids, newShards, updateTime);

        clearRollback();
    }

    @Override
    public RowChangeParadigm getRowChangeParadigm(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return DELETE_ROW_AND_INSERT_ROW;
    }

    @Override
    public ColumnHandle getMergeRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return mergeRowIdHandle();
    }

    @Override
    public Optional<ConnectorPartitioningHandle> getUpdateLayout(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return ((RaptorTableHandle) tableHandle).getDistributionId().<ConnectorPartitioningHandle>map(distributionId ->
                        new RaptorBucketedUpdateHandle(distributionId, shardManager.getBucketAssignments(distributionId)))
                .or(() -> Optional.of(RaptorUnbucketedUpdateHandle.INSTANCE));
    }

    @Override
    public ConnectorMergeTableHandle beginMerge(ConnectorSession session, ConnectorTableHandle tableHandle, RetryMode retryMode)
    {
        RaptorTableHandle handle = (RaptorTableHandle) tableHandle;

        beginDeleteForTableId.accept(handle.getTableId());

        RaptorInsertTableHandle insertHandle = beginInsert(session, handle, ImmutableList.of(), retryMode);

        return new RaptorMergeTableHandle(handle, insertHandle);
    }

    @Override
    public void finishMerge(ConnectorSession session, ConnectorMergeTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        RaptorMergeTableHandle handle = (RaptorMergeTableHandle) tableHandle;
        long transactionId = handle.getInsertTableHandle().getTransactionId();
        finishDelete(session, handle.getTableHandle(), transactionId, fragments);
    }

    @Override
    public void createView(ConnectorSession session, SchemaTableName viewName, ConnectorViewDefinition definition, boolean replace)
    {
        String schemaName = viewName.getSchemaName();
        String tableName = viewName.getTableName();
        String viewData = VIEW_CODEC.toJson(definition);

        if (getTableHandle(viewName) != null) {
            throw new TrinoException(ALREADY_EXISTS, "Table already exists: " + viewName);
        }

        if (replace) {
            daoTransaction(dbi, MetadataDao.class, dao -> {
                dao.dropView(schemaName, tableName);
                dao.insertView(schemaName, tableName, viewData);
            });
            return;
        }

        try {
            dao.insertView(schemaName, tableName, viewData);
        }
        catch (TrinoException e) {
            if (viewExists(session, viewName)) {
                throw new TrinoException(ALREADY_EXISTS, "View already exists: " + viewName);
            }
            throw e;
        }
    }

    @Override
    public void dropView(ConnectorSession session, SchemaTableName viewName)
    {
        if (!viewExists(session, viewName)) {
            throw new ViewNotFoundException(viewName);
        }
        dao.dropView(viewName.getSchemaName(), viewName.getTableName());
    }

    @Override
    public List<SchemaTableName> listViews(ConnectorSession session, Optional<String> schemaName)
    {
        return dao.listViews(schemaName.orElse(null));
    }

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews(ConnectorSession session, Optional<String> schemaName)
    {
        ImmutableMap.Builder<SchemaTableName, ConnectorViewDefinition> map = ImmutableMap.builder();
        for (ViewResult view : dao.getViews(schemaName.orElse(null), null)) {
            map.put(view.getName(), VIEW_CODEC.fromJson(view.getData()));
        }
        return map.buildOrThrow();
    }

    @Override
    public Optional<ConnectorViewDefinition> getView(ConnectorSession session, SchemaTableName viewName)
    {
        return dao.getViews(viewName.getSchemaName(), viewName.getTableName()).stream()
                .map(view -> VIEW_CODEC.fromJson(view.getData()))
                .collect(toOptional());
    }

    private boolean viewExists(ConnectorSession session, SchemaTableName viewName)
    {
        return getView(session, viewName).isPresent();
    }

    private RaptorColumnHandle getRaptorColumnHandle(TableColumn tableColumn)
    {
        return new RaptorColumnHandle(tableColumn.getColumnName(), tableColumn.getColumnId(), tableColumn.getDataType());
    }

    private static Collection<ShardInfo> parseFragments(Collection<Slice> fragments)
    {
        return fragments.stream()
                .map(fragment -> SHARD_INFO_CODEC.fromJson(fragment.getBytes()))
                .collect(toList());
    }

    private static ColumnMetadata hiddenColumn(String name, Type type)
    {
        return ColumnMetadata.builder()
                .setName(name)
                .setType(type)
                .setHidden(true)
                .build();
    }

    private void setTransactionId(long transactionId)
    {
        checkState(currentTransactionId.compareAndSet(null, transactionId), "current transaction ID already set");
    }

    private void clearRollback()
    {
        currentTransactionId.set(null);
    }

    public void rollback()
    {
        Long transactionId = currentTransactionId.getAndSet(null);
        if (transactionId != null) {
            shardManager.rollbackTransaction(transactionId);
        }
    }

    private static class DistributionInfo
    {
        private final long distributionId;
        private final int bucketCount;
        private final List<RaptorColumnHandle> bucketColumns;

        public DistributionInfo(long distributionId, int bucketCount, List<RaptorColumnHandle> bucketColumns)
        {
            this.distributionId = distributionId;
            this.bucketCount = bucketCount;
            this.bucketColumns = ImmutableList.copyOf(requireNonNull(bucketColumns, "bucketColumns is null"));
        }

        public long getDistributionId()
        {
            return distributionId;
        }

        public int getBucketCount()
        {
            return bucketCount;
        }

        public List<RaptorColumnHandle> getBucketColumns()
        {
            return bucketColumns;
        }
    }
}
