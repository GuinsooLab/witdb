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

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.trino.Session;
import io.trino.plugin.hive.HdfsConfig;
import io.trino.plugin.hive.HdfsConfigurationInitializer;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.HiveHdfsConfiguration;
import io.trino.plugin.hive.TestingHivePlugin;
import io.trino.plugin.hive.authentication.NoHdfsAuthentication;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.glue.DefaultGlueColumnStatisticsProviderFactory;
import io.trino.plugin.hive.metastore.glue.GlueHiveMetastore;
import io.trino.plugin.hive.metastore.glue.GlueHiveMetastoreConfig;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;

import java.nio.file.Path;
import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;

/**
 * Tests metadata operations on a schema which has a mix of Hive and Delta Lake tables.
 * <p>
 * Requires AWS credentials, which can be provided any way supported by the DefaultProviderChain
 * See https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
 */
public class TestDeltaLakeSharedGlueMetastoreWithTableRedirections
        extends BaseDeltaLakeSharedMetastoreWithTableRedirectionsTest
{
    private static final Logger LOG = Logger.get(TestDeltaLakeSharedGlueMetastoreWithTableRedirections.class);

    private Path dataDirectory;
    private HiveMetastore glueMetastore;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Session deltaLakeSession = testSessionBuilder()
                .setCatalog("delta_with_redirections")
                .setSchema(schema)
                .build();

        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(deltaLakeSession).build();

        this.dataDirectory = queryRunner.getCoordinator().getBaseDataDir().resolve("delta_lake_data");
        this.dataDirectory.toFile().deleteOnExit();

        queryRunner.installPlugin(new DeltaLakePlugin());
        queryRunner.createCatalog(
                "delta_with_redirections",
                "delta-lake",
                ImmutableMap.<String, String>builder()
                        .put("hive.metastore", "glue")
                        .put("hive.metastore.glue.default-warehouse-dir", dataDirectory.toString())
                        .put("delta.hive-catalog-name", "hive_with_redirections")
                        .buildOrThrow());

        HdfsConfig hdfsConfig = new HdfsConfig();
        HdfsEnvironment hdfsEnvironment = new HdfsEnvironment(
                new HiveHdfsConfiguration(new HdfsConfigurationInitializer(hdfsConfig), ImmutableSet.of()),
                hdfsConfig,
                new NoHdfsAuthentication());
        this.glueMetastore = new GlueHiveMetastore(
                hdfsEnvironment,
                new GlueHiveMetastoreConfig(),
                DefaultAWSCredentialsProviderChain.getInstance(),
                directExecutor(),
                new DefaultGlueColumnStatisticsProviderFactory(directExecutor(), directExecutor()),
                Optional.empty(),
                table -> true);
        queryRunner.installPlugin(new TestingHivePlugin(glueMetastore));
        queryRunner.createCatalog(
                "hive_with_redirections",
                "hive",
                ImmutableMap.of("hive.delta-lake-catalog-name", "delta_with_redirections"));

        queryRunner.execute("CREATE SCHEMA " + schema + " WITH (location = '" + dataDirectory.toString() + "')");
        queryRunner.execute("CREATE TABLE hive_with_redirections." + schema + ".hive_table (a_integer) WITH (format='PARQUET') AS VALUES 1, 2, 3");
        queryRunner.execute("CREATE TABLE delta_with_redirections." + schema + ".delta_table (a_varchar) AS VALUES 'a', 'b', 'c'");

        return queryRunner;
    }

    @AfterClass(alwaysRun = true)
    public void cleanup()
    {
        try {
            if (glueMetastore != null) {
                // Data is on the local disk and will be deleted by the deleteOnExit hook
                glueMetastore.dropDatabase(schema, false);
            }
        }
        catch (Exception e) {
            LOG.error(e, "Failed to clean up Glue database: %s", schema);
        }
    }

    @Override
    protected String getExpectedHiveCreateSchema(String catalogName)
    {
        String expectedHiveCreateSchema = "CREATE SCHEMA %s.%s\n" +
                "AUTHORIZATION ROLE public\n" +
                "WITH (\n" +
                "   location = '%s'\n" +
                ")";

        return format(expectedHiveCreateSchema, catalogName, schema, dataDirectory);
    }

    @Override
    protected String getExpectedDeltaLakeCreateSchema(String catalogName)
    {
        String expectedDeltaLakeCreateSchema = "CREATE SCHEMA %s.%s\n" +
                "WITH (\n" +
                "   location = '%s'\n" +
                ")";
        return format(expectedDeltaLakeCreateSchema, catalogName, schema, dataDirectory, schema);
    }
}
