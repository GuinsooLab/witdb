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
package io.trino.plugin.deltalake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.stats.cardinality.HyperLogLog;
import io.airlift.units.DataSize;
import io.trino.plugin.deltalake.metastore.DeltaLakeMetastore;
import io.trino.plugin.deltalake.metastore.NotADeltaLakeTableException;
import io.trino.plugin.deltalake.procedure.DeltaLakeTableExecuteHandle;
import io.trino.plugin.deltalake.procedure.DeltaLakeTableProcedureId;
import io.trino.plugin.deltalake.procedure.DeltaTableOptimizeHandle;
import io.trino.plugin.deltalake.statistics.DeltaLakeColumnStatistics;
import io.trino.plugin.deltalake.statistics.ExtendedStatistics;
import io.trino.plugin.deltalake.statistics.ExtendedStatisticsAccess;
import io.trino.plugin.deltalake.transactionlog.AddFileEntry;
import io.trino.plugin.deltalake.transactionlog.CommitInfoEntry;
import io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.ColumnMappingMode;
import io.trino.plugin.deltalake.transactionlog.MetadataEntry;
import io.trino.plugin.deltalake.transactionlog.MetadataEntry.Format;
import io.trino.plugin.deltalake.transactionlog.ProtocolEntry;
import io.trino.plugin.deltalake.transactionlog.RemoveFileEntry;
import io.trino.plugin.deltalake.transactionlog.TableSnapshot;
import io.trino.plugin.deltalake.transactionlog.checkpoint.CheckpointWriterManager;
import io.trino.plugin.deltalake.transactionlog.statistics.DeltaLakeFileStatistics;
import io.trino.plugin.deltalake.transactionlog.writer.TransactionConflictException;
import io.trino.plugin.deltalake.transactionlog.writer.TransactionLogWriter;
import io.trino.plugin.deltalake.transactionlog.writer.TransactionLogWriterFactory;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.HdfsEnvironment.HdfsContext;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.TableAlreadyExistsException;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.Database;
import io.trino.plugin.hive.metastore.HivePrincipal;
import io.trino.plugin.hive.metastore.PrincipalPrivileges;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.plugin.hive.metastore.Table;
import io.trino.plugin.hive.security.AccessControlMetadata;
import io.trino.spi.NodeManager;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.connector.Assignment;
import io.trino.spi.connector.BeginTableExecuteResult;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorAnalyzeMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableExecuteHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.ProjectionApplicationResult;
import io.trino.spi.connector.RetryMode;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.TableColumnsMetadata;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.connector.TableScanRedirectApplicationResult;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.GrantInfo;
import io.trino.spi.security.Privilege;
import io.trino.spi.security.RoleGrant;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.statistics.ColumnStatisticMetadata;
import io.trino.spi.statistics.ColumnStatisticType;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.statistics.TableStatistics;
import io.trino.spi.statistics.TableStatisticsMetadata;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.FixedWidthType;
import io.trino.spi.type.HyperLogLogType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.VarcharType;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import javax.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.FILE_MODIFIED_TIME_COLUMN_NAME;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.ROW_ID_COLUMN_NAME;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.ROW_ID_COLUMN_TYPE;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.fileModifiedTimeColumnHandle;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.fileSizeColumnHandle;
import static io.trino.plugin.deltalake.DeltaLakeColumnHandle.pathColumnHandle;
import static io.trino.plugin.deltalake.DeltaLakeColumnType.PARTITION_KEY;
import static io.trino.plugin.deltalake.DeltaLakeColumnType.REGULAR;
import static io.trino.plugin.deltalake.DeltaLakeColumnType.SYNTHESIZED;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_BAD_WRITE;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_INVALID_SCHEMA;
import static io.trino.plugin.deltalake.DeltaLakeSessionProperties.isExtendedStatisticsEnabled;
import static io.trino.plugin.deltalake.DeltaLakeSessionProperties.isTableStatisticsEnabled;
import static io.trino.plugin.deltalake.DeltaLakeTableProperties.CHECKPOINT_INTERVAL_PROPERTY;
import static io.trino.plugin.deltalake.DeltaLakeTableProperties.LOCATION_PROPERTY;
import static io.trino.plugin.deltalake.DeltaLakeTableProperties.PARTITIONED_BY_PROPERTY;
import static io.trino.plugin.deltalake.DeltaLakeTableProperties.getLocation;
import static io.trino.plugin.deltalake.DeltaLakeTableProperties.getPartitionedBy;
import static io.trino.plugin.deltalake.metastore.HiveMetastoreBackedDeltaLakeMetastore.TABLE_PROVIDER_PROPERTY;
import static io.trino.plugin.deltalake.metastore.HiveMetastoreBackedDeltaLakeMetastore.TABLE_PROVIDER_VALUE;
import static io.trino.plugin.deltalake.procedure.DeltaLakeTableProcedureId.OPTIMIZE;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.APPEND_ONLY_CONFIGURATION_KEY;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.COLUMN_MAPPING_MODE_CONFIGURATION_KEY;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.extractColumnMetadata;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.extractPartitionColumns;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.extractSchema;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.getColumnComments;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.getColumnMappingMode;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.isAppendOnly;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.serializeSchemaAsJson;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.serializeStatsAsJson;
import static io.trino.plugin.deltalake.transactionlog.DeltaLakeSchemaSupport.validateType;
import static io.trino.plugin.deltalake.transactionlog.MetadataEntry.buildDeltaMetadataConfiguration;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogParser.getMandatoryCurrentVersion;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogUtil.getTransactionLogDir;
import static io.trino.plugin.hive.HiveMetadata.PRESTO_QUERY_ID_NAME;
import static io.trino.plugin.hive.metastore.MetastoreUtil.buildInitialPrivilegeSet;
import static io.trino.plugin.hive.metastore.StorageFormat.create;
import static io.trino.plugin.hive.util.HiveUtil.isDeltaLakeTable;
import static io.trino.plugin.hive.util.HiveUtil.isHiveSystemSchema;
import static io.trino.plugin.hive.util.HiveWriteUtils.createDirectory;
import static io.trino.plugin.hive.util.HiveWriteUtils.isS3FileSystem;
import static io.trino.plugin.hive.util.HiveWriteUtils.pathExists;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ANALYZE_PROPERTY;
import static io.trino.spi.StandardErrorCode.INVALID_SCHEMA_PROPERTY;
import static io.trino.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.connector.RetryMode.NO_RETRIES;
import static io.trino.spi.connector.SchemaTableName.schemaTableName;
import static io.trino.spi.predicate.Range.greaterThanOrEqual;
import static io.trino.spi.predicate.Range.lessThanOrEqual;
import static io.trino.spi.predicate.Range.range;
import static io.trino.spi.predicate.TupleDomain.withColumnDomains;
import static io.trino.spi.predicate.Utils.blockToNativeValue;
import static io.trino.spi.predicate.ValueSet.ofRanges;
import static io.trino.spi.statistics.ColumnStatisticType.MAX_VALUE;
import static io.trino.spi.statistics.ColumnStatisticType.NUMBER_OF_DISTINCT_VALUES_SUMMARY;
import static io.trino.spi.statistics.ColumnStatisticType.TOTAL_SIZE_IN_BYTES;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.TypeUtils.isFloatingPointNaN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Collections.emptyIterator;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.function.Function.identity;
import static java.util.function.Predicate.not;
import static org.apache.hadoop.hive.metastore.TableType.EXTERNAL_TABLE;
import static org.apache.hadoop.hive.metastore.TableType.MANAGED_TABLE;
import static org.apache.hadoop.hive.metastore.TableType.VIRTUAL_VIEW;

public class DeltaLakeMetadata
        implements ConnectorMetadata
{
    public static final Logger LOG = Logger.get(DeltaLakeMetadata.class);

    public static final String PATH_PROPERTY = "path";
    public static final StorageFormat DELTA_STORAGE_FORMAT = create(
            "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
            "org.apache.hadoop.mapred.SequenceFileInputFormat",
            "org.apache.hadoop.hive.ql.io.HiveSequenceFileOutputFormat");
    public static final String CREATE_TABLE_AS_OPERATION = "CREATE TABLE AS SELECT";
    public static final String CREATE_TABLE_OPERATION = "CREATE TABLE";
    public static final String ADD_COLUMN_OPERATION = "ADD COLUMNS";
    public static final String INSERT_OPERATION = "WRITE";
    public static final String DELETE_OPERATION = "DELETE";
    public static final String UPDATE_OPERATION = "UPDATE";
    public static final String OPTIMIZE_OPERATION = "OPTIMIZE";
    public static final String SET_TBLPROPERTIES_OPERATION = "SET TBLPROPERTIES";
    public static final String CHANGE_COLUMN_OPERATION = "CHANGE COLUMN";
    public static final String ISOLATION_LEVEL = "WriteSerializable";
    private static final int READER_VERSION = 1;
    private static final int WRITER_VERSION = 2;
    // Matches the dummy column Databricks stores in the metastore
    private static final List<Column> DUMMY_DATA_COLUMNS = ImmutableList.of(
            new Column("col", HiveType.toHiveType(new ArrayType(VarcharType.createUnboundedVarcharType())), Optional.empty()));
    private static final Set<ColumnStatisticType> SUPPORTED_STATISTICS_TYPE = ImmutableSet.<ColumnStatisticType>builder()
            .add(TOTAL_SIZE_IN_BYTES)
            .add(NUMBER_OF_DISTINCT_VALUES_SUMMARY)
            .build();

    private final DeltaLakeMetastore metastore;
    private final HdfsEnvironment hdfsEnvironment;
    private final TypeManager typeManager;
    private final AccessControlMetadata accessControlMetadata;
    private final CheckpointWriterManager checkpointWriterManager;
    private final long defaultCheckpointInterval;
    private final boolean ignoreCheckpointWriteFailures;
    private final int domainCompactionThreshold;
    private final boolean unsafeWritesEnabled;
    private final JsonCodec<DataFileInfo> dataFileInfoCodec;
    private final JsonCodec<DeltaLakeUpdateResult> updateResultJsonCodec;
    private final TransactionLogWriterFactory transactionLogWriterFactory;
    private final String nodeVersion;
    private final String nodeId;
    private final AtomicReference<Runnable> rollbackAction = new AtomicReference<>();
    private final DeltaLakeRedirectionsProvider deltaLakeRedirectionsProvider;
    private final ExtendedStatisticsAccess statisticsAccess;
    private final boolean deleteSchemaLocationsFallback;

    public DeltaLakeMetadata(
            DeltaLakeMetastore metastore,
            HdfsEnvironment hdfsEnvironment,
            TypeManager typeManager,
            AccessControlMetadata accessControlMetadata,
            int domainCompactionThreshold,
            boolean unsafeWritesEnabled,
            JsonCodec<DataFileInfo> dataFileInfoCodec,
            JsonCodec<DeltaLakeUpdateResult> updateResultJsonCodec,
            TransactionLogWriterFactory transactionLogWriterFactory,
            NodeManager nodeManager,
            CheckpointWriterManager checkpointWriterManager,
            long defaultCheckpointInterval,
            boolean ignoreCheckpointWriteFailures,
            boolean deleteSchemaLocationsFallback,
            DeltaLakeRedirectionsProvider deltaLakeRedirectionsProvider,
            ExtendedStatisticsAccess statisticsAccess)
    {
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.accessControlMetadata = requireNonNull(accessControlMetadata, "accessControlMetadata is null");
        this.domainCompactionThreshold = domainCompactionThreshold;
        this.unsafeWritesEnabled = unsafeWritesEnabled;
        this.dataFileInfoCodec = requireNonNull(dataFileInfoCodec, "dataFileInfoCodec is null");
        this.updateResultJsonCodec = requireNonNull(updateResultJsonCodec, "updateResultJsonCodec is null");
        this.transactionLogWriterFactory = requireNonNull(transactionLogWriterFactory, "transactionLogWriterFactory is null");
        this.nodeVersion = nodeManager.getCurrentNode().getVersion();
        this.nodeId = nodeManager.getCurrentNode().getNodeIdentifier();
        this.checkpointWriterManager = requireNonNull(checkpointWriterManager, "checkpointWriterManager is null");
        this.defaultCheckpointInterval = defaultCheckpointInterval;
        this.ignoreCheckpointWriteFailures = ignoreCheckpointWriteFailures;
        this.deltaLakeRedirectionsProvider = requireNonNull(deltaLakeRedirectionsProvider, "deltaLakeRedirectionsProvider is null");
        this.statisticsAccess = requireNonNull(statisticsAccess, "statisticsAccess is null");
        this.deleteSchemaLocationsFallback = deleteSchemaLocationsFallback;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return metastore.getAllDatabases().stream()
                .filter(schema -> {
                    String schemaName = schema.toLowerCase(ENGLISH);
                    return !(schemaName.equals("information_schema") || schemaName.equals("sys"));
                })
                .collect(toImmutableList());
    }

    private static boolean isHiveTable(Table table)
    {
        return !isDeltaLakeTable(table);
    }

    @Override
    public Optional<CatalogSchemaTableName> redirectTable(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(session, "session is null");
        requireNonNull(tableName, "tableName is null");
        Optional<String> targetCatalogName = DeltaLakeSessionProperties.getHiveCatalogName(session);
        if (targetCatalogName.isEmpty()) {
            return Optional.empty();
        }
        if (isHiveSystemSchema(tableName.getSchemaName())) {
            return Optional.empty();
        }

        // we need to chop off any "$partitions" and similar suffixes from table name while querying the metastore for the Table object
        int metadataMarkerIndex = tableName.getTableName().lastIndexOf('$');
        SchemaTableName tableNameBase = (metadataMarkerIndex == -1) ? tableName : schemaTableName(
                tableName.getSchemaName(),
                tableName.getTableName().substring(0, metadataMarkerIndex));

        Optional<Table> table = metastore.getHiveMetastore()
                .getTable(tableNameBase.getSchemaName(), tableNameBase.getTableName());

        if (table.isEmpty() || VIRTUAL_VIEW.name().equals(table.get().getTableType())) {
            return Optional.empty();
        }
        if (isHiveTable(table.get())) {
            // After redirecting, use the original table name, with "$partitions" and similar suffixes
            return targetCatalogName.map(catalog -> new CatalogSchemaTableName(catalog, tableName));
        }
        return Optional.empty();
    }

    @Override
    public DeltaLakeTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        Optional<Table> table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (table.isEmpty()) {
            return null;
        }

        TableSnapshot tableSnapshot = metastore.getSnapshot(tableName, session);
        Optional<MetadataEntry> metadata = metastore.getMetadata(tableSnapshot, session);
        if (metadata.isPresent()) {
            ColumnMappingMode columnMappingMode = getColumnMappingMode(metadata.get());
            if (columnMappingMode != ColumnMappingMode.NAME && columnMappingMode != ColumnMappingMode.NONE) {
                throw new TrinoException(NOT_SUPPORTED, format("Only 'name' or 'none' is supported for the '%s' table property", COLUMN_MAPPING_MODE_CONFIGURATION_KEY));
            }
        }
        return new DeltaLakeTableHandle(
                tableName.getSchemaName(),
                tableName.getTableName(),
                metastore.getTableLocation(tableName, session),
                metadata,
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                tableSnapshot.getVersion(),
                false);
    }

    @Override
    public SchemaTableName getSchemaTableName(ConnectorSession session, ConnectorTableHandle table)
    {
        return ((DeltaLakeTableHandle) table).getSchemaTableName();
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return new ConnectorTableProperties();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        DeltaLakeTableHandle tableHandle = (DeltaLakeTableHandle) table;
        String location = metastore.getTableLocation(tableHandle.getSchemaTableName(), session);
        Map<String, String> columnComments = getColumnComments(tableHandle.getMetadataEntry());
        List<ColumnMetadata> columns = getColumns(tableHandle.getMetadataEntry()).stream()
                .map(column -> getColumnMetadata(column, columnComments.get(column.getName())))
                .collect(toImmutableList());

        ImmutableMap.Builder<String, Object> properties = ImmutableMap.<String, Object>builder()
                .put(LOCATION_PROPERTY, location)
                .put(PARTITIONED_BY_PROPERTY, tableHandle.getMetadataEntry().getCanonicalPartitionColumns());

        Optional<Long> checkpointInterval = tableHandle.getMetadataEntry().getCheckpointInterval();
        checkpointInterval.ifPresent(value -> properties.put(CHECKPOINT_INTERVAL_PROPERTY, value));

        return new ConnectorTableMetadata(
                tableHandle.getSchemaTableName(),
                columns,
                properties.buildOrThrow(),
                Optional.ofNullable(tableHandle.getMetadataEntry().getDescription()));
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        if (schemaName.isPresent() && schemaName.get().equals("information_schema")) {
            // TODO https://github.com/trinodb/trino/issues/1559 information_schema should be handled by the engine fully
            return ImmutableList.of();
        }
        return schemaName.map(Collections::singletonList)
                .orElseGet(() -> listSchemaNames(session))
                .stream()
                .flatMap(schema -> metastore.getAllTables(schema).stream()
                        .map(table -> new SchemaTableName(schema, table)))
                .collect(toImmutableList());
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        DeltaLakeTableHandle table = (DeltaLakeTableHandle) tableHandle;
        return getColumns(table.getMetadataEntry()).stream()
                .collect(toImmutableMap(DeltaLakeColumnHandle::getName, identity()));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        DeltaLakeTableHandle table = (DeltaLakeTableHandle) tableHandle;
        DeltaLakeColumnHandle column = (DeltaLakeColumnHandle) columnHandle;
        return getColumnMetadata(column, getColumnComments(table.getMetadataEntry()).get(column.getName()));
    }

    /**
     * Provides partitioning scheme of table for query planner to decide how to
     * write to multiple partitions.
     */
    @Override
    public Optional<ConnectorTableLayout> getNewTableLayout(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        validateTableColumns(tableMetadata);

        List<String> partitionColumnNames = getPartitionedBy(tableMetadata.getProperties());

        if (partitionColumnNames.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ConnectorTableLayout(partitionColumnNames));
    }

    @Override
    public Optional<ConnectorTableLayout> getInsertLayout(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        DeltaLakeTableHandle deltaLakeTableHandle = (DeltaLakeTableHandle) tableHandle;
        List<String> partitionColumnNames = deltaLakeTableHandle.getMetadataEntry().getCanonicalPartitionColumns();

        if (partitionColumnNames.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ConnectorTableLayout(partitionColumnNames));
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        throw new UnsupportedOperationException("The deprecated listTableColumns is not supported because streamTableColumns is implemented instead");
    }

    @Override
    public Iterator<TableColumnsMetadata> streamTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getSchema().isPresent() && prefix.getSchema().get().equals("information_schema")) {
            // TODO https://github.com/trinodb/trino/issues/1559 information_schema should be handled by the engine fully
            return emptyIterator();
        }

        List<SchemaTableName> tables = prefix.getTable()
                .map(ignored -> singletonList(prefix.toSchemaTableName()))
                .orElseGet(() -> listTables(session, prefix.getSchema()));

        return tables.stream().flatMap(table -> {
            try {
                if (redirectTable(session, table).isPresent()) {
                    // put "redirect marker" for current table
                    return Stream.of(TableColumnsMetadata.forRedirectedTable(table));
                }

                // intentionally skip case when table snapshot is present but it lacks metadata portion
                return metastore.getMetadata(metastore.getSnapshot(table, session), session).stream().map(metadata -> {
                    Map<String, String> columnComments = getColumnComments(metadata);
                    List<ColumnMetadata> columnMetadata = getColumns(metadata).stream()
                            .map(column -> getColumnMetadata(column, columnComments.get(column.getName())))
                            .collect(toImmutableList());
                    return TableColumnsMetadata.forTable(table, columnMetadata);
                });
            }
            catch (NotADeltaLakeTableException e) {
                return Stream.empty();
            }
            catch (RuntimeException e) {
                // this may happen when table is being deleted concurrently, it still exists in metastore but TL is no longer present
                // there can be several different exceptions thrown this is why all RTE are caught and ignored here
                LOG.debug(e, "Ignored exception when trying to list columns from %s", table);
                return Stream.empty();
            }
        })
                .iterator();
    }

    private List<DeltaLakeColumnHandle> getColumns(MetadataEntry deltaMetadata)
    {
        ImmutableList.Builder<DeltaLakeColumnHandle> columns = ImmutableList.builder();
        extractSchema(deltaMetadata, typeManager).stream()
                .map(column -> toColumnHandle(column.getColumnMetadata(), column.getPhysicalName(), column.getPhysicalColumnType(), deltaMetadata.getCanonicalPartitionColumns()))
                .forEach(columns::add);
        columns.add(pathColumnHandle());
        columns.add(fileSizeColumnHandle());
        columns.add(fileModifiedTimeColumnHandle());
        return columns.build();
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        if (!isTableStatisticsEnabled(session)) {
            return TableStatistics.empty();
        }
        return metastore.getTableStatistics(session, (DeltaLakeTableHandle) tableHandle);
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, TrinoPrincipal owner)
    {
        Optional<String> location = DeltaLakeSchemaProperties.getLocation(properties).map(locationUri -> {
            try {
                hdfsEnvironment.getFileSystem(new HdfsContext(session), new Path(locationUri));
            }
            catch (IOException e) {
                throw new TrinoException(INVALID_SCHEMA_PROPERTY, "Invalid location URI: " + locationUri, e);
            }
            return locationUri;
        });

        Database database = Database.builder()
                .setDatabaseName(schemaName)
                .setLocation(location)
                .setOwnerType(Optional.of(owner.getType()))
                .setOwnerName(Optional.of(owner.getName()))
                .build();

        metastore.createDatabase(database);
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        Optional<Path> location = metastore.getDatabase(schemaName)
                .orElseThrow(() -> new SchemaNotFoundException(schemaName))
                .getLocation()
                .map(Path::new);

        // If we see files in the schema location, don't delete it.
        // If we see no files or can't see the location at all, use fallback.
        boolean deleteData = location.map(path -> {
            HdfsContext context = new HdfsContext(session); // don't catch errors here

            try (FileSystem fs = hdfsEnvironment.getFileSystem(context, path)) {
                return !fs.listLocatedStatus(path).hasNext();
            }
            catch (IOException | RuntimeException e) {
                LOG.warn(e, "Could not check schema directory '%s'", path);
                return deleteSchemaLocationsFallback;
            }
        }).orElse(deleteSchemaLocationsFallback);

        metastore.dropDatabase(schemaName, deleteData);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        Database schema = metastore.getDatabase(schemaName).orElseThrow(() -> new SchemaNotFoundException(schemaName));

        boolean external = true;
        String location = getLocation(tableMetadata.getProperties());
        if (location == null) {
            Optional<String> schemaLocation = getSchemaLocation(schema);
            if (schemaLocation.isEmpty()) {
                throw new TrinoException(NOT_SUPPORTED, "The 'location' property must be specified either for the table or the schema");
            }
            location = new Path(schemaLocation.get(), tableName).toString();
            checkPathContainsNoFiles(session, new Path(location));
            external = false;
        }
        Path targetPath = new Path(location);
        ensurePathExists(session, targetPath);
        Path deltaLogDirectory = getTransactionLogDir(targetPath);
        Optional<Long> checkpointInterval = DeltaLakeTableProperties.getCheckpointInterval(tableMetadata.getProperties());

        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(new HdfsContext(session), targetPath);
            if (!fileSystem.exists(deltaLogDirectory)) {
                validateTableColumns(tableMetadata);

                List<String> partitionColumns = getPartitionedBy(tableMetadata.getProperties());
                List<DeltaLakeColumnHandle> deltaLakeColumns = tableMetadata.getColumns()
                        .stream()
                        .map(column -> toColumnHandle(column, column.getName(), column.getType(), partitionColumns))
                        .collect(toImmutableList());
                Map<String, String> columnComments = tableMetadata.getColumns().stream()
                        .filter(column -> column.getComment() != null)
                        .collect(toImmutableMap(ColumnMetadata::getName, ColumnMetadata::getComment));
                TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriterWithoutTransactionIsolation(session, targetPath.toString());
                appendTableEntries(
                        0,
                        transactionLogWriter,
                        randomUUID().toString(),
                        deltaLakeColumns,
                        partitionColumns,
                        columnComments,
                        buildDeltaMetadataConfiguration(checkpointInterval),
                        CREATE_TABLE_OPERATION,
                        session,
                        nodeVersion,
                        nodeId,
                        tableMetadata.getComment());

                setRollback(() -> deleteRecursivelyIfExists(new HdfsContext(session), hdfsEnvironment, deltaLogDirectory));
                transactionLogWriter.flush();
            }
        }
        catch (IOException e) {
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Unable to access file system for: " + location, e);
        }

        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(schemaName)
                .setTableName(tableName)
                .setOwner(Optional.of(session.getUser()))
                .setTableType(external ? EXTERNAL_TABLE.name() : MANAGED_TABLE.name())
                .setDataColumns(DUMMY_DATA_COLUMNS)
                .setParameters(deltaTableProperties(session, location, external));

        setDeltaStorageFormat(tableBuilder, location, targetPath);
        Table table = tableBuilder.build();

        PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(table.getOwner().orElseThrow());
        metastore.createTable(
                session,
                table,
                principalPrivileges);
    }

    private static Map<String, String> deltaTableProperties(ConnectorSession session, String location, boolean external)
    {
        ImmutableMap.Builder<String, String> properties = ImmutableMap.<String, String>builder()
                .put(PRESTO_QUERY_ID_NAME, session.getQueryId())
                .put(LOCATION_PROPERTY, location)
                .put(TABLE_PROVIDER_PROPERTY, TABLE_PROVIDER_VALUE)
                // Set bogus table stats to prevent Hive 3.x from gathering these stats at table creation.
                // These stats are not useful by themselves and can take a long time to collect when creating a
                // table over a large data set.
                .put("numFiles", "-1")
                .put("totalSize", "-1");

        if (external) {
            // Mimicking the behavior of the Hive connector which sets both `Table#setTableType` and the "EXTERNAL" table property
            properties.put("EXTERNAL", "TRUE");
        }
        return properties.buildOrThrow();
    }

    private static void setDeltaStorageFormat(Table.Builder tableBuilder, String location, Path targetPath)
    {
        tableBuilder.getStorageBuilder()
                // this mimics what Databricks is doing when creating a Delta table in the Hive metastore
                .setStorageFormat(DELTA_STORAGE_FORMAT)
                .setSerdeParameters(ImmutableMap.of(PATH_PROPERTY, location))
                .setLocation(targetPath.toString());
    }

    private Path getExternalPath(HdfsContext context, String location)
    {
        try {
            Path path = new Path(location);
            if (!isS3FileSystem(context, hdfsEnvironment, path)) {
                if (!hdfsEnvironment.getFileSystem(context, path).getFileStatus(path).isDirectory()) {
                    throw new TrinoException(INVALID_TABLE_PROPERTY, "External location must be a directory: " + location);
                }
            }
            return path;
        }
        catch (IllegalArgumentException | IOException e) {
            throw new TrinoException(INVALID_TABLE_PROPERTY, "External location is not a valid file system URI: " + location, e);
        }
    }

    @Override
    public DeltaLakeOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorTableLayout> layout, RetryMode retryMode)
    {
        validateTableColumns(tableMetadata);

        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        Database schema = metastore.getDatabase(schemaName).orElseThrow(() -> new SchemaNotFoundException(schemaName));
        List<String> partitionedBy = getPartitionedBy(tableMetadata.getProperties());

        boolean external = true;
        String location = getLocation(tableMetadata.getProperties());
        if (location == null) {
            Optional<String> schemaLocation = getSchemaLocation(schema);
            if (schemaLocation.isEmpty()) {
                throw new TrinoException(NOT_SUPPORTED, "The 'location' property must be specified either for the table or the schema");
            }
            location = new Path(schemaLocation.get(), tableName).toString();
            external = false;
        }
        Path targetPath = new Path(location);
        ensurePathExists(session, targetPath);

        HdfsContext hdfsContext = new HdfsContext(session);
        createDirectory(hdfsContext, hdfsEnvironment, targetPath);
        checkPathContainsNoFiles(session, targetPath);

        setRollback(() -> deleteRecursivelyIfExists(new HdfsContext(session), hdfsEnvironment, targetPath));

        return new DeltaLakeOutputTableHandle(
                schemaName,
                tableName,
                tableMetadata.getColumns().stream().map(column -> toColumnHandle(column, column.getName(), column.getType(), partitionedBy)).collect(toImmutableList()),
                location,
                DeltaLakeTableProperties.getCheckpointInterval(tableMetadata.getProperties()),
                external,
                tableMetadata.getComment());
    }

    private Optional<String> getSchemaLocation(Database database)
    {
        Optional<String> schemaLocation = database.getLocation();
        if (schemaLocation.isEmpty() || schemaLocation.get().isEmpty()) {
            return Optional.empty();
        }

        return schemaLocation;
    }

    private void ensurePathExists(ConnectorSession session, Path directoryPath)
    {
        HdfsContext hdfsContext = new HdfsContext(session);
        if (!pathExists(hdfsContext, hdfsEnvironment, directoryPath)) {
            createDirectory(hdfsContext, hdfsEnvironment, directoryPath);
        }
    }

    private void checkPathContainsNoFiles(ConnectorSession session, Path targetPath)
    {
        try {
            FileSystem fs = hdfsEnvironment.getFileSystem(new HdfsContext(session), targetPath.getParent());
            if (fs.exists(targetPath)) {
                RemoteIterator<FileStatus> filesIterator = fs.listStatusIterator(targetPath);
                if (filesIterator.hasNext()) {
                    throw new TrinoException(NOT_SUPPORTED, "Target location cannot contain any files: " + targetPath);
                }
            }
        }
        catch (IOException e) {
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Unable to access file system for: " + targetPath, e);
        }
    }

    private void validateTableColumns(ConnectorTableMetadata tableMetadata)
    {
        checkPartitionColumns(tableMetadata.getColumns(), getPartitionedBy(tableMetadata.getProperties()));
        checkColumnTypes(tableMetadata.getColumns());
    }

    private static void checkPartitionColumns(List<ColumnMetadata> columns, List<String> partitionColumnNames)
    {
        Set<String> columnNames = columns.stream()
                .map(ColumnMetadata::getName)
                .collect(toImmutableSet());
        List<String> invalidPartitionNames = partitionColumnNames.stream()
                .filter(partitionColumnName -> !columnNames.contains(partitionColumnName))
                .collect(toImmutableList());

        if (!invalidPartitionNames.isEmpty()) {
            throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Table property 'partition_by' contained column names which do not exist: " + invalidPartitionNames);
        }
    }

    private void checkColumnTypes(List<ColumnMetadata> columnMetadata)
    {
        for (ColumnMetadata column : columnMetadata) {
            Type type = column.getType();
            validateType(type);
        }
    }

    private static boolean deleteRecursivelyIfExists(HdfsContext context, HdfsEnvironment hdfsEnvironment, Path path)
    {
        FileSystem fileSystem;
        try {
            fileSystem = hdfsEnvironment.getFileSystem(context, path);
        }
        catch (IOException e) {
            LOG.warn(e, "IOException while trying to delete '%s'", path);
            return false;
        }

        return deleteIfExists(fileSystem, path, true);
    }

    private static boolean deleteIfExists(FileSystem fileSystem, Path path, boolean recursive)
    {
        try {
            // attempt to delete the path
            if (fileSystem.delete(path, recursive)) {
                return true;
            }

            // delete failed
            // check if path still exists
            return !fileSystem.exists(path);
        }
        catch (FileNotFoundException ignored) {
            // path was already removed or never existed
            return true;
        }
        catch (IOException e) {
            LOG.warn(e, "IOException while trying to delete '%s'", path);
        }
        return false;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(
            ConnectorSession session,
            ConnectorOutputTableHandle tableHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics)
    {
        DeltaLakeOutputTableHandle handle = (DeltaLakeOutputTableHandle) tableHandle;

        String schemaName = handle.getSchemaName();
        String tableName = handle.getTableName();
        String location = handle.getLocation();

        List<DataFileInfo> dataFileInfos = fragments.stream()
                .map(Slice::getBytes)
                .map(dataFileInfoCodec::fromJson)
                .collect(toImmutableList());

        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(schemaName)
                .setTableName(tableName)
                .setOwner(Optional.of(session.getUser()))
                .setTableType(handle.isExternal() ? EXTERNAL_TABLE.name() : MANAGED_TABLE.name())
                .setDataColumns(DUMMY_DATA_COLUMNS)
                .setParameters(deltaTableProperties(session, location, handle.isExternal()));
        setDeltaStorageFormat(tableBuilder, location, getExternalPath(new HdfsContext(session), location));
        Table table = tableBuilder.build();
        // Ensure the table has queryId set. This is relied on for exception handling
        String queryId = session.getQueryId();
        verify(
                getQueryId(table).orElseThrow(() -> new IllegalArgumentException("Query id is not present")).equals(queryId),
                "Table does not have correct query id set",
                table);

        try {
            // For CTAS there is no risk of multiple writers racing. Using writer without transaction isolation so we are not limiting support for CTAS to
            // filesystems for which we have proper implementations of TransactionLogSynchronizers.
            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriterWithoutTransactionIsolation(session, handle.getLocation());

            appendTableEntries(
                    0,
                    transactionLogWriter,
                    randomUUID().toString(),
                    handle.getInputColumns(),
                    handle.getPartitionedBy(),
                    ImmutableMap.of(),
                    buildDeltaMetadataConfiguration(handle.getCheckpointInterval()),
                    CREATE_TABLE_AS_OPERATION,
                    session,
                    nodeVersion,
                    nodeId,
                    handle.getComment());
            appendAddFileEntries(transactionLogWriter, dataFileInfos, handle.getPartitionedBy(), true);
            transactionLogWriter.flush();
            PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(table.getOwner().orElseThrow());

            try {
                metastore.createTable(session, table, principalPrivileges);
            }
            catch (TableAlreadyExistsException e) {
                // Ignore TableAlreadyExistsException when table looks like created by us.
                // This may happen when an actually successful metastore create call is retried
                // e.g. because of a timeout on our side.
                Optional<Table> existingTable = metastore.getTable(schemaName, tableName);
                if (existingTable.isEmpty() || !isCreatedBy(existingTable.get(), queryId)) {
                    throw e;
                }
            }
        }
        catch (Exception e) {
            // Remove the transaction log entry if the table creation fails
            try {
                Path transactionLogLocation = getTransactionLogDir(new Path(handle.getLocation()));
                FileSystem fs = hdfsEnvironment.getFileSystem(new HdfsContext(session), transactionLogLocation);
                fs.delete(transactionLogLocation, true);
            }
            catch (IOException ioException) {
                // Nothing to do, the IOException is probably the same reason why the initial write failed
                LOG.error(ioException, "Transaction log cleanup failed during CREATE TABLE rollback");
            }
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Failed to write Delta Lake transaction log entry", e);
        }

        return Optional.empty();
    }

    private static boolean isCreatedBy(Table table, String queryId)
    {
        Optional<String> tableQueryId = getQueryId(table);
        return tableQueryId.isPresent() && tableQueryId.get().equals(queryId);
    }

    @Override
    public void setTableComment(ConnectorSession session, ConnectorTableHandle tableHandle, Optional<String> comment)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        checkSupportedWriterVersion(session, handle.getSchemaTableName());

        ConnectorTableMetadata tableMetadata = getTableMetadata(session, handle);

        try {
            long commitVersion = handle.getReadVersion() + 1;

            List<String> partitionColumns = getPartitionedBy(tableMetadata.getProperties());
            List<DeltaLakeColumnHandle> columns = tableMetadata.getColumns().stream()
                    .filter(column -> !column.isHidden())
                    .map(column -> toColumnHandle(column, column.getName(), column.getType(), partitionColumns))
                    .collect(toImmutableList());

            Optional<Long> checkpointInterval = DeltaLakeTableProperties.getCheckpointInterval(tableMetadata.getProperties());

            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, handle.getLocation());
            appendTableEntries(
                    commitVersion,
                    transactionLogWriter,
                    handle.getMetadataEntry().getId(),
                    columns,
                    partitionColumns,
                    getColumnComments(handle.getMetadataEntry()),
                    buildDeltaMetadataConfiguration(checkpointInterval),
                    SET_TBLPROPERTIES_OPERATION,
                    session,
                    nodeVersion,
                    nodeId,
                    comment);
            transactionLogWriter.flush();
        }
        catch (Exception e) {
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, format("Unable to comment on table: %s.%s", handle.getSchemaName(), handle.getTableName()), e);
        }
    }

    @Override
    public void setColumnComment(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column, Optional<String> comment)
    {
        DeltaLakeTableHandle deltaLakeTableHandle = (DeltaLakeTableHandle) tableHandle;
        DeltaLakeColumnHandle deltaLakeColumnHandle = (DeltaLakeColumnHandle) column;
        checkSupportedWriterVersion(session, deltaLakeTableHandle.getSchemaTableName());

        ConnectorTableMetadata tableMetadata = getTableMetadata(session, deltaLakeTableHandle);

        try {
            long commitVersion = deltaLakeTableHandle.getReadVersion() + 1;

            List<String> partitionColumns = getPartitionedBy(tableMetadata.getProperties());
            List<DeltaLakeColumnHandle> columns = tableMetadata.getColumns().stream()
                    .filter(columnMetadata -> !columnMetadata.isHidden())
                    .map(columnMetadata -> toColumnHandle(columnMetadata, columnMetadata.getName(), columnMetadata.getType(), partitionColumns))
                    .collect(toImmutableList());

            ImmutableMap.Builder<String, String> columnComments = ImmutableMap.builder();
            columnComments.putAll(getColumnComments(deltaLakeTableHandle.getMetadataEntry()).entrySet().stream()
                    .filter(e -> !e.getKey().equals(deltaLakeColumnHandle.getName()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            comment.ifPresent(s -> columnComments.put(deltaLakeColumnHandle.getName(), s));

            Optional<Long> checkpointInterval = DeltaLakeTableProperties.getCheckpointInterval(tableMetadata.getProperties());

            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, deltaLakeTableHandle.getLocation());
            appendTableEntries(
                    commitVersion,
                    transactionLogWriter,
                    deltaLakeTableHandle.getMetadataEntry().getId(),
                    columns,
                    partitionColumns,
                    columnComments.buildOrThrow(),
                    buildDeltaMetadataConfiguration(checkpointInterval),
                    CHANGE_COLUMN_OPERATION,
                    session,
                    nodeVersion,
                    nodeId,
                    Optional.ofNullable(deltaLakeTableHandle.getMetadataEntry().getDescription()));
            transactionLogWriter.flush();
        }
        catch (Exception e) {
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, format("Unable to add '%s' column comment for: %s.%s", deltaLakeColumnHandle.getName(), deltaLakeTableHandle.getSchemaName(), deltaLakeTableHandle.getTableName()), e);
        }
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata newColumnMetadata)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        checkSupportedWriterVersion(session, handle.getSchemaTableName());

        ConnectorTableMetadata tableMetadata = getTableMetadata(session, handle);

        try {
            long commitVersion = handle.getReadVersion() + 1;

            List<String> partitionColumns = getPartitionedBy(tableMetadata.getProperties());
            ImmutableList.Builder<DeltaLakeColumnHandle> columnsBuilder = ImmutableList.builder();
            columnsBuilder.addAll(tableMetadata.getColumns().stream()
                    .filter(column -> !column.isHidden())
                    .map(column -> toColumnHandle(column, column.getName(), column.getType(), partitionColumns))
                    .collect(toImmutableList()));
            columnsBuilder.add(toColumnHandle(newColumnMetadata, newColumnMetadata.getName(), newColumnMetadata.getType(), partitionColumns));
            ImmutableMap.Builder<String, String> columnComments = ImmutableMap.builder();
            columnComments.putAll(getColumnComments(handle.getMetadataEntry()));
            if (newColumnMetadata.getComment() != null) {
                columnComments.put(newColumnMetadata.getName(), newColumnMetadata.getComment());
            }

            Optional<Long> checkpointInterval = DeltaLakeTableProperties.getCheckpointInterval(tableMetadata.getProperties());

            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, handle.getLocation());
            appendTableEntries(
                    commitVersion,
                    transactionLogWriter,
                    handle.getMetadataEntry().getId(),
                    columnsBuilder.build(),
                    partitionColumns,
                    columnComments.buildOrThrow(),
                    buildDeltaMetadataConfiguration(checkpointInterval),
                    ADD_COLUMN_OPERATION,
                    session,
                    nodeVersion,
                    nodeId,
                    Optional.ofNullable(handle.getMetadataEntry().getDescription()));
            transactionLogWriter.flush();
        }
        catch (Exception e) {
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, format("Unable to add '%s' column for: %s.%s", newColumnMetadata.getName(), handle.getSchemaName(), handle.getTableName()), e);
        }
    }

    private static void appendTableEntries(
            long commitVersion,
            TransactionLogWriter transactionLogWriter,
            String tableId,
            List<DeltaLakeColumnHandle> columns,
            List<String> partitionColumnNames,
            Map<String, String> columnComments,
            Map<String, String> configuration,
            String operation,
            ConnectorSession session,
            String nodeVersion,
            String nodeId,
            Optional<String> comment)
    {
        long createdTime = System.currentTimeMillis();
        transactionLogWriter.appendCommitInfoEntry(
                new CommitInfoEntry(
                        commitVersion,
                        createdTime,
                        session.getUser(),
                        session.getUser(),
                        operation,
                        ImmutableMap.of("queryId", session.getQueryId()),
                        null,
                        null,
                        "trino-" + nodeVersion + "-" + nodeId,
                        0,
                        ISOLATION_LEVEL,
                        true));

        transactionLogWriter.appendProtocolEntry(new ProtocolEntry(READER_VERSION, WRITER_VERSION));

        transactionLogWriter.appendMetadataEntry(
                new MetadataEntry(
                        tableId,
                        null,
                        comment.orElse(null),
                        new Format("parquet", ImmutableMap.of()),
                        serializeSchemaAsJson(columns, columnComments),
                        partitionColumnNames,
                        ImmutableMap.copyOf(configuration),
                        createdTime));
    }

    private static void appendAddFileEntries(TransactionLogWriter transactionLogWriter, List<DataFileInfo> dataFileInfos, List<String> partitionColumnNames, boolean dataChange)
            throws JsonProcessingException
    {
        for (DataFileInfo info : dataFileInfos) {
            // using Hashmap because partition values can be null
            Map<String, String> partitionValues = new HashMap<>();
            for (int i = 0; i < partitionColumnNames.size(); i++) {
                partitionValues.put(partitionColumnNames.get(i), info.getPartitionValues().get(i));
            }
            partitionValues = unmodifiableMap(partitionValues);

            transactionLogWriter.appendAddFileEntry(
                    new AddFileEntry(
                            toUriFormat(info.getPath()), // Databricks and OSS Delta Lake both expect path to be url-encoded, even though the procotol specification doesn't mention that
                            partitionValues,
                            info.getSize(),
                            info.getCreationTime(),
                            dataChange,
                            Optional.of(serializeStatsAsJson(info.getStatistics())),
                            Optional.empty(),
                            ImmutableMap.of()));
        }
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns, RetryMode retryMode)
    {
        DeltaLakeTableHandle table = (DeltaLakeTableHandle) tableHandle;
        if (!allowWrite(session, table)) {
            String fileSystem = new Path(table.getLocation()).toUri().getScheme();
            throw new TrinoException(NOT_SUPPORTED, format("Inserts are not supported on the %s filesystem", fileSystem));
        }
        checkSupportedWriterVersion(session, table.getSchemaTableName());

        List<DeltaLakeColumnHandle> inputColumns = columns.stream()
                .map(handle -> (DeltaLakeColumnHandle) handle)
                .collect(toImmutableList());

        ConnectorTableMetadata tableMetadata = getTableMetadata(session, table);

        // This check acts as a safeguard in cases where the input columns may differ from the table metadata case-sensitively
        checkAllColumnsPassedOnInsert(tableMetadata, inputColumns);
        String tableLocation = getLocation(tableMetadata.getProperties());
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(new HdfsContext(session), new Path(tableLocation));

            return new DeltaLakeInsertTableHandle(
                    table.getSchemaName(),
                    table.getTableName(),
                    tableLocation,
                    table.getMetadataEntry(),
                    inputColumns,
                    getMandatoryCurrentVersion(fileSystem, new Path(tableLocation)),
                    retryMode != NO_RETRIES);
        }
        catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e);
        }
    }

    private void checkAllColumnsPassedOnInsert(ConnectorTableMetadata tableMetadata, List<DeltaLakeColumnHandle> insertColumns)
    {
        List<String> allColumnNames = tableMetadata.getColumns().stream()
                .filter(not(ColumnMetadata::isHidden))
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        List<String> insertColumnNames = insertColumns.stream()
                .map(DeltaLakeColumnHandle::getName)
                .collect(toImmutableList());

        checkArgument(allColumnNames.equals(insertColumnNames), "Not all table columns passed on INSERT; table columns=%s; insert columns=%s", allColumnNames, insertColumnNames);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(
            ConnectorSession session,
            ConnectorInsertTableHandle insertHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics)
    {
        DeltaLakeInsertTableHandle handle = (DeltaLakeInsertTableHandle) insertHandle;

        List<DataFileInfo> dataFileInfos = fragments.stream()
                .map(Slice::getBytes)
                .map(dataFileInfoCodec::fromJson)
                .collect(toImmutableList());

        if (handle.isRetriesEnabled()) {
            cleanExtraOutputFiles(session, handle.getLocation(), dataFileInfos);
        }

        boolean writeCommitted = false;
        try {
            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, handle.getLocation());

            long createdTime = Instant.now().toEpochMilli();

            FileSystem fileSystem = hdfsEnvironment.getFileSystem(new HdfsContext(session), new Path(handle.getLocation()));
            long commitVersion = getMandatoryCurrentVersion(fileSystem, new Path(handle.getLocation())) + 1;
            if (commitVersion != handle.getReadVersion() + 1) {
                throw new TransactionConflictException(format("Conflicting concurrent writes found. Expected transaction log version: %s, actual version: %s",
                        handle.getReadVersion(),
                        commitVersion - 1));
            }
            Optional<Long> checkpointInterval = handle.getMetadataEntry().getCheckpointInterval();
            transactionLogWriter.appendCommitInfoEntry(
                    new CommitInfoEntry(
                            commitVersion,
                            createdTime,
                            session.getUser(),
                            session.getUser(),
                            INSERT_OPERATION,
                            ImmutableMap.of("queryId", session.getQueryId()),
                            null,
                            null,
                            "trino-" + nodeVersion + "-" + nodeId,
                            handle.getReadVersion(), // it is not obvious why we need to persist this readVersion
                            ISOLATION_LEVEL,
                            true));

            // Note: during writes we want to preserve original case of partition columns
            List<String> partitionColumns = handle.getMetadataEntry().getOriginalPartitionColumns();
            appendAddFileEntries(transactionLogWriter, dataFileInfos, partitionColumns, true);

            transactionLogWriter.flush();
            writeCommitted = true;
            writeCheckpointIfNeeded(session, new SchemaTableName(handle.getSchemaName(), handle.getTableName()), checkpointInterval, commitVersion);
        }
        catch (Exception e) {
            if (!writeCommitted) {
                // TODO perhaps it should happen in a background thread (https://github.com/trinodb/trino/issues/12011)
                cleanupFailedWrite(session, handle.getLocation(), dataFileInfos);
            }
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Failed to write Delta Lake transaction log entry", e);
        }

        return Optional.empty();
    }

    @Override
    public ColumnHandle getDeleteRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return new DeltaLakeColumnHandle(ROW_ID_COLUMN_NAME, ROW_ID_COLUMN_TYPE, ROW_ID_COLUMN_NAME, ROW_ID_COLUMN_TYPE, SYNTHESIZED);
    }

    @Override
    public ConnectorTableHandle beginDelete(ConnectorSession session, ConnectorTableHandle tableHandle, RetryMode retryMode)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        if (isAppendOnly(handle.getMetadataEntry())) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot delete rows from a table with '" + APPEND_ONLY_CONFIGURATION_KEY + "' set to true");
        }
        if (!allowWrite(session, handle)) {
            String fileSystem = new Path(handle.getLocation()).toUri().getScheme();
            throw new TrinoException(NOT_SUPPORTED, format("Deletes are not supported on the %s filesystem", fileSystem));
        }
        checkSupportedWriterVersion(session, handle.getSchemaTableName());

        return DeltaLakeTableHandle.forDelete(
                handle.getSchemaName(),
                handle.getTableName(),
                handle.getLocation(),
                Optional.of(handle.getMetadataEntry()),
                handle.getEnforcedPartitionConstraint(),
                handle.getNonPartitionConstraint(),
                handle.getProjectedColumns(),
                handle.getReadVersion(),
                retryMode != NO_RETRIES);
    }

    @Override
    public void finishDelete(ConnectorSession session, ConnectorTableHandle tableHandle, Collection<Slice> fragments)
    {
        finishWrite(session, tableHandle, fragments);
    }

    // The rowId is a RowType where the first field is a BigInt containing the row number. The second field contains the values of any columns that were unmodified.
    // They are needed to write the complete row after modifications in DeltaLakeUpdatablePageSource. If there are no unmodified columns, the rowId only has one field.
    @Override
    public ColumnHandle getUpdateRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> updatedColumns)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        List<DeltaLakeColumnHandle> unmodifiedColumns = getUnmodifiedColumns(handle, updatedColumns);

        Type rowIdType;
        if (unmodifiedColumns.isEmpty()) {
            rowIdType = RowType.rowType(RowType.field(BIGINT));
        }
        else {
            List<RowType.Field> unmodifiedColumnFields = unmodifiedColumns.stream()
                    .map(columnMetadata -> RowType.field(columnMetadata.getName(), columnMetadata.getType()))
                    .collect(toImmutableList());
            rowIdType = RowType.rowType(
                    RowType.field(BIGINT),
                    RowType.field(RowType.from(unmodifiedColumnFields)));
        }

        return new DeltaLakeColumnHandle(ROW_ID_COLUMN_NAME, rowIdType, ROW_ID_COLUMN_NAME, rowIdType, SYNTHESIZED);
    }

    @Override
    public ConnectorTableHandle beginUpdate(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> updatedColumns, RetryMode retryMode)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        if (isAppendOnly(handle.getMetadataEntry())) {
            throw new TrinoException(NOT_SUPPORTED, "Cannot update rows from a table with '" + APPEND_ONLY_CONFIGURATION_KEY + "' set to true");
        }
        if (!allowWrite(session, handle)) {
            String fileSystem = new Path(handle.getLocation()).toUri().getScheme();
            throw new TrinoException(NOT_SUPPORTED, format("Updates are not supported on the %s filesystem", fileSystem));
        }
        checkSupportedWriterVersion(session, handle.getSchemaTableName());

        List<DeltaLakeColumnHandle> updatedColumnHandles = updatedColumns.stream()
                .map(columnHandle -> (DeltaLakeColumnHandle) columnHandle)
                .collect(toImmutableList());

        Set<String> partitionColumnNames = ImmutableSet.copyOf(handle.getMetadataEntry().getCanonicalPartitionColumns());
        if (updatedColumnHandles.stream()
                .map(DeltaLakeColumnHandle::getName)
                .anyMatch(partitionColumnNames::contains)) {
            throw new TrinoException(NOT_SUPPORTED, "Updating table partition columns is not supported");
        }

        List<DeltaLakeColumnHandle> unmodifiedColumns = getUnmodifiedColumns(handle, updatedColumns);

        return DeltaLakeTableHandle.forUpdate(
                handle.getSchemaName(),
                handle.getTableName(),
                handle.getLocation(),
                Optional.of(handle.getMetadataEntry()),
                handle.getEnforcedPartitionConstraint(),
                handle.getNonPartitionConstraint(),
                handle.getProjectedColumns(),
                updatedColumnHandles,
                unmodifiedColumns,
                handle.getReadVersion(),
                retryMode != NO_RETRIES);
    }

    @Override
    public void finishUpdate(ConnectorSession session, ConnectorTableHandle tableHandle, Collection<Slice> fragments)
    {
        finishWrite(session, tableHandle, fragments);
    }

    @Override
    public Optional<ConnectorTableExecuteHandle> getTableHandleForExecute(
            ConnectorSession session,
            ConnectorTableHandle connectorTableHandle,
            String procedureName,
            Map<String, Object> executeProperties,
            RetryMode retryMode)
    {
        DeltaLakeTableHandle tableHandle = (DeltaLakeTableHandle) connectorTableHandle;

        DeltaLakeTableProcedureId procedureId;
        try {
            procedureId = DeltaLakeTableProcedureId.valueOf(procedureName);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown procedure '" + procedureName + "'");
        }

        switch (procedureId) {
            case OPTIMIZE:
                return getTableHandleForOptimize(tableHandle, executeProperties, retryMode);
        }

        throw new IllegalArgumentException("Unknown procedure: " + procedureId);
    }

    private Optional<ConnectorTableExecuteHandle> getTableHandleForOptimize(DeltaLakeTableHandle tableHandle, Map<String, Object> executeProperties, RetryMode retryMode)
    {
        DataSize maxScannedFileSize = (DataSize) executeProperties.get("file_size_threshold");

        List<DeltaLakeColumnHandle> columns = getColumns(tableHandle.getMetadataEntry()).stream()
                .filter(column -> column.getColumnType() != SYNTHESIZED)
                .collect(toImmutableList());

        return Optional.of(new DeltaLakeTableExecuteHandle(
                tableHandle.getSchemaTableName(),
                OPTIMIZE,
                new DeltaTableOptimizeHandle(
                        tableHandle.getMetadataEntry(),
                        columns,
                        tableHandle.getMetadataEntry().getOriginalPartitionColumns(),
                        maxScannedFileSize,
                        Optional.empty(),
                        retryMode != NO_RETRIES),
                tableHandle.getLocation()));
    }

    @Override
    public Optional<ConnectorTableLayout> getLayoutForTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle)
    {
        DeltaLakeTableExecuteHandle executeHandle = (DeltaLakeTableExecuteHandle) tableExecuteHandle;
        switch (executeHandle.getProcedureId()) {
            case OPTIMIZE:
                return getLayoutForOptimize(executeHandle);
        }
        throw new IllegalArgumentException("Unknown procedure '" + executeHandle.getProcedureId() + "'");
    }

    private Optional<ConnectorTableLayout> getLayoutForOptimize(DeltaLakeTableExecuteHandle executeHandle)
    {
        DeltaTableOptimizeHandle optimizeHandle = (DeltaTableOptimizeHandle) executeHandle.getProcedureHandle();
        List<String> partitionColumnNames = optimizeHandle.getMetadataEntry().getCanonicalPartitionColumns();
        if (partitionColumnNames.isEmpty()) {
            return Optional.empty();
        }
        Map<String, DeltaLakeColumnHandle> columnsByName = optimizeHandle.getTableColumns().stream()
                .collect(toImmutableMap(columnHandle -> columnHandle.getName().toLowerCase(Locale.ENGLISH), identity()));
        ImmutableList.Builder<DeltaLakeColumnHandle> partitioningColumns = ImmutableList.builder();
        for (String columnName : partitionColumnNames) {
            partitioningColumns.add(columnsByName.get(columnName));
        }
        DeltaLakePartitioningHandle partitioningHandle = new DeltaLakePartitioningHandle(partitioningColumns.build());
        return Optional.of(new ConnectorTableLayout(partitioningHandle, partitionColumnNames));
    }

    @Override
    public BeginTableExecuteResult<ConnectorTableExecuteHandle, ConnectorTableHandle> beginTableExecute(
            ConnectorSession session,
            ConnectorTableExecuteHandle tableExecuteHandle,
            ConnectorTableHandle updatedSourceTableHandle)
    {
        DeltaLakeTableExecuteHandle executeHandle = (DeltaLakeTableExecuteHandle) tableExecuteHandle;
        DeltaLakeTableHandle table = (DeltaLakeTableHandle) updatedSourceTableHandle;
        switch (executeHandle.getProcedureId()) {
            case OPTIMIZE:
                return beginOptimize(session, executeHandle, table);
        }
        throw new IllegalArgumentException("Unknown procedure '" + executeHandle.getProcedureId() + "'");
    }

    private BeginTableExecuteResult<ConnectorTableExecuteHandle, ConnectorTableHandle> beginOptimize(
            ConnectorSession session,
            DeltaLakeTableExecuteHandle executeHandle,
            DeltaLakeTableHandle table)
    {
        DeltaTableOptimizeHandle optimizeHandle = (DeltaTableOptimizeHandle) executeHandle.getProcedureHandle();

        if (!allowWrite(session, table)) {
            String fileSystem = new Path(table.getLocation()).toUri().getScheme();
            throw new TrinoException(NOT_SUPPORTED, format("Optimize is not supported on the %s filesystem", fileSystem));
        }
        checkSupportedWriterVersion(session, table.getSchemaTableName());

        return new BeginTableExecuteResult<>(
                executeHandle.withProcedureHandle(optimizeHandle.withCurrentVersion(table.getReadVersion())),
                table.forOptimize(true, optimizeHandle.getMaxScannedFileSize()));
    }

    @Override
    public void finishTableExecute(ConnectorSession session, ConnectorTableExecuteHandle tableExecuteHandle, Collection<Slice> fragments, List<Object> splitSourceInfo)
    {
        DeltaLakeTableExecuteHandle executeHandle = (DeltaLakeTableExecuteHandle) tableExecuteHandle;
        switch (executeHandle.getProcedureId()) {
            case OPTIMIZE:
                finishOptimize(session, executeHandle, fragments, splitSourceInfo);
                return;
        }
        throw new IllegalArgumentException("Unknown procedure '" + executeHandle.getProcedureId() + "'");
    }

    private void finishOptimize(ConnectorSession session, DeltaLakeTableExecuteHandle executeHandle, Collection<Slice> fragments, List<Object> splitSourceInfo)
    {
        DeltaTableOptimizeHandle optimizeHandle = (DeltaTableOptimizeHandle) executeHandle.getProcedureHandle();
        long readVersion = optimizeHandle.getCurrentVersion().orElseThrow(() -> new IllegalArgumentException("currentVersion not set"));
        String tableLocation = executeHandle.getTableLocation();

        // paths to be deleted
        Set<Path> scannedPaths = splitSourceInfo.stream()
                .map(file -> new Path((String) file))
                .collect(toImmutableSet());

        // files to be added
        List<DataFileInfo> dataFileInfos = fragments.stream()
                .map(Slice::getBytes)
                .map(dataFileInfoCodec::fromJson)
                .collect(toImmutableList());

        if (optimizeHandle.isRetriesEnabled()) {
            cleanExtraOutputFiles(session, executeHandle.getTableLocation(), dataFileInfos);
        }

        boolean writeCommitted = false;
        try {
            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, tableLocation);

            long createdTime = Instant.now().toEpochMilli();
            long commitVersion = readVersion + 1;
            transactionLogWriter.appendCommitInfoEntry(
                    new CommitInfoEntry(
                            commitVersion,
                            createdTime,
                            session.getUser(),
                            session.getUser(),
                            OPTIMIZE_OPERATION,
                            ImmutableMap.of("queryId", session.getQueryId()),
                            null,
                            null,
                            "trino-" + nodeVersion + "-" + nodeId,
                            readVersion,
                            ISOLATION_LEVEL,
                            true));
            // TODO: Delta writes another field "operationMetrics" that I haven't
            //   seen before. It contains delete/update metrics. Investigate/include it.

            long writeTimestamp = Instant.now().toEpochMilli();

            for (Path scannedPath : scannedPaths) {
                String relativePath = new Path(tableLocation).toUri().relativize(scannedPath.toUri()).toString();
                transactionLogWriter.appendRemoveFileEntry(new RemoveFileEntry(relativePath, writeTimestamp, false));
            }

            // Note: during writes we want to preserve original case of partition columns
            List<String> partitionColumns = optimizeHandle.getMetadataEntry().getOriginalPartitionColumns();
            appendAddFileEntries(transactionLogWriter, dataFileInfos, partitionColumns, false);

            transactionLogWriter.flush();
            writeCommitted = true;
            Optional<Long> checkpointInterval = Optional.of(1L); // force checkpoint
            writeCheckpointIfNeeded(session, executeHandle.getSchemaTableName(), checkpointInterval, commitVersion);
        }
        catch (Exception e) {
            if (!writeCommitted) {
                // TODO perhaps it should happen in a background thread (https://github.com/trinodb/trino/issues/12011)
                cleanupFailedWrite(session, tableLocation, dataFileInfos);
            }
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Failed to write Delta Lake transaction log entry", e);
        }
    }

    private boolean allowWrite(ConnectorSession session, DeltaLakeTableHandle tableHandle)
    {
        boolean requiresOptIn = transactionLogWriterFactory.newWriter(session, tableHandle.getLocation()).isUnsafe();
        return !requiresOptIn || unsafeWritesEnabled;
    }

    private void checkSupportedWriterVersion(ConnectorSession session, SchemaTableName schemaTableName)
    {
        int requiredWriterVersion = metastore.getProtocol(session, metastore.getSnapshot(schemaTableName, session)).getMinWriterVersion();
        if (requiredWriterVersion > WRITER_VERSION) {
            throw new TrinoException(
                    NOT_SUPPORTED,
                    format("Table %s requires Delta Lake writer version %d which is not supported", schemaTableName, requiredWriterVersion));
        }
    }

    private List<DeltaLakeColumnHandle> getUnmodifiedColumns(DeltaLakeTableHandle tableHandle, List<ColumnHandle> updatedColumns)
    {
        Set<DeltaLakeColumnHandle> updatedColumnHandles = updatedColumns.stream()
                .map(columnHandle -> (DeltaLakeColumnHandle) columnHandle)
                .collect(toImmutableSet());
        Set<String> partitionColumnNames = ImmutableSet.copyOf(tableHandle.getMetadataEntry().getCanonicalPartitionColumns());
        List<DeltaLakeColumnMetadata> allColumns = extractSchema(tableHandle.getMetadataEntry(), typeManager);
        return allColumns.stream()
                .map(column -> toColumnHandle(column.getColumnMetadata(), column.getPhysicalName(), column.getPhysicalColumnType(), partitionColumnNames))
                .filter(columnHandle -> !updatedColumnHandles.contains(columnHandle))
                .filter(columnHandle -> !partitionColumnNames.contains(columnHandle.getName()))
                .collect(toImmutableList());
    }

    private void finishWrite(ConnectorSession session, ConnectorTableHandle tableHandle, Collection<Slice> fragments)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;

        List<DeltaLakeUpdateResult> updateResults = fragments.stream()
                .map(Slice::getBytes)
                .map(updateResultJsonCodec::fromJson)
                .collect(toImmutableList());

        if (handle.isRetriesEnabled()) {
            cleanExtraOutputFilesForUpdate(session, handle.getLocation(), updateResults);
        }

        String tableLocation = metastore.getTableLocation(handle.getSchemaTableName(), session);

        DeltaLakeTableHandle.WriteType writeType = handle.getWriteType().orElseThrow();
        String operation;
        switch (writeType) {
            case DELETE:
                operation = DELETE_OPERATION;
                break;
            case UPDATE:
                operation = UPDATE_OPERATION;
                break;
            default:
                throw new TrinoException(NOT_SUPPORTED, "Unsupported write type: " + writeType);
        }

        boolean writeCommitted = false;
        try {
            TransactionLogWriter transactionLogWriter = transactionLogWriterFactory.newWriter(session, tableLocation);

            long createdTime = Instant.now().toEpochMilli();

            FileSystem fileSystem = hdfsEnvironment.getFileSystem(new HdfsContext(session), new Path(tableLocation));
            long commitVersion = getMandatoryCurrentVersion(fileSystem, new Path(tableLocation)) + 1;
            if (commitVersion != handle.getReadVersion() + 1) {
                throw new TransactionConflictException(format("Conflicting concurrent writes found. Expected transaction log version: %s, actual version: %s",
                        handle.getReadVersion(),
                        commitVersion - 1));
            }
            Optional<Long> checkpointInterval = handle.getMetadataEntry().getCheckpointInterval();
            transactionLogWriter.appendCommitInfoEntry(
                    new CommitInfoEntry(
                            commitVersion,
                            createdTime,
                            session.getUser(),
                            session.getUser(),
                            operation,
                            ImmutableMap.of("queryId", session.getQueryId()),
                            null,
                            null,
                            "trino-" + nodeVersion + "-" + nodeId,
                            0, // TODO Insert fills this in with, probably should do so here too
                            ISOLATION_LEVEL,
                            true));
            // TODO: Delta writes another field "operationMetrics" that I haven't (https://github.com/trinodb/trino/issues/12005)
            //   seen before. It contains delete/update metrics. Investigate/include it.

            long writeTimestamp = Instant.now().toEpochMilli();

            for (DeltaLakeUpdateResult updateResult : updateResults) {
                transactionLogWriter.appendRemoveFileEntry(new RemoveFileEntry(updateResult.getOldFile(), writeTimestamp, true));
            }
            appendAddFileEntries(
                    transactionLogWriter,
                    updateResults.stream()
                            .map(DeltaLakeUpdateResult::getNewFile)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(toImmutableList()),
                    handle.getMetadataEntry().getOriginalPartitionColumns(),
                    true);

            transactionLogWriter.flush();
            writeCommitted = true;
            writeCheckpointIfNeeded(session, new SchemaTableName(handle.getSchemaName(), handle.getTableName()), checkpointInterval, commitVersion);
        }
        catch (Exception e) {
            if (!writeCommitted) {
                // TODO perhaps it should happen in a background thread (https://github.com/trinodb/trino/issues/12011)
                cleanupFailedWrite(session, tableLocation, updateResults.stream()
                        .map(DeltaLakeUpdateResult::getNewFile)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(toImmutableList()));
            }
            throw new TrinoException(DELTA_LAKE_BAD_WRITE, "Failed to write Delta Lake transaction log entry", e);
        }
    }

    private void writeCheckpointIfNeeded(ConnectorSession session, SchemaTableName table, Optional<Long> checkpointInterval, long newVersion)
    {
        try {
            // We are writing checkpoint synchronously. It should not be long lasting operation for tables where transaction log is not humongous.
            // Tables with really huge transaction logs would behave poorly in read flow already.
            TableSnapshot snapshot = metastore.getSnapshot(table, session);
            long lastCheckpointVersion = snapshot.getLastCheckpointVersion().orElse(0L);
            if (newVersion - lastCheckpointVersion < checkpointInterval.orElse(defaultCheckpointInterval)) {
                return;
            }

            // TODO: There is a race possibility here(https://github.com/trinodb/trino/issues/12004),
            // which may result in us not writing checkpoints at exactly the planned frequency.
            // The snapshot obtained above may already be on a version higher than `newVersion` because some other transaction could have just been committed.
            // This does not pose correctness issue but may be confusing if someone looks into transaction log.
            // To fix that we should allow for getting snapshot for given version.
            if (snapshot.getVersion() > newVersion) {
                LOG.info("Snapshot for table %s already at version %s when checkpoint requested for version %s", table, snapshot.getVersion(), newVersion);
            }

            checkpointWriterManager.writeCheckpoint(session, snapshot);
        }
        catch (Exception e) {
            if (ignoreCheckpointWriteFailures) {
                LOG.warn(e, "Failed to write checkpoint for table %s for version %s", table, newVersion);
            }
            else {
                throw new TrinoException(GENERIC_INTERNAL_ERROR, format("Failed to write checkpoint for table %s for version %s", table, newVersion), e);
            }
        }
    }

    private void cleanupFailedWrite(ConnectorSession session, String tableLocation, List<DataFileInfo> dataFiles)
    {
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(new HdfsContext(session), new Path(tableLocation));
            for (DataFileInfo dataFile : dataFiles) {
                fileSystem.delete(new Path(tableLocation, dataFile.getPath()), false);
            }
        }
        catch (Exception e) {
            // Can be safely ignored since a VACCUM from DeltaLake will take care of such orphaned files
            LOG.warn(e, "Failed cleanup of leftover files from failed write, files are: %s", dataFiles.stream()
                    .map(dataFileInfo -> new Path(tableLocation, dataFileInfo.getPath()))
                    .collect(toImmutableList()));
        }
    }

    @Override
    public Optional<Object> getInfo(ConnectorTableHandle table)
    {
        boolean isPartitioned = !((DeltaLakeTableHandle) table).getMetadataEntry().getCanonicalPartitionColumns().isEmpty();
        return Optional.of(new DeltaLakeInputInfo(isPartitioned));
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;

        Optional<Table> table = metastore.getTable(handle.getSchemaName(), handle.getTableName());
        if (table.isEmpty()) {
            throw new TableNotFoundException(handle.getSchemaTableName());
        }

        metastore.dropTable(session, handle.getSchemaName(), handle.getTableName());
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        Table table = metastore.getTable(handle.getSchemaName(), handle.getTableName())
                .orElseThrow(() -> new TableNotFoundException(handle.getSchemaTableName()));
        if (table.getTableType().equals(MANAGED_TABLE.name())) {
            throw new TrinoException(NOT_SUPPORTED, "Renaming managed tables is not supported");
        }
        metastore.renameTable(session, handle.getSchemaTableName(), newTableName);
    }

    @Override
    public Map<String, Object> getSchemaProperties(ConnectorSession session, CatalogSchemaName schemaName)
    {
        String schema = schemaName.getSchemaName();
        checkState(!schema.equals("information_schema") && !schema.equals("sys"), "Schema is not accessible: %s", schemaName);
        Optional<Database> db = metastore.getDatabase(schema);
        return db.map(DeltaLakeSchemaProperties::fromDatabase).orElseThrow(() -> new SchemaNotFoundException(schema));
    }

    @Override
    public void createRole(ConnectorSession session, String role, Optional<TrinoPrincipal> grantor)
    {
        accessControlMetadata.createRole(session, role, grantor.map(HivePrincipal::from));
    }

    @Override
    public void dropRole(ConnectorSession session, String role)
    {
        accessControlMetadata.dropRole(session, role);
    }

    @Override
    public Set<String> listRoles(ConnectorSession session)
    {
        return accessControlMetadata.listRoles(session);
    }

    @Override
    public Set<RoleGrant> listRoleGrants(ConnectorSession session, TrinoPrincipal principal)
    {
        return ImmutableSet.copyOf(accessControlMetadata.listRoleGrants(session, HivePrincipal.from(principal)));
    }

    @Override
    public void grantRoles(ConnectorSession session, Set<String> roles, Set<TrinoPrincipal> grantees, boolean withAdminOption, Optional<TrinoPrincipal> grantor)
    {
        accessControlMetadata.grantRoles(session, roles, HivePrincipal.from(grantees), withAdminOption, grantor.map(HivePrincipal::from));
    }

    @Override
    public void revokeRoles(ConnectorSession session, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOptionFor, Optional<TrinoPrincipal> grantor)
    {
        accessControlMetadata.revokeRoles(session, roles, HivePrincipal.from(grantees), adminOptionFor, grantor.map(HivePrincipal::from));
    }

    @Override
    public Set<RoleGrant> listApplicableRoles(ConnectorSession session, TrinoPrincipal principal)
    {
        return accessControlMetadata.listApplicableRoles(session, HivePrincipal.from(principal));
    }

    @Override
    public Set<String> listEnabledRoles(ConnectorSession session)
    {
        return accessControlMetadata.listEnabledRoles(session);
    }

    @Override
    public void grantTablePrivileges(ConnectorSession session, SchemaTableName schemaTableName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        accessControlMetadata.grantTablePrivileges(session, schemaTableName, privileges, HivePrincipal.from(grantee), grantOption);
    }

    @Override
    public void revokeTablePrivileges(ConnectorSession session, SchemaTableName schemaTableName, Set<Privilege> privileges, TrinoPrincipal grantee, boolean grantOption)
    {
        accessControlMetadata.revokeTablePrivileges(session, schemaTableName, privileges, HivePrincipal.from(grantee), grantOption);
    }

    @Override
    public List<GrantInfo> listTablePrivileges(ConnectorSession session, SchemaTablePrefix schemaTablePrefix)
    {
        return accessControlMetadata.listTablePrivileges(session, listTables(session, schemaTablePrefix));
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getTable().isEmpty()) {
            return listTables(session, prefix.getSchema());
        }
        SchemaTableName tableName = prefix.toSchemaTableName();
        return metastore.getTable(tableName.getSchemaName(), tableName.getTableName())
                .map(table -> ImmutableList.of(tableName))
                .orElse(ImmutableList.of());
    }

    private void setRollback(Runnable action)
    {
        checkState(rollbackAction.compareAndSet(null, action), "rollback action is already set");
    }

    private static String toUriFormat(String path)
    {
        return new Path(path).toUri().toString();
    }

    public void rollback()
    {
        // The actions are responsible for cleanup in case operation is aborted.
        // So far this is used by CTAS flow which does not require an explicit commit operation therefore
        // DeltaLakeMetadata does not define a commit() method.
        Optional.ofNullable(rollbackAction.getAndSet(null)).ifPresent(Runnable::run);
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
    {
        DeltaLakeTableHandle tableHandle = (DeltaLakeTableHandle) handle;
        SchemaTableName tableName = tableHandle.getSchemaTableName();

        Set<DeltaLakeColumnHandle> partitionColumns = ImmutableSet.copyOf(extractPartitionColumns(tableHandle.getMetadataEntry(), typeManager));
        Map<ColumnHandle, Domain> constraintDomains = constraint.getSummary().getDomains().orElseThrow(() -> new IllegalArgumentException("constraint summary is NONE"));

        ImmutableMap.Builder<DeltaLakeColumnHandle, Domain> enforceableDomains = ImmutableMap.builder();
        ImmutableMap.Builder<DeltaLakeColumnHandle, Domain> unenforceableDomains = ImmutableMap.builder();
        for (Map.Entry<ColumnHandle, Domain> domainEntry : constraintDomains.entrySet()) {
            DeltaLakeColumnHandle column = (DeltaLakeColumnHandle) domainEntry.getKey();
            if (!partitionColumns.contains(column)) {
                unenforceableDomains.put(column, domainEntry.getValue());
            }
            else {
                enforceableDomains.put(column, domainEntry.getValue());
            }
        }

        TupleDomain<DeltaLakeColumnHandle> newEnforcedConstraint = TupleDomain.withColumnDomains(enforceableDomains.buildOrThrow());
        TupleDomain<DeltaLakeColumnHandle> newUnenforcedConstraint = TupleDomain.withColumnDomains(unenforceableDomains.buildOrThrow());
        DeltaLakeTableHandle newHandle = new DeltaLakeTableHandle(
                tableName.getSchemaName(),
                tableName.getTableName(),
                tableHandle.getLocation(),
                Optional.of(tableHandle.getMetadataEntry()),
                // Do not simplify the enforced constraint, the connector is guaranteeing the constraint will be applied as is.
                // The unenforced constraint will still be checked by the engine.
                tableHandle.getEnforcedPartitionConstraint()
                        .intersect(newEnforcedConstraint),
                tableHandle.getNonPartitionConstraint()
                        .intersect(newUnenforcedConstraint)
                        .simplify(domainCompactionThreshold),
                tableHandle.getWriteType(),
                tableHandle.getProjectedColumns(),
                tableHandle.getUpdatedColumns(),
                tableHandle.getUpdateRowIdColumns(),
                Optional.empty(),
                tableHandle.getReadVersion(),
                tableHandle.isRetriesEnabled());

        if (tableHandle.getEnforcedPartitionConstraint().equals(newHandle.getEnforcedPartitionConstraint()) &&
                tableHandle.getNonPartitionConstraint().equals(newHandle.getNonPartitionConstraint())) {
            return Optional.empty();
        }

        return Optional.of(new ConstraintApplicationResult<>(
                newHandle,
                newUnenforcedConstraint.transformKeys(ColumnHandle.class::cast),
                false));
    }

    @Override
    public Optional<ProjectionApplicationResult<ConnectorTableHandle>> applyProjection(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            List<ConnectorExpression> projections,
            Map<String, ColumnHandle> assignments)
    {
        DeltaLakeTableHandle deltaLakeTableHandle = (DeltaLakeTableHandle) tableHandle;
        Set<ColumnHandle> projectedColumns = ImmutableSet.copyOf(assignments.values());

        if (deltaLakeTableHandle.getProjectedColumns().isPresent() &&
                deltaLakeTableHandle.getProjectedColumns().get().equals(projectedColumns)) {
            return Optional.empty();
        }

        List<ConnectorExpression> simpleProjections = projections.stream()
                .filter(projection -> projection instanceof Variable)
                .collect(toImmutableList());

        List<Assignment> newColumnAssignments = assignments.entrySet().stream()
                .map(assignment -> new Assignment(
                        assignment.getKey(),
                        assignment.getValue(),
                        ((DeltaLakeColumnHandle) assignment.getValue()).getType()))
                .collect(toImmutableList());

        return Optional.of(new ProjectionApplicationResult<>(
                deltaLakeTableHandle.withProjectedColumns(projectedColumns),
                simpleProjections,
                newColumnAssignments,
                false));
    }

    @Override
    public Optional<TableScanRedirectApplicationResult> applyTableScanRedirect(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return deltaLakeRedirectionsProvider.getTableScanRedirection(session, (DeltaLakeTableHandle) tableHandle);
    }

    @Override
    public ConnectorAnalyzeMetadata getStatisticsCollectionMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, Map<String, Object> analyzeProperties)
    {
        if (!isExtendedStatisticsEnabled(session)) {
            throw new TrinoException(
                    NOT_SUPPORTED,
                    "ANALYZE not supported if extended statistics are disabled. Enable via delta.extended-statistics.enabled config property or extended_statistics_enabled session property.");
        }

        DeltaLakeTableHandle handle = (DeltaLakeTableHandle) tableHandle;
        MetadataEntry metadata = handle.getMetadataEntry();

        Optional<Instant> filesModifiedAfterFromProperties = DeltaLakeAnalyzeProperties.getFilesModifiedAfterProperty(analyzeProperties);

        Optional<ExtendedStatistics> statistics = statisticsAccess.readExtendedStatistics(session, handle.getLocation());

        Optional<Instant> alreadyAnalyzedModifiedTimeMax = statistics.map(ExtendedStatistics::getAlreadyAnalyzedModifiedTimeMax);

        // determine list of files we want to read based on what caller requested via files_modified_after and what files were already analyzed in the past
        Optional<Instant> filesModifiedAfter = Optional.empty();
        if (filesModifiedAfterFromProperties.isPresent() || alreadyAnalyzedModifiedTimeMax.isPresent()) {
            filesModifiedAfter = Optional.of(Comparators.max(
                    filesModifiedAfterFromProperties.orElse(Instant.ofEpochMilli(0)),
                    alreadyAnalyzedModifiedTimeMax.orElse(Instant.ofEpochMilli(0))));
        }

        Optional<Set<String>> analyzeColumnNames = DeltaLakeAnalyzeProperties.getColumnNames(analyzeProperties);
        if (analyzeColumnNames.isPresent()) {
            Set<String> columnNames = analyzeColumnNames.get();
            // validate that proper column names are passed via `columns` analyze property
            if (columnNames.isEmpty()) {
                throw new TrinoException(INVALID_ANALYZE_PROPERTY, "Cannot specify empty list of columns for analysis");
            }

            Set<String> allColumnNames = extractColumnMetadata(metadata, typeManager).stream()
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableSet());
            if (!allColumnNames.containsAll(columnNames)) {
                throw new TrinoException(
                        INVALID_ANALYZE_PROPERTY,
                        format("Invalid columns specified for analysis: %s", Sets.difference(columnNames, allColumnNames)));
            }
        }

        // verify that we do not extend set of analyzed columns
        Optional<Set<String>> oldAnalyzeColumnNames = statistics.flatMap(ExtendedStatistics::getAnalyzedColumns);
        if (oldAnalyzeColumnNames.isPresent()) {
            if (analyzeColumnNames.isEmpty() || !oldAnalyzeColumnNames.get().containsAll(analyzeColumnNames.get())) {
                throw new TrinoException(INVALID_ANALYZE_PROPERTY,
                        "List of columns to be analyzed must be a subset of previously used. To extend list of analyzed columns drop table statistics");
            }
        }

        AnalyzeHandle analyzeHandle = new AnalyzeHandle(statistics.isEmpty(), filesModifiedAfter, analyzeColumnNames);
        DeltaLakeTableHandle newHandle = new DeltaLakeTableHandle(
                handle.getSchemaTableName().getSchemaName(),
                handle.getSchemaTableName().getTableName(),
                handle.getLocation(),
                Optional.of(metadata),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(analyzeHandle),
                handle.getReadVersion(),
                false);

        ImmutableSet.Builder<ColumnStatisticMetadata> columnStatistics = ImmutableSet.builder();
        extractColumnMetadata(metadata, typeManager).stream()
                .filter(DeltaLakeMetadata::shouldCollectExtendedStatistics)
                .filter(columnMetadata ->
                        analyzeColumnNames
                                .map(columnNames -> columnNames.contains(columnMetadata.getName()))
                                .orElse(true))
                .forEach(columnMetadata -> {
                    if (!(columnMetadata.getType() instanceof FixedWidthType)) {
                        if (statistics.isEmpty() || totalSizeStatisticsExists(statistics.get().getColumnStatistics(), columnMetadata.getName())) {
                            columnStatistics.add(new ColumnStatisticMetadata(columnMetadata.getName(), TOTAL_SIZE_IN_BYTES));
                        }
                    }
                    columnStatistics.add(new ColumnStatisticMetadata(columnMetadata.getName(), NUMBER_OF_DISTINCT_VALUES_SUMMARY));
                });

        // collect max(file modification time) for sake of incremental ANALYZE
        columnStatistics.add(new ColumnStatisticMetadata(FILE_MODIFIED_TIME_COLUMN_NAME, MAX_VALUE));

        TableStatisticsMetadata statisticsMetadata = new TableStatisticsMetadata(
                columnStatistics.build(),
                ImmutableSet.of(),
                ImmutableList.of());

        return new ConnectorAnalyzeMetadata(newHandle, statisticsMetadata);
    }

    private static boolean shouldCollectExtendedStatistics(ColumnMetadata columnMetadata)
    {
        if (columnMetadata.isHidden()) {
            return false;
        }
        Type type = columnMetadata.getType();
        if (type instanceof MapType || type instanceof RowType || type instanceof ArrayType) {
            return false;
        }
        return true;
    }

    private static boolean totalSizeStatisticsExists(Map<String, DeltaLakeColumnStatistics> statistics, String columnName)
    {
        return statistics.containsKey(columnName) && statistics.get(columnName).getTotalSizeInBytes().isPresent();
    }

    @Override
    public ConnectorTableHandle beginStatisticsCollection(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        // nothing to be done here
        return tableHandle;
    }

    @Override
    public void finishStatisticsCollection(ConnectorSession session, ConnectorTableHandle table, Collection<ComputedStatistics> computedStatistics)
    {
        DeltaLakeTableHandle tableHandle = (DeltaLakeTableHandle) table;
        AnalyzeHandle analyzeHandle = tableHandle.getAnalyzeHandle().orElseThrow(() -> new IllegalArgumentException("analyzeHandle not set"));
        String location = metastore.getTableLocation(tableHandle.getSchemaTableName(), session);
        Optional<ExtendedStatistics> oldStatistics = statisticsAccess.readExtendedStatistics(session, location);

        // more elaborate logic for handling statistics model evaluation may need to be introduced in the future
        // for now let's have a simple check rejecting update
        oldStatistics.ifPresent(statistics ->
                checkArgument(
                        statistics.getModelVersion() == ExtendedStatistics.CURRENT_MODEL_VERSION,
                        "Existing table statistics are incompatible, run the drop statistics procedure on this table before re-analyzing"));

        Map<String, DeltaLakeColumnStatistics> oldColumnStatistics = oldStatistics.map(ExtendedStatistics::getColumnStatistics)
                .orElseGet(ImmutableMap::of);
        Map<String, DeltaLakeColumnStatistics> newColumnStatistics = toDeltaLakeColumnStatistics(computedStatistics);

        Map<String, DeltaLakeColumnStatistics> mergedColumnStatistics = new HashMap<>();

        // only keep stats for existing columns
        Set<String> newColumns = newColumnStatistics.keySet();
        oldColumnStatistics.entrySet().stream()
                .filter(entry -> newColumns.contains(entry.getKey()))
                .forEach(entry -> mergedColumnStatistics.put(entry.getKey(), entry.getValue()));

        newColumnStatistics.forEach((columnName, columnStatistics) -> {
            mergedColumnStatistics.merge(columnName, columnStatistics, DeltaLakeColumnStatistics::update);
        });

        Optional<Instant> maxFileModificationTime = getMaxFileModificationTime(computedStatistics);
        // We do not want to hinder our future calls to ANALYZE if one of the files we analyzed have modification time far in the future.
        // Therefore we cap the value we store in extended_stats.json to current_time as observed on Trino coordinator.
        Instant finalAlreadyAnalyzedModifiedTimeMax = Instant.now();
        if (maxFileModificationTime.isPresent()) {
            finalAlreadyAnalyzedModifiedTimeMax = Comparators.min(maxFileModificationTime.get(), finalAlreadyAnalyzedModifiedTimeMax);
        }
        // also ensure that we are not traveling back in time
        if (oldStatistics.isPresent()) {
            finalAlreadyAnalyzedModifiedTimeMax = Comparators.max(oldStatistics.get().getAlreadyAnalyzedModifiedTimeMax(), finalAlreadyAnalyzedModifiedTimeMax);
        }

        if (analyzeHandle.getColumns().isPresent() && !mergedColumnStatistics.keySet().equals(analyzeHandle.getColumns().get())) {
            // sanity validation
            throw new IllegalStateException(
                    format("Unexpected columns in in mergedColumnStatistics %s; expected %s",
                            mergedColumnStatistics.keySet(),
                            analyzeHandle.getColumns().get()));
        }

        ExtendedStatistics mergedExtendedStatistics = new ExtendedStatistics(
                finalAlreadyAnalyzedModifiedTimeMax,
                mergedColumnStatistics,
                analyzeHandle.getColumns());

        statisticsAccess.updateExtendedStatistics(session, location, mergedExtendedStatistics);
    }

    @Override
    public boolean supportsReportingWrittenBytes(ConnectorSession session, ConnectorTableHandle connectorTableHandle)
    {
        return true;
    }

    @Override
    public boolean supportsReportingWrittenBytes(ConnectorSession session, SchemaTableName fullTableName, Map<String, Object> tableProperties)
    {
        return true;
    }

    private void cleanExtraOutputFiles(ConnectorSession session, String baseLocation, List<DataFileInfo> validDataFiles)
    {
        Set<String> writtenFilePaths = validDataFiles.stream()
                .map(dataFileInfo -> baseLocation + "/" + dataFileInfo.getPath())
                .collect(toImmutableSet());

        cleanExtraOutputFiles(session, writtenFilePaths);
    }

    private void cleanExtraOutputFilesForUpdate(ConnectorSession session, String baseLocation, List<DeltaLakeUpdateResult> validUpdateResults)
    {
        Set<String> writtenFilePaths = validUpdateResults.stream()
                .map(DeltaLakeUpdateResult::getNewFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(dataFileInfo -> baseLocation + "/" + dataFileInfo.getPath())
                .collect(toImmutableSet());

        cleanExtraOutputFiles(session, writtenFilePaths);
    }

    private void cleanExtraOutputFiles(ConnectorSession session, Set<String> validWrittenFilePaths)
    {
        HdfsContext hdfsContext = new HdfsContext(session);

        Set<String> fileLocations = validWrittenFilePaths.stream()
                .map(path -> {
                    int fileNameSeparatorPos = path.lastIndexOf("/");
                    verify(fileNameSeparatorPos != -1 && fileNameSeparatorPos != 0, "invalid data file path: %s", path);
                    return path.substring(0, fileNameSeparatorPos);
                })
                .collect(toImmutableSet());

        for (String location : fileLocations) {
            cleanExtraOutputFiles(hdfsContext, session.getQueryId(), location, validWrittenFilePaths);
        }
    }

    private void cleanExtraOutputFiles(HdfsContext hdfsContext, String queryId, String location, Set<String> filesToKeep)
    {
        Deque<String> filesToDelete = new ArrayDeque<>();
        try {
            LOG.debug("Deleting failed attempt files from %s for query %s", location, queryId);
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(hdfsContext, new Path(location));
            if (!fileSystem.exists(new Path(location))) {
                // directory may not exist if no files were actually written
                return;
            }

            // files within given partition are written flat into location; we need to list recursively
            RemoteIterator<LocatedFileStatus> iterator = fileSystem.listFiles(new Path(location), false);
            while (iterator.hasNext()) {
                Path file = iterator.next().getPath();
                if (isFileCreatedByQuery(file.getName(), queryId) && !filesToKeep.contains(location + "/" + file.getName())) {
                    filesToDelete.add(file.getName());
                }
            }

            if (filesToDelete.isEmpty()) {
                return;
            }

            LOG.info("Found %s files to delete and %s to retain in location %s for query %s", filesToDelete.size(), filesToKeep.size(), location, queryId);
            ImmutableList.Builder<String> deletedFilesBuilder = ImmutableList.builder();
            Iterator<String> filesToDeleteIterator = filesToDelete.iterator();
            while (filesToDeleteIterator.hasNext()) {
                String fileName = filesToDeleteIterator.next();
                LOG.debug("Deleting failed attempt file %s/%s for query %s", location, fileName, queryId);
                fileSystem.delete(new Path(location, fileName), false);
                deletedFilesBuilder.add(fileName);
                filesToDeleteIterator.remove();
            }

            List<String> deletedFiles = deletedFilesBuilder.build();
            if (!deletedFiles.isEmpty()) {
                LOG.info("Deleted failed attempt files %s from %s for query %s", deletedFiles, location, queryId);
            }
        }
        catch (IOException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR,
                    format("Could not clean up extraneous output files; remaining files: %s", filesToDelete), e);
        }
    }

    private boolean isFileCreatedByQuery(String fileName, String queryId)
    {
        verify(!queryId.contains("-"), "queryId(%s) should not contain hyphens", queryId);
        return fileName.startsWith(queryId + "-");
    }

    private static Map<String, DeltaLakeColumnStatistics> toDeltaLakeColumnStatistics(Collection<ComputedStatistics> computedStatistics)
    {
        // Only statistics for whole table are collected
        ComputedStatistics singleStatistics = Iterables.getOnlyElement(computedStatistics);
        return createColumnToComputedStatisticsMap(singleStatistics.getColumnStatistics()).entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> createDeltaLakeColumnStatistics(entry.getValue())));
    }

    private static Map<String, Map<ColumnStatisticType, Block>> createColumnToComputedStatisticsMap(Map<ColumnStatisticMetadata, Block> computedStatistics)
    {
        ImmutableTable.Builder<String, ColumnStatisticType, Block> result = ImmutableTable.builder();
        computedStatistics.forEach((metadata, block) -> {
            if (metadata.getColumnName().equals(FILE_MODIFIED_TIME_COLUMN_NAME)) {
                return;
            }
            if (!SUPPORTED_STATISTICS_TYPE.contains(metadata.getStatisticType())) {
                throw new TrinoException(
                        GENERIC_INTERNAL_ERROR,
                        "Unexpected statistics type " + metadata.getStatisticType() + " found for column " + metadata.getColumnName());
            }

            result.put(metadata.getColumnName(), metadata.getStatisticType(), block);
        });
        return result.buildOrThrow().rowMap();
    }

    private static DeltaLakeColumnStatistics createDeltaLakeColumnStatistics(Map<ColumnStatisticType, Block> computedStatistics)
    {
        OptionalLong totalSize = OptionalLong.empty();
        if (computedStatistics.containsKey(TOTAL_SIZE_IN_BYTES)) {
            totalSize = getLongValue(computedStatistics.get(TOTAL_SIZE_IN_BYTES));
        }
        HyperLogLog ndvSummary = getHyperLogLogForNdv(computedStatistics.get(NUMBER_OF_DISTINCT_VALUES_SUMMARY));
        return DeltaLakeColumnStatistics.create(totalSize, ndvSummary);
    }

    private static OptionalLong getLongValue(Block block)
    {
        if (block.isNull(0)) {
            return OptionalLong.of(0);
        }
        return OptionalLong.of(BIGINT.getLong(block, 0));
    }

    private static HyperLogLog getHyperLogLogForNdv(Block block)
    {
        if (block.isNull(0)) {
            return HyperLogLog.newInstance(4096); // number of buckets used by $approx_set
        }
        Slice serializedSummary = (Slice) blockToNativeValue(HyperLogLogType.HYPER_LOG_LOG, block);
        return HyperLogLog.newInstance(serializedSummary);
    }

    private static Optional<Instant> getMaxFileModificationTime(Collection<ComputedStatistics> computedStatistics)
    {
        // Only statistics for whole table are collected
        ComputedStatistics singleStatistics = Iterables.getOnlyElement(computedStatistics);

        return singleStatistics.getColumnStatistics().entrySet().stream()
                .filter(entry -> entry.getKey().getColumnName().equals(FILE_MODIFIED_TIME_COLUMN_NAME))
                .map(entry -> {
                    ColumnStatisticMetadata columnStatisticMetadata = entry.getKey();
                    if (columnStatisticMetadata.getStatisticType() != MAX_VALUE) {
                        throw new TrinoException(
                                GENERIC_INTERNAL_ERROR,
                                "Unexpected statistics type " + columnStatisticMetadata.getStatisticType() + " found for column " + columnStatisticMetadata.getColumnName());
                    }
                    if (entry.getValue().isNull(0)) {
                        return Optional.<Instant>empty();
                    }
                    return Optional.of(Instant.ofEpochMilli(unpackMillisUtc(TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS.getLong(entry.getValue(), 0))));
                })
                .findFirst()
                .orElseThrow();
    }

    public DeltaLakeMetastore getMetastore()
    {
        return metastore;
    }

    private static ColumnMetadata getColumnMetadata(DeltaLakeColumnHandle column, @Nullable String comment)
    {
        return ColumnMetadata.builder()
                .setName(column.getName())
                .setType(column.getType())
                .setHidden(column.getColumnType() == SYNTHESIZED)
                .setComment(Optional.ofNullable(comment))
                .build();
    }

    public static TupleDomain<DeltaLakeColumnHandle> createStatisticsPredicate(
            AddFileEntry addFileEntry,
            List<DeltaLakeColumnMetadata> schema,
            List<String> canonicalPartitionColumns)
    {
        return addFileEntry.getStats()
                .map(deltaLakeFileStatistics -> withColumnDomains(
                        schema.stream()
                                .filter(column -> canUseInPredicate(column.getColumnMetadata()))
                                .collect(toImmutableMap(
                                        column -> DeltaLakeMetadata.toColumnHandle(column.getColumnMetadata(), column.getPhysicalName(), column.getPhysicalColumnType(), canonicalPartitionColumns),
                                        column -> buildColumnDomain(column, deltaLakeFileStatistics, canonicalPartitionColumns)))))
                .orElseGet(TupleDomain::all);
    }

    private static boolean canUseInPredicate(ColumnMetadata column)
    {
        Type type = column.getType();
        return type.equals(TINYINT)
                || type.equals(SMALLINT)
                || type.equals(INTEGER)
                || type.equals(BIGINT)
                || type.equals(REAL)
                || type.equals(DOUBLE)
                || type.equals(BOOLEAN)
                || type.equals(DATE)
                || type instanceof TimestampWithTimeZoneType
                || type instanceof DecimalType
                || type.equals(VARCHAR);
    }

    private static Domain buildColumnDomain(DeltaLakeColumnMetadata column, DeltaLakeFileStatistics stats, List<String> canonicalPartitionColumns)
    {
        Optional<Long> nullCount = stats.getNullCount(column.getPhysicalName());
        if (nullCount.isEmpty()) {
            // No stats were collected for this column; this can happen in 2 scenarios:
            // 1. The column didn't exist in the schema when the data file was created
            // 2. The column does exist in the file, but Spark property 'delta.dataSkippingNumIndexedCols'
            //    was used to limit the number of columns for which stats are collected
            // Since we don't know which scenario we're dealing with, we can't make a decision to prune.
            return Domain.all(column.getType());
        }
        if (stats.getNumRecords().equals(nullCount)) {
            return Domain.onlyNull(column.getType());
        }

        boolean hasNulls = nullCount.get() > 0;
        DeltaLakeColumnHandle deltaLakeColumnHandle = toColumnHandle(column.getColumnMetadata(), column.getPhysicalName(), column.getPhysicalColumnType(), canonicalPartitionColumns);
        Optional<Object> minValue = stats.getMinColumnValue(deltaLakeColumnHandle);
        if (minValue.isPresent() && isFloatingPointNaN(column.getType(), minValue.get())) {
            return allValues(column.getType(), hasNulls);
        }
        if (isNotFinite(minValue, column.getType())) {
            minValue = Optional.empty();
        }
        Optional<Object> maxValue = stats.getMaxColumnValue(deltaLakeColumnHandle);
        if (maxValue.isPresent() && isFloatingPointNaN(column.getType(), maxValue.get())) {
            return allValues(column.getType(), hasNulls);
        }
        if (isNotFinite(maxValue, column.getType())) {
            maxValue = Optional.empty();
        }
        if (minValue.isPresent() && maxValue.isPresent()) {
            return Domain.create(
                    ofRanges(range(column.getType(), minValue.get(), true, maxValue.get(), true)),
                    hasNulls);
        }
        if (minValue.isPresent()) {
            return Domain.create(ofRanges(greaterThanOrEqual(column.getType(), minValue.get())), hasNulls);
        }

        return maxValue
                .map(value -> Domain.create(ofRanges(lessThanOrEqual(column.getType(), value)), hasNulls))
                .orElseGet(() -> Domain.all(column.getType()));
    }

    private static boolean isNotFinite(Optional<Object> value, Type type)
    {
        if (type.equals(DOUBLE)) {
            return value
                    .map(Double.class::cast)
                    .filter(val -> !Double.isFinite(val))
                    .isPresent();
        }
        if (type.equals(REAL)) {
            return value
                    .map(Long.class::cast)
                    .map(Math::toIntExact)
                    .map(Float::intBitsToFloat)
                    .filter(val -> !Float.isFinite(val))
                    .isPresent();
        }
        return false;
    }

    private static Domain allValues(Type type, boolean includeNull)
    {
        if (includeNull) {
            return Domain.all(type);
        }
        return Domain.notNull(type);
    }

    private static DeltaLakeColumnHandle toColumnHandle(ColumnMetadata column, String physicalName, Type physicalType, Collection<String> partitionColumns)
    {
        boolean isPartitionKey = partitionColumns.stream().anyMatch(partition -> partition.equalsIgnoreCase(column.getName()));
        return new DeltaLakeColumnHandle(
                column.getName(),
                column.getType(),
                physicalName,
                physicalType,
                isPartitionKey ? PARTITION_KEY : REGULAR);
    }

    private static Optional<String> getQueryId(Table table)
    {
        return Optional.ofNullable(table.getParameters().get(PRESTO_QUERY_ID_NAME));
    }
}
