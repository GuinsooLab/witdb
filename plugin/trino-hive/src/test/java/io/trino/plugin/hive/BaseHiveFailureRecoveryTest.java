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

import io.trino.Session;
import io.trino.operator.RetryPolicy;
import io.trino.testing.ExtendedFailureRecoveryTest;
import org.testng.annotations.DataProvider;

import java.util.List;
import java.util.Optional;

import static io.trino.plugin.hive.HiveMetadata.MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class BaseHiveFailureRecoveryTest
        extends ExtendedFailureRecoveryTest
{
    protected BaseHiveFailureRecoveryTest(RetryPolicy retryPolicy)
    {
        super(retryPolicy);
    }

    @Override
    protected boolean areWriteRetriesSupported()
    {
        return true;
    }

    @Override
    protected void createPartitionedLineitemTable(String tableName, List<String> columns, String partitionColumn)
    {
        String sql = format(
                "CREATE TABLE %s WITH (format = 'TEXTFILE', partitioned_by=array['%s']) AS SELECT %s FROM tpch.tiny.lineitem",
                tableName,
                partitionColumn,
                String.join(",", columns));
        getQueryRunner().execute(sql);
    }

    @Override
    @DataProvider(name = "parallelTests", parallel = true)
    public Object[][] parallelTests()
    {
        return moreParallelTests(super.parallelTests(),
                parallelTest("testCreatePartitionedTable", this::testCreatePartitionedTable),
                parallelTest("testInsertIntoNewPartition", this::testInsertIntoNewPartition),
                parallelTest("testInsertIntoExistingPartition", this::testInsertIntoExistingPartition),
                parallelTest("testInsertIntoNewPartitionBucketed", this::testInsertIntoNewPartitionBucketed),
                parallelTest("testInsertIntoExistingPartitionBucketed", this::testInsertIntoExistingPartitionBucketed),
                parallelTest("testReplaceExistingPartition", this::testReplaceExistingPartition),
                parallelTest("testDeletePartitionWithSubquery", this::testDeletePartitionWithSubquery));
    }

    @Override
    // delete is unsupported for non ACID tables
    protected void testDelete()
    {
        assertThatThrownBy(super::testDelete)
                .hasMessageContaining(MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE);
    }

    @Override
    // delete is unsupported for non ACID tables
    protected void testDeleteWithSubquery()
    {
        assertThatThrownBy(super::testDelete)
                .hasMessageContaining(MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE);
    }

    @Override
    // update is unsupported for non ACID tables
    protected void testUpdate()
    {
        assertThatThrownBy(super::testUpdate)
                .hasMessageContaining(MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE);
    }

    @Override
    // update is unsupported for non ACID tables
    protected void testUpdateWithSubquery()
    {
        assertThatThrownBy(super::testUpdateWithSubquery)
                .hasMessageContaining(MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE);
    }

    @Override
    protected void testMerge()
    {
        assertThatThrownBy(super::testMerge)
                .hasMessageContaining("Modifying Hive table rows is only supported for transactional tables");
    }

    @Override
    // materialized views are currently not implemented by Hive connector
    protected void testRefreshMaterializedView()
    {
        assertThatThrownBy(super::testRefreshMaterializedView)
                .hasMessageContaining("This connector does not support creating materialized views");
    }

    protected void testCreatePartitionedTable()
    {
        testTableModification(
                Optional.empty(),
                "CREATE TABLE <table> WITH (partitioned_by = ARRAY['p']) AS SELECT *, 'partition1' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testInsertIntoNewPartition()
    {
        testTableModification(
                Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p']) AS SELECT *, 'partition1' p FROM orders"),
                "INSERT INTO <table> SELECT *, 'partition2' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testInsertIntoExistingPartition()
    {
        testTableModification(
                Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p']) AS SELECT *, 'partition1' p FROM orders"),
                "INSERT INTO <table> SELECT *, 'partition1' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testInsertIntoNewPartitionBucketed()
    {
        testTableModification(
                Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p'], bucketed_by = ARRAY['orderkey'], bucket_count = 4) AS SELECT *, 'partition1' p FROM orders"),
                "INSERT INTO <table> SELECT *, 'partition2' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testInsertIntoExistingPartitionBucketed()
    {
        testTableModification(
                Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p'], bucketed_by = ARRAY['orderkey'], bucket_count = 4) AS SELECT *, 'partition1' p FROM orders"),
                "INSERT INTO <table> SELECT *, 'partition1' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testReplaceExistingPartition()
    {
        testTableModification(
                Optional.of(Session.builder(getQueryRunner().getDefaultSession())
                        .setCatalogSessionProperty("hive", "insert_existing_partitions_behavior", "OVERWRITE")
                        .build()),
                Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p']) AS SELECT *, 'partition1' p FROM orders"),
                "INSERT INTO <table> SELECT *, 'partition1' p FROM orders",
                Optional.of("DROP TABLE <table>"));
    }

    protected void testDeletePartitionWithSubquery()
    {
        assertThatThrownBy(() -> {
            testTableModification(
                    Optional.of("CREATE TABLE <table> WITH (partitioned_by = ARRAY['p']) AS SELECT *, 0 p FROM orders"),
                    "DELETE FROM <table> WHERE p = (SELECT min(nationkey) FROM nation)",
                    Optional.of("DROP TABLE <table>"));
        }).hasMessageContaining(MODIFYING_NON_TRANSACTIONAL_TABLE_MESSAGE);
    }
}
