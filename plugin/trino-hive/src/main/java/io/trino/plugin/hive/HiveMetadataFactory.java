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
package io.trino.plugin.hive;

import io.airlift.concurrent.BoundedExecutor;
import io.airlift.json.JsonCodec;
import io.airlift.units.Duration;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.hdfs.HdfsEnvironment;
import io.trino.plugin.base.CatalogName;
import io.trino.plugin.hive.aws.athena.PartitionProjectionService;
import io.trino.plugin.hive.fs.DirectoryLister;
import io.trino.plugin.hive.fs.TransactionScopeCachingDirectoryLister;
import io.trino.plugin.hive.metastore.HiveMetastoreConfig;
import io.trino.plugin.hive.metastore.HiveMetastoreFactory;
import io.trino.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.trino.plugin.hive.security.AccessControlMetadataFactory;
import io.trino.plugin.hive.statistics.MetastoreHiveStatisticsProvider;
import io.trino.spi.connector.MetadataProvider;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.type.TypeManager;

import javax.inject.Inject;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.trino.plugin.hive.metastore.cache.CachingHiveMetastore.memoizeMetastore;
import static java.util.Objects.requireNonNull;

public class HiveMetadataFactory
        implements TransactionalMetadataFactory
{
    private final CatalogName catalogName;
    private final boolean skipDeletionForAlter;
    private final boolean skipTargetCleanupOnRollback;
    private final boolean writesToNonManagedTablesEnabled;
    private final boolean createsOfNonManagedTablesEnabled;
    private final boolean deleteSchemaLocationsFallback;
    private final boolean translateHiveViews;
    private final boolean hiveViewsRunAsInvoker;
    private final boolean hideDeltaLakeTables;
    private final long perTransactionCacheMaximumSize;
    private final HiveMetastoreFactory metastoreFactory;
    private final TrinoFileSystemFactory fileSystemFactory;
    private final HdfsEnvironment hdfsEnvironment;
    private final HivePartitionManager partitionManager;
    private final TypeManager typeManager;
    private final MetadataProvider metadataProvider;
    private final LocationService locationService;
    private final JsonCodec<PartitionUpdate> partitionUpdateCodec;
    private final BoundedExecutor fileSystemExecutor;
    private final BoundedExecutor dropExecutor;
    private final Executor updateExecutor;
    private final long maxPartitionDropsPerQuery;
    private final String trinoVersion;
    private final HiveRedirectionsProvider hiveRedirectionsProvider;
    private final Set<SystemTableProvider> systemTableProviders;
    private final HiveMaterializedViewMetadataFactory hiveMaterializedViewMetadataFactory;
    private final AccessControlMetadataFactory accessControlMetadataFactory;
    private final Optional<Duration> hiveTransactionHeartbeatInterval;
    private final ScheduledExecutorService heartbeatService;
    private final DirectoryLister directoryLister;
    private final long perTransactionFileStatusCacheMaximumSize;
    private final PartitionProjectionService partitionProjectionService;
    private final boolean allowTableRename;
    private final HiveTimestampPrecision hiveViewsTimestampPrecision;

    @Inject
    public HiveMetadataFactory(
            CatalogName catalogName,
            HiveConfig hiveConfig,
            HiveMetastoreConfig hiveMetastoreConfig,
            HiveMetastoreFactory metastoreFactory,
            TrinoFileSystemFactory fileSystemFactory,
            HdfsEnvironment hdfsEnvironment,
            HivePartitionManager partitionManager,
            ExecutorService executorService,
            @ForHiveTransactionHeartbeats ScheduledExecutorService heartbeatService,
            TypeManager typeManager,
            MetadataProvider metadataProvider,
            LocationService locationService,
            JsonCodec<PartitionUpdate> partitionUpdateCodec,
            NodeVersion nodeVersion,
            HiveRedirectionsProvider hiveRedirectionsProvider,
            Set<SystemTableProvider> systemTableProviders,
            HiveMaterializedViewMetadataFactory hiveMaterializedViewMetadataFactory,
            AccessControlMetadataFactory accessControlMetadataFactory,
            DirectoryLister directoryLister,
            PartitionProjectionService partitionProjectionService,
            @AllowHiveTableRename boolean allowTableRename)
    {
        this(
                catalogName,
                metastoreFactory,
                fileSystemFactory,
                hdfsEnvironment,
                partitionManager,
                hiveConfig.getMaxConcurrentFileSystemOperations(),
                hiveConfig.getMaxConcurrentMetastoreDrops(),
                hiveConfig.getMaxConcurrentMetastoreUpdates(),
                hiveConfig.getMaxPartitionDropsPerQuery(),
                hiveConfig.isSkipDeletionForAlter(),
                hiveConfig.isSkipTargetCleanupOnRollback(),
                hiveConfig.getWritesToNonManagedTablesEnabled(),
                hiveConfig.getCreatesOfNonManagedTablesEnabled(),
                hiveConfig.isDeleteSchemaLocationsFallback(),
                hiveConfig.isTranslateHiveViews(),
                hiveConfig.isHiveViewsRunAsInvoker(),
                hiveConfig.getPerTransactionMetastoreCacheMaximumSize(),
                hiveConfig.getHiveTransactionHeartbeatInterval(),
                hiveMetastoreConfig.isHideDeltaLakeTables(),
                typeManager,
                metadataProvider,
                locationService,
                partitionUpdateCodec,
                executorService,
                heartbeatService,
                nodeVersion.toString(),
                hiveRedirectionsProvider,
                systemTableProviders,
                hiveMaterializedViewMetadataFactory,
                accessControlMetadataFactory,
                directoryLister,
                hiveConfig.getPerTransactionFileStatusCacheMaximumSize(),
                partitionProjectionService,
                allowTableRename,
                hiveConfig.getTimestampPrecision());
    }

    public HiveMetadataFactory(
            CatalogName catalogName,
            HiveMetastoreFactory metastoreFactory,
            TrinoFileSystemFactory fileSystemFactory,
            HdfsEnvironment hdfsEnvironment,
            HivePartitionManager partitionManager,
            int maxConcurrentFileSystemOperations,
            int maxConcurrentMetastoreDrops,
            int maxConcurrentMetastoreUpdates,
            long maxPartitionDropsPerQuery,
            boolean skipDeletionForAlter,
            boolean skipTargetCleanupOnRollback,
            boolean writesToNonManagedTablesEnabled,
            boolean createsOfNonManagedTablesEnabled,
            boolean deleteSchemaLocationsFallback,
            boolean translateHiveViews,
            boolean hiveViewsRunAsInvoker,
            long perTransactionCacheMaximumSize,
            Optional<Duration> hiveTransactionHeartbeatInterval,
            boolean hideDeltaLakeTables,
            TypeManager typeManager,
            MetadataProvider metadataProvider,
            LocationService locationService,
            JsonCodec<PartitionUpdate> partitionUpdateCodec,
            ExecutorService executorService,
            ScheduledExecutorService heartbeatService,
            String trinoVersion,
            HiveRedirectionsProvider hiveRedirectionsProvider,
            Set<SystemTableProvider> systemTableProviders,
            HiveMaterializedViewMetadataFactory hiveMaterializedViewMetadataFactory,
            AccessControlMetadataFactory accessControlMetadataFactory,
            DirectoryLister directoryLister,
            long perTransactionFileStatusCacheMaximumSize,
            PartitionProjectionService partitionProjectionService,
            boolean allowTableRename,
            HiveTimestampPrecision hiveViewsTimestampPrecision)
    {
        this.catalogName = requireNonNull(catalogName, "catalogName is null");
        this.skipDeletionForAlter = skipDeletionForAlter;
        this.skipTargetCleanupOnRollback = skipTargetCleanupOnRollback;
        this.writesToNonManagedTablesEnabled = writesToNonManagedTablesEnabled;
        this.createsOfNonManagedTablesEnabled = createsOfNonManagedTablesEnabled;
        this.deleteSchemaLocationsFallback = deleteSchemaLocationsFallback;
        this.translateHiveViews = translateHiveViews;
        this.hiveViewsRunAsInvoker = hiveViewsRunAsInvoker;
        this.hideDeltaLakeTables = hideDeltaLakeTables;
        this.perTransactionCacheMaximumSize = perTransactionCacheMaximumSize;

        this.metastoreFactory = requireNonNull(metastoreFactory, "metastoreFactory is null");
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.partitionManager = requireNonNull(partitionManager, "partitionManager is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.metadataProvider = requireNonNull(metadataProvider, "metadataProvider is null");
        this.locationService = requireNonNull(locationService, "locationService is null");
        this.partitionUpdateCodec = requireNonNull(partitionUpdateCodec, "partitionUpdateCodec is null");
        this.trinoVersion = requireNonNull(trinoVersion, "trinoVersion is null");
        this.hiveRedirectionsProvider = requireNonNull(hiveRedirectionsProvider, "hiveRedirectionsProvider is null");
        this.systemTableProviders = requireNonNull(systemTableProviders, "systemTableProviders is null");
        this.hiveMaterializedViewMetadataFactory = requireNonNull(hiveMaterializedViewMetadataFactory, "hiveMaterializedViewMetadataFactory is null");
        this.accessControlMetadataFactory = requireNonNull(accessControlMetadataFactory, "accessControlMetadataFactory is null");
        this.hiveTransactionHeartbeatInterval = requireNonNull(hiveTransactionHeartbeatInterval, "hiveTransactionHeartbeatInterval is null");

        fileSystemExecutor = new BoundedExecutor(executorService, maxConcurrentFileSystemOperations);
        dropExecutor = new BoundedExecutor(executorService, maxConcurrentMetastoreDrops);
        if (maxConcurrentMetastoreUpdates == 1) {
            // this will serve as a kill switch in case we observe that parallel updates causes conflicts in metastore's DB side
            updateExecutor = directExecutor();
        }
        else {
            updateExecutor = new BoundedExecutor(executorService, maxConcurrentMetastoreUpdates);
        }
        this.maxPartitionDropsPerQuery = maxPartitionDropsPerQuery;
        this.heartbeatService = requireNonNull(heartbeatService, "heartbeatService is null");
        this.directoryLister = requireNonNull(directoryLister, "directoryLister is null");
        this.perTransactionFileStatusCacheMaximumSize = perTransactionFileStatusCacheMaximumSize;
        this.partitionProjectionService = requireNonNull(partitionProjectionService, "partitionProjectionService is null");
        this.allowTableRename = allowTableRename;
        this.hiveViewsTimestampPrecision = requireNonNull(hiveViewsTimestampPrecision, "hiveViewsTimestampPrecision is null");
    }

    @Override
    public TransactionalMetadata create(ConnectorIdentity identity, boolean autoCommit)
    {
        HiveMetastoreClosure hiveMetastoreClosure = new HiveMetastoreClosure(
                memoizeMetastore(metastoreFactory.createMetastore(Optional.of(identity)), perTransactionCacheMaximumSize)); // per-transaction cache

        DirectoryLister directoryLister = new TransactionScopeCachingDirectoryLister(this.directoryLister, perTransactionFileStatusCacheMaximumSize);

        SemiTransactionalHiveMetastore metastore = new SemiTransactionalHiveMetastore(
                hdfsEnvironment,
                hiveMetastoreClosure,
                fileSystemExecutor,
                dropExecutor,
                updateExecutor,
                skipDeletionForAlter,
                skipTargetCleanupOnRollback,
                deleteSchemaLocationsFallback,
                hiveTransactionHeartbeatInterval,
                heartbeatService,
                directoryLister);

        return new HiveMetadata(
                catalogName,
                metastore,
                autoCommit,
                fileSystemFactory,
                hdfsEnvironment,
                partitionManager,
                writesToNonManagedTablesEnabled,
                createsOfNonManagedTablesEnabled,
                translateHiveViews,
                hiveViewsRunAsInvoker,
                hideDeltaLakeTables,
                typeManager,
                metadataProvider,
                locationService,
                partitionUpdateCodec,
                trinoVersion,
                new MetastoreHiveStatisticsProvider(metastore),
                hiveRedirectionsProvider,
                systemTableProviders,
                hiveMaterializedViewMetadataFactory.create(hiveMetastoreClosure),
                accessControlMetadataFactory.create(metastore),
                directoryLister,
                partitionProjectionService,
                allowTableRename,
                maxPartitionDropsPerQuery,
                hiveViewsTimestampPrecision);
    }
}
