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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.units.Duration;
import io.trino.plugin.deltalake.transactionlog.writer.S3TransactionLogSynchronizer;
import io.trino.plugin.hive.parquet.ParquetWriterConfig;
import io.trino.testing.QueryRunner;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.plugin.deltalake.DeltaLakeQueryRunner.DELTA_CATALOG;
import static io.trino.testing.assertions.Assert.assertEventually;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDeltaLakeConnectorSmokeTest
        extends BaseDeltaLakeAwsConnectorSmokeTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();

    @Override
    protected QueryRunner createDeltaLakeQueryRunner(Map<String, String> connectorProperties)
            throws Exception
    {
        verify(!new ParquetWriterConfig().isParquetOptimizedWriterEnabled(), "This test assumes the optimized Parquet writer is disabled by default");
        return DeltaLakeQueryRunner.createS3DeltaLakeQueryRunner(
                DELTA_CATALOG,
                SCHEMA,
                ImmutableMap.<String, String>builder()
                        .putAll(connectorProperties)
                        .put("delta.enable-non-concurrent-writes", "true")
                        .put("hive.s3.max-connections", "2")
                        .buildOrThrow(),
                dockerizedMinioDataLake.getMinioAddress(),
                dockerizedMinioDataLake.getTestingHadoop());
    }

    @Test(dataProvider = "writesLockedQueryProvider")
    public void testWritesLocked(String writeStatement)
            throws Exception
    {
        String tableName = "test_writes_locked" + randomTableSuffix();
        try {
            assertUpdate(
                    format("CREATE TABLE %s (a_number, a_string) WITH (location = 's3://%s/%s') AS " +
                                    "VALUES (1, 'ala'), (2, 'ma')",
                            tableName,
                            bucketName,
                            tableName),
                    2);

            Set<String> originalFiles = getTableFiles(tableName).stream().collect(toImmutableSet());
            assertThat(originalFiles).isNotEmpty(); // sanity check

            String lockFilePath = lockTable(tableName, java.time.Duration.ofMinutes(5));
            assertThatThrownBy(() -> computeActual(format(writeStatement, tableName)))
                    .hasStackTraceContaining("Transaction log locked(1); lockingCluster=some_cluster; lockingQuery=some_query");
            assertThat(listLocks(tableName)).containsExactly(lockFilePath); // we should not delete exising, not-expired lock

            // files from failed write should be cleaned up
            Set<String> expectedFiles = ImmutableSet.<String>builder()
                    .addAll(originalFiles)
                    .add(lockFilePath)
                    .build();
            assertEventually(
                    new Duration(5, TimeUnit.SECONDS),
                    () -> assertThat(getTableFiles(tableName)).containsExactlyInAnyOrderElementsOf(expectedFiles));
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    @DataProvider
    public static Object[][] writesLockedQueryProvider()
    {
        return new Object[][] {
                {"INSERT INTO %s VALUES (3, 'kota'), (4, 'psa')"},
                {"UPDATE %s SET a_string = 'kota' WHERE a_number = 2"},
                {"DELETE FROM %s WHERE a_number = 1"},
        };
    }

    @Test(dataProvider = "writesLockExpiredValuesProvider")
    public void testWritesLockExpired(String writeStatement, String expectedValues)
            throws Exception
    {
        String tableName = "test_writes_locked" + randomTableSuffix();
        assertUpdate(
                format("CREATE TABLE %s (a_number, a_string) WITH (location = 's3://%s/%s') AS " +
                                "VALUES (1, 'ala'), (2, 'ma')",
                        tableName,
                        bucketName,
                        tableName),
                2);

        lockTable(tableName, java.time.Duration.ofSeconds(-5));
        assertUpdate(format(writeStatement, tableName), 1);
        assertQuery("SELECT * FROM " + tableName, expectedValues);
        assertThat(listLocks(tableName)).isEmpty(); // expired lock should be cleaned up

        assertUpdate("DROP TABLE " + tableName);
    }

    @DataProvider
    public static Object[][] writesLockExpiredValuesProvider()
    {
        return new Object[][] {
                {"INSERT INTO %s VALUES (3, 'kota')", "VALUES (1,'ala'), (2,'ma'), (3,'kota')"},
                {"UPDATE %s SET a_string = 'kota' WHERE a_number = 2", "VALUES (1,'ala'), (2,'kota')"},
                {"DELETE FROM %s WHERE a_number = 2", "VALUES (1,'ala')"},
        };
    }

    @Test(dataProvider = "writesLockInvalidContentsValuesProvider")
    public void testWritesLockInvalidContents(String writeStatement, String expectedValues)
    {
        String tableName = "test_writes_locked" + randomTableSuffix();
        assertUpdate(
                format("CREATE TABLE %s (a_number, a_string) WITH (location = 's3://%s/%s') AS " +
                                "VALUES (1, 'ala'), (2, 'ma')",
                        tableName,
                        bucketName,
                        tableName),
                2);

        String lockFilePath = invalidLockTable(tableName);
        assertUpdate(format(writeStatement, tableName), 1);
        assertQuery("SELECT * FROM " + tableName, expectedValues);
        assertThat(listLocks(tableName)).containsExactly(lockFilePath); // we should not delete unparsable lock file

        assertUpdate("DROP TABLE " + tableName);
    }

    @DataProvider
    public static Object[][] writesLockInvalidContentsValuesProvider()
    {
        return new Object[][] {
                {"INSERT INTO %s VALUES (3, 'kota')", "VALUES (1,'ala'), (2,'ma'), (3,'kota')"},
                {"UPDATE %s SET a_string = 'kota' WHERE a_number = 2", "VALUES (1,'ala'), (2,'kota')"},
                {"DELETE FROM %s WHERE a_number = 2", "VALUES (1,'ala')"},
        };
    }

    private String lockTable(String tableName, java.time.Duration lockDuration)
            throws Exception
    {
        String lockFilePath = format("%s/00000000000000000001.json.sb-lock_blah", getLockFileDirectory(tableName));
        String lockFileContents = OBJECT_MAPPER.writeValueAsString(
                new S3TransactionLogSynchronizer.LockFileContents("some_cluster", "some_query", Instant.now().plus(lockDuration).toEpochMilli()));
        dockerizedMinioDataLake.writeFile(lockFileContents.getBytes(UTF_8), lockFilePath);
        String lockUri = format("s3://%s/%s", bucketName, lockFilePath);
        assertThat(listLocks(tableName)).containsExactly(lockUri); // sanity check
        return lockUri;
    }

    private String invalidLockTable(String tableName)
    {
        String lockFilePath = format("%s/00000000000000000001.json.sb-lock_blah", getLockFileDirectory(tableName));
        String invalidLockFileContents = "some very wrong json contents";
        dockerizedMinioDataLake.writeFile(invalidLockFileContents.getBytes(UTF_8), lockFilePath);
        String lockUri = format("s3://%s/%s", bucketName, lockFilePath);
        assertThat(listLocks(tableName)).containsExactly(lockUri); // sanity check
        return lockUri;
    }

    private List<String> listLocks(String tableName)
    {
        List<String> paths = dockerizedMinioDataLake.listFiles(getLockFileDirectory(tableName));
        return paths.stream()
                .filter(path -> path.contains(".sb-lock_"))
                .map(path -> format("s3://%s/%s", bucketName, path))
                .collect(toImmutableList());
    }

    private String getLockFileDirectory(String tableName)
    {
        return format("%s/_delta_log/_sb_lock", tableName);
    }
}
