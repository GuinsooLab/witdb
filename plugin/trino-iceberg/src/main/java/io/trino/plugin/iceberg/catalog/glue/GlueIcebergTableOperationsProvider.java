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
package io.trino.plugin.iceberg.catalog.glue;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.glue.AWSGlueAsync;
import io.trino.plugin.hive.HdfsEnvironment.HdfsContext;
import io.trino.plugin.hive.metastore.glue.GlueHiveMetastoreConfig;
import io.trino.plugin.hive.metastore.glue.GlueMetastoreStats;
import io.trino.plugin.iceberg.FileIoProvider;
import io.trino.plugin.iceberg.catalog.IcebergTableOperations;
import io.trino.plugin.iceberg.catalog.IcebergTableOperationsProvider;
import io.trino.plugin.iceberg.catalog.TrinoCatalog;
import io.trino.spi.connector.ConnectorSession;

import javax.inject.Inject;

import java.util.Optional;

import static io.trino.plugin.hive.metastore.glue.GlueHiveMetastore.createAsyncGlueClient;
import static java.util.Objects.requireNonNull;

public class GlueIcebergTableOperationsProvider
        implements IcebergTableOperationsProvider
{
    private final FileIoProvider fileIoProvider;
    private final AWSGlueAsync glueClient;
    private final GlueMetastoreStats stats;

    @Inject
    public GlueIcebergTableOperationsProvider(FileIoProvider fileIoProvider, GlueMetastoreStats stats, GlueHiveMetastoreConfig glueConfig, AWSCredentialsProvider credentialsProvider)
    {
        this.fileIoProvider = requireNonNull(fileIoProvider, "fileIoProvider is null");
        this.stats = requireNonNull(stats, "stats is null");
        requireNonNull(glueConfig, "glueConfig is null");
        requireNonNull(credentialsProvider, "credentialsProvider is null");
        this.glueClient = createAsyncGlueClient(glueConfig, credentialsProvider, Optional.empty(), stats.newRequestMetricsCollector());
    }

    @Override
    public IcebergTableOperations createTableOperations(
            TrinoCatalog catalog,
            ConnectorSession session,
            String database,
            String table,
            Optional<String> owner,
            Optional<String> location)
    {
        return new GlueIcebergTableOperations(
                glueClient,
                stats,
                fileIoProvider.createFileIo(new HdfsContext(session), session.getQueryId()),
                session,
                database,
                table,
                owner,
                location);
    }
}
