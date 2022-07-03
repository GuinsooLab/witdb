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
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.DataSize;
import io.trino.Session;
import io.trino.metadata.Metadata;
import io.trino.metadata.QualifiedObjectName;
import io.trino.metadata.TableHandle;
import io.trino.operator.OperatorStats;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.spi.QueryId;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.OutputNode;
import io.trino.sql.planner.plan.ValuesNode;
import io.trino.testing.BaseConnectorTest;
import io.trino.testing.DataProviders;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import io.trino.testing.ResultWithQueryId;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.TestTable;
import io.trino.tpch.TpchTable;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.hadoop.fs.FileSystem;
import org.intellij.lang.annotations.Language;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static io.trino.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.trino.SystemSessionProperties.PREFERRED_WRITE_PARTITIONING_MIN_NUMBER_OF_PARTITIONS;
import static io.trino.SystemSessionProperties.SCALE_WRITERS;
import static io.trino.SystemSessionProperties.TASK_WRITER_COUNT;
import static io.trino.plugin.hive.HdfsEnvironment.HdfsContext;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.iceberg.IcebergFileFormat.ORC;
import static io.trino.plugin.iceberg.IcebergFileFormat.PARQUET;
import static io.trino.plugin.iceberg.IcebergQueryRunner.ICEBERG_CATALOG;
import static io.trino.plugin.iceberg.IcebergSplitManager.ICEBERG_DOMAIN_COMPACTION_THRESHOLD;
import static io.trino.spi.predicate.Domain.multipleValues;
import static io.trino.spi.predicate.Domain.singleValue;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.planner.OptimizerConfig.JoinDistributionType.BROADCAST;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.QueryAssertions.assertEqualsIgnoreOrder;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.testing.assertions.Assert.assertEquals;
import static io.trino.testing.assertions.Assert.assertEventually;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static io.trino.tpch.TpchTable.LINE_ITEM;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.time.ZoneOffset.UTC;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public abstract class BaseIcebergConnectorTest
        extends BaseConnectorTest
{
    private static final Pattern WITH_CLAUSE_EXTRACTOR = Pattern.compile(".*(WITH\\s*\\([^)]*\\))\\s*$", Pattern.DOTALL);

    private final IcebergFileFormat format;

    protected BaseIcebergConnectorTest(IcebergFileFormat format)
    {
        this.format = requireNonNull(format, "format is null");
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return IcebergQueryRunner.builder()
                .setIcebergProperties(Map.of("iceberg.file-format", format.name()))
                .setInitialTables(ImmutableList.<TpchTable<?>>builder()
                        .addAll(REQUIRED_TPCH_TABLES)
                        .add(LINE_ITEM)
                        .build())
                .build();
    }

    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_TOPN_PUSHDOWN:
                return false;

            case SUPPORTS_CREATE_VIEW:
                return true;

            case SUPPORTS_CREATE_MATERIALIZED_VIEW:
            case SUPPORTS_RENAME_MATERIALIZED_VIEW:
                return true;
            case SUPPORTS_RENAME_MATERIALIZED_VIEW_ACROSS_SCHEMAS:
                return false;

            case SUPPORTS_DELETE:
            case SUPPORTS_UPDATE:
                return true;
            default:
                return super.hasBehavior(connectorBehavior);
        }
    }

    @Override
    protected void verifyVersionedQueryFailurePermissible(Exception e)
    {
        assertThat(e)
                .hasMessageMatching("Version pointer type is not supported: .*|" +
                        "Unsupported type for temporal table version: .*|" +
                        "Unsupported type for table version: .*|" +
                        "No version history table tpch.nation at or before .*|" +
                        "Iceberg snapshot ID does not exists: .*");
    }

    @Override
    protected void verifyConcurrentUpdateFailurePermissible(Exception e)
    {
        assertThat(e).hasMessageContaining("Failed to commit Iceberg update to table");
    }

    @Override
    protected void verifyConcurrentAddColumnFailurePermissible(Exception e)
    {
        assertThat(e)
                .hasMessageContaining("Cannot update Iceberg table: supplied previous location does not match current location");
    }

    @Test
    public void testDeleteOnV1Table()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_delete_", "WITH (format_version = 1) AS SELECT * FROM orders")) {
            assertQueryFails("DELETE FROM " + table.getName() + " WHERE custkey <= 100", "Iceberg table updates require at least format version 2");
        }
    }

    @Override
    public void testCharVarcharComparison()
    {
        assertThatThrownBy(super::testCharVarcharComparison)
                .hasMessage("Type not supported for Iceberg: char(3)");
    }

    @Test
    @Override
    public void testShowCreateSchema()
    {
        assertThat(computeActual("SHOW CREATE SCHEMA tpch").getOnlyValue().toString())
                .matches("CREATE SCHEMA iceberg.tpch\n" +
                        "AUTHORIZATION USER user\n" +
                        "WITH \\(\n" +
                        "\\s+location = '.*/iceberg_data/tpch'\n" +
                        "\\)");
    }

    @Override
    @Test
    public void testDescribeTable()
    {
        MaterializedResult expectedColumns = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "date", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "integer", "", "")
                .row("comment", "varchar", "", "")
                .build();
        MaterializedResult actualColumns = computeActual("DESCRIBE orders");
        assertEquals(actualColumns, expectedColumns);
    }

    @Override
    @Test
    public void testShowCreateTable()
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        assertThat(computeActual("SHOW CREATE TABLE orders").getOnlyValue())
                .isEqualTo("CREATE TABLE iceberg.tpch.orders (\n" +
                        "   orderkey bigint,\n" +
                        "   custkey bigint,\n" +
                        "   orderstatus varchar,\n" +
                        "   totalprice double,\n" +
                        "   orderdate date,\n" +
                        "   orderpriority varchar,\n" +
                        "   clerk varchar,\n" +
                        "   shippriority integer,\n" +
                        "   comment varchar\n" +
                        ")\n" +
                        "WITH (\n" +
                        "   format = '" + format.name() + "',\n" +
                        "   format_version = 2,\n" +
                        "   location = '" + tempDir + "/iceberg_data/tpch/orders'\n" +
                        ")");
    }

    @Override
    protected void checkInformationSchemaViewsForMaterializedView(String schemaName, String viewName)
    {
        // TODO should probably return materialized view, as it's also a view -- to be double checked
        assertThatThrownBy(() -> super.checkInformationSchemaViewsForMaterializedView(schemaName, viewName))
                .hasMessageFindingMatch("(?s)Expecting.*to contain:.*\\Q[(" + viewName + ")]");
    }

    @Test
    public void testDecimal()
    {
        testDecimalWithPrecisionAndScale(1, 0);
        testDecimalWithPrecisionAndScale(8, 6);
        testDecimalWithPrecisionAndScale(9, 8);
        testDecimalWithPrecisionAndScale(10, 8);

        testDecimalWithPrecisionAndScale(18, 1);
        testDecimalWithPrecisionAndScale(18, 8);
        testDecimalWithPrecisionAndScale(18, 17);

        testDecimalWithPrecisionAndScale(17, 16);
        testDecimalWithPrecisionAndScale(18, 17);
        testDecimalWithPrecisionAndScale(24, 10);
        testDecimalWithPrecisionAndScale(30, 10);
        testDecimalWithPrecisionAndScale(37, 26);
        testDecimalWithPrecisionAndScale(38, 37);

        testDecimalWithPrecisionAndScale(38, 17);
        testDecimalWithPrecisionAndScale(38, 37);
    }

    private void testDecimalWithPrecisionAndScale(int precision, int scale)
    {
        checkArgument(precision >= 1 && precision <= 38, "Decimal precision (%s) must be between 1 and 38 inclusive", precision);
        checkArgument(scale < precision && scale >= 0, "Decimal scale (%s) must be less than the precision (%s) and non-negative", scale, precision);

        String decimalType = format("DECIMAL(%d,%d)", precision, scale);
        String beforeTheDecimalPoint = "12345678901234567890123456789012345678".substring(0, precision - scale);
        String afterTheDecimalPoint = "09876543210987654321098765432109876543".substring(0, scale);
        String decimalValue = format("%s.%s", beforeTheDecimalPoint, afterTheDecimalPoint);

        assertUpdate(format("CREATE TABLE test_iceberg_decimal (x %s)", decimalType));
        assertUpdate(format("INSERT INTO test_iceberg_decimal (x) VALUES (CAST('%s' AS %s))", decimalValue, decimalType), 1);
        assertQuery("SELECT * FROM test_iceberg_decimal", format("SELECT CAST('%s' AS %s)", decimalValue, decimalType));
        dropTable("test_iceberg_decimal");
    }

    @Test
    public void testTime()
    {
        testSelectOrPartitionedByTime(false);
    }

    @Test
    public void testPartitionedByTime()
    {
        testSelectOrPartitionedByTime(true);
    }

    private void testSelectOrPartitionedByTime(boolean partitioned)
    {
        String tableName = format("test_%s_by_time", partitioned ? "partitioned" : "selected");
        String partitioning = partitioned ? "WITH(partitioning = ARRAY['x'])" : "";
        assertUpdate(format("CREATE TABLE %s (x TIME(6), y BIGINT) %s", tableName, partitioning));
        assertUpdate(format("INSERT INTO %s VALUES (TIME '10:12:34', 12345)", tableName), 1);
        assertQuery(format("SELECT COUNT(*) FROM %s", tableName), "SELECT 1");
        assertQuery(format("SELECT x FROM %s", tableName), "SELECT CAST('10:12:34' AS TIME)");
        assertUpdate(format("INSERT INTO %s VALUES (TIME '9:00:00', 67890)", tableName), 1);
        assertQuery(format("SELECT COUNT(*) FROM %s", tableName), "SELECT 2");
        assertQuery(format("SELECT x FROM %s WHERE x = TIME '10:12:34'", tableName), "SELECT CAST('10:12:34' AS TIME)");
        assertQuery(format("SELECT x FROM %s WHERE x = TIME '9:00:00'", tableName), "SELECT CAST('9:00:00' AS TIME)");
        assertQuery(format("SELECT x FROM %s WHERE y = 12345", tableName), "SELECT CAST('10:12:34' AS TIME)");
        assertQuery(format("SELECT x FROM %s WHERE y = 67890", tableName), "SELECT CAST('9:00:00' AS TIME)");
        dropTable(tableName);
    }

    @Test
    public void testPartitionByTimestamp()
    {
        testSelectOrPartitionedByTimestamp(true);
    }

    @Test
    public void testSelectByTimestamp()
    {
        testSelectOrPartitionedByTimestamp(false);
    }

    private void testSelectOrPartitionedByTimestamp(boolean partitioned)
    {
        String tableName = format("test_%s_by_timestamp", partitioned ? "partitioned" : "selected");
        assertUpdate(format("CREATE TABLE %s (_timestamp timestamp(6)) %s",
                tableName, partitioned ? "WITH (partitioning = ARRAY['_timestamp'])" : ""));
        @Language("SQL") String select1 = "SELECT TIMESTAMP '2017-05-01 10:12:34' _timestamp";
        @Language("SQL") String select2 = "SELECT TIMESTAMP '2017-10-01 10:12:34' _timestamp";
        @Language("SQL") String select3 = "SELECT TIMESTAMP '2018-05-01 10:12:34' _timestamp";
        assertUpdate(format("INSERT INTO %s %s", tableName, select1), 1);
        assertUpdate(format("INSERT INTO %s %s", tableName, select2), 1);
        assertUpdate(format("INSERT INTO %s %s", tableName, select3), 1);
        assertQuery(format("SELECT COUNT(*) from %s", tableName), "SELECT 3");

        assertQuery(format("SELECT * from %s WHERE _timestamp = TIMESTAMP '2017-05-01 10:12:34'", tableName), select1);
        assertQuery(format("SELECT * from %s WHERE _timestamp < TIMESTAMP '2017-06-01 10:12:34'", tableName), select1);
        assertQuery(format("SELECT * from %s WHERE _timestamp = TIMESTAMP '2017-10-01 10:12:34'", tableName), select2);
        assertQuery(format("SELECT * from %s WHERE _timestamp > TIMESTAMP '2017-06-01 10:12:34' AND _timestamp < TIMESTAMP '2018-05-01 10:12:34'", tableName), select2);
        assertQuery(format("SELECT * from %s WHERE _timestamp = TIMESTAMP '2018-05-01 10:12:34'", tableName), select3);
        assertQuery(format("SELECT * from %s WHERE _timestamp > TIMESTAMP '2018-01-01 10:12:34'", tableName), select3);
        dropTable(tableName);
    }

    @Test
    public void testPartitionByTimestampWithTimeZone()
    {
        testSelectOrPartitionedByTimestampWithTimeZone(true);
    }

    @Test
    public void testSelectByTimestampWithTimeZone()
    {
        testSelectOrPartitionedByTimestampWithTimeZone(false);
    }

    private void testSelectOrPartitionedByTimestampWithTimeZone(boolean partitioned)
    {
        String tableName = format("test_%s_by_timestamptz", partitioned ? "partitioned" : "selected");
        assertUpdate(format(
                "CREATE TABLE %s (_timestamptz timestamp(6) with time zone) %s",
                tableName,
                partitioned ? "WITH (partitioning = ARRAY['_timestamptz'])" : ""));

        String instant1Utc = "TIMESTAMP '2021-10-31 00:30:00.005000 UTC'";
        String instant1La = "TIMESTAMP '2021-10-30 17:30:00.005000 America/Los_Angeles'";
        String instant2Utc = "TIMESTAMP '2021-10-31 00:30:00.006000 UTC'";
        String instant2La = "TIMESTAMP '2021-10-30 17:30:00.006000 America/Los_Angeles'";
        String instant3Utc = "TIMESTAMP '2021-10-31 00:30:00.007000 UTC'";
        String instant3La = "TIMESTAMP '2021-10-30 17:30:00.007000 America/Los_Angeles'";
        // regression test value for https://github.com/trinodb/trino/issues/12852
        String instant4Utc = "TIMESTAMP '1969-12-01 05:06:07.234567 UTC'";

        assertUpdate(format("INSERT INTO %s VALUES %s", tableName, instant1Utc), 1);
        assertUpdate(format("INSERT INTO %s VALUES %s", tableName, instant2La /* non-UTC for this one */), 1);
        assertUpdate(format("INSERT INTO %s VALUES %s", tableName, instant3Utc), 1);
        assertUpdate(format("INSERT INTO %s VALUES %s", tableName, instant4Utc), 1);
        assertQuery(format("SELECT COUNT(*) from %s", tableName), "SELECT 4");

        // =
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant1Utc)))
                .matches("VALUES " + instant1Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant1La)))
                .matches("VALUES " + instant1Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant2Utc)))
                .matches("VALUES " + instant2Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant2La)))
                .matches("VALUES " + instant2Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant3Utc)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant3La)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz = %s", tableName, instant4Utc)))
                .matches("VALUES " + instant4Utc);

        // <
        assertThat(query(format("SELECT * from %s WHERE _timestamptz < %s", tableName, instant2Utc)))
                .matches(format("VALUES %s, %s", instant1Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz < %s", tableName, instant2La)))
                .matches(format("VALUES %s, %s", instant1Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz < %s", tableName, instant3Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz < %s", tableName, instant3La)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant4Utc));

        // <=
        assertThat(query(format("SELECT * from %s WHERE _timestamptz <= %s", tableName, instant2Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz <= %s", tableName, instant2La)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant4Utc));

        // >
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s", tableName, instant2Utc)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s", tableName, instant2La)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s", tableName, instant1Utc)))
                .matches(format("VALUES %s, %s", instant2Utc, instant3Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s", tableName, instant1La)))
                .matches(format("VALUES %s, %s", instant2Utc, instant3Utc));

        // >=
        assertThat(query(format("SELECT * from %s WHERE _timestamptz >= %s", tableName, instant2Utc)))
                .matches(format("VALUES %s, %s", instant2Utc, instant3Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz >= %s", tableName, instant2La)))
                .matches(format("VALUES %s, %s", instant2Utc, instant3Utc));

        // open range
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s AND _timestamptz < %s", tableName, instant1Utc, instant3Utc)))
                .matches("VALUES " + instant2Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz > %s AND _timestamptz < %s", tableName, instant1La, instant3La)))
                .matches("VALUES " + instant2Utc);

        // closed range
        assertThat(query(format("SELECT * from %s WHERE _timestamptz BETWEEN %s AND %s", tableName, instant1Utc, instant2Utc)))
                .matches(format("VALUES %s, %s", instant1Utc, instant2Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz BETWEEN %s AND %s", tableName, instant1La, instant2La)))
                .matches(format("VALUES %s, %s", instant1Utc, instant2Utc));

        // !=
        assertThat(query(format("SELECT * from %s WHERE _timestamptz != %s", tableName, instant1Utc)))
                .matches(format("VALUES %s, %s, %s", instant2Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz != %s", tableName, instant1La)))
                .matches(format("VALUES %s, %s, %s", instant2Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz != %s", tableName, instant2Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz != %s", tableName, instant2La)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz != %s", tableName, instant4Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant3Utc));

        // IS DISTINCT FROM
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS DISTINCT FROM %s", tableName, instant1Utc)))
                .matches(format("VALUES %s, %s, %s", instant2Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS DISTINCT FROM %s", tableName, instant1La)))
                .matches(format("VALUES %s, %s, %s", instant2Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS DISTINCT FROM %s", tableName, instant2Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS DISTINCT FROM %s", tableName, instant2La)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant3Utc, instant4Utc));
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS DISTINCT FROM %s", tableName, instant4Utc)))
                .matches(format("VALUES %s, %s, %s", instant1Utc, instant2Utc, instant3Utc));

        // IS NOT DISTINCT FROM
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant1Utc)))
                .matches("VALUES " + instant1Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant1La)))
                .matches("VALUES " + instant1Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant2Utc)))
                .matches("VALUES " + instant2Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant2La)))
                .matches("VALUES " + instant2Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant3Utc)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant3La)))
                .matches("VALUES " + instant3Utc);
        assertThat(query(format("SELECT * from %s WHERE _timestamptz IS NOT DISTINCT FROM %s", tableName, instant4Utc)))
                .matches("VALUES " + instant4Utc);

        if (partitioned) {
            assertThat(query(format("SELECT record_count, file_count, partition._timestamptz FROM \"%s$partitions\"", tableName)))
                    .matches(format(
                            "VALUES (BIGINT '1', BIGINT '1', %s), (BIGINT '1', BIGINT '1', %s), (BIGINT '1', BIGINT '1', %s), (BIGINT '1', BIGINT '1', %s)",
                            instant1Utc,
                            instant2Utc,
                            instant3Utc,
                            instant4Utc));
        }
        else {
            assertThat(query(format("SELECT record_count, file_count, data._timestamptz FROM \"%s$partitions\"", tableName)))
                    .matches(format(
                            "VALUES (BIGINT '4', BIGINT '4', CAST(ROW(%s, %s, 0, NULL) AS row(min timestamp(6) with time zone, max timestamp(6) with time zone, null_count bigint, nan_count bigint)))",
                            format == ORC ? "TIMESTAMP '1969-12-01 05:06:07.234000 UTC'" : instant4Utc,
                            format == ORC ? "TIMESTAMP '2021-10-31 00:30:00.007999 UTC'" : instant3Utc));
        }

        // show stats
        assertThat(query("SHOW STATS FOR " + tableName))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "('_timestamptz', NULL, NULL, 0e0, NULL, '1969-12-01 05:06:07.234 UTC', '2021-10-31 00:30:00.007 UTC'), " +
                        "(NULL, NULL, NULL, NULL, 4e0, NULL, NULL)");

        if (partitioned) {
            // show stats with predicate
            assertThat(query("SHOW STATS FOR (SELECT * FROM " + tableName + " WHERE _timestamptz = " + instant1La + ")"))
                    .skippingTypesCheck()
                    .matches("VALUES " +
                            // TODO (https://github.com/trinodb/trino/issues/9716) the min/max values are off by 1 millisecond
                            "('_timestamptz', NULL, NULL, 0e0, NULL, '2021-10-31 00:30:00.005 UTC', '2021-10-31 00:30:00.005 UTC'), " +
                            "(NULL, NULL, NULL, NULL, 1e0, NULL, NULL)");
        }
        else {
            // show stats with predicate
            assertThat(query("SHOW STATS FOR (SELECT * FROM " + tableName + " WHERE _timestamptz = " + instant1La + ")"))
                    .skippingTypesCheck()
                    .matches("VALUES " +
                            "('_timestamptz', NULL, NULL, NULL, NULL, NULL, NULL), " +
                            "(NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
        }

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testUuid()
    {
        testSelectOrPartitionedByUuid(false);
    }

    @Test
    public void testPartitionedByUuid()
    {
        testSelectOrPartitionedByUuid(true);
    }

    private void testSelectOrPartitionedByUuid(boolean partitioned)
    {
        String tableName = format("test_%s_by_uuid", partitioned ? "partitioned" : "selected");
        String partitioning = partitioned ? "WITH (partitioning = ARRAY['x'])" : "";
        assertUpdate(format("DROP TABLE IF EXISTS %s", tableName));
        assertUpdate(format("CREATE TABLE %s (x uuid, y bigint) %s", tableName, partitioning));

        assertUpdate(format("INSERT INTO %s VALUES (UUID '406caec7-68b9-4778-81b2-a12ece70c8b1', 12345)", tableName), 1);
        assertQuery(format("SELECT count(*) FROM %s", tableName), "SELECT 1");
        assertQuery(format("SELECT x FROM %s", tableName), "SELECT CAST('406caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID)");

        assertUpdate(format("INSERT INTO %s VALUES (UUID 'f79c3e09-677c-4bbd-a479-3f349cb785e7', 67890)", tableName), 1);
        assertUpdate(format("INSERT INTO %s VALUES (NULL, 7531)", tableName), 1);
        assertQuery(format("SELECT count(*) FROM %s", tableName), "SELECT 3");
        assertQuery(format("SELECT * FROM %s WHERE x = UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'", tableName), "SELECT CAST('406caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID), 12345");
        assertQuery(format("SELECT * FROM %s WHERE x = UUID 'f79c3e09-677c-4bbd-a479-3f349cb785e7'", tableName), "SELECT CAST('f79c3e09-677c-4bbd-a479-3f349cb785e7' AS UUID), 67890");
        assertQuery(
                format("SELECT * FROM %s WHERE x >= UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'", tableName),
                "VALUES (CAST('f79c3e09-677c-4bbd-a479-3f349cb785e7' AS UUID), 67890), (CAST('406caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID), 12345)");
        assertQuery(
                format("SELECT * FROM %s WHERE x >= UUID 'f79c3e09-677c-4bbd-a479-3f349cb785e7'", tableName),
                "SELECT CAST('f79c3e09-677c-4bbd-a479-3f349cb785e7' AS UUID), 67890");
        assertQuery(format("SELECT * FROM %s WHERE x IS NULL", tableName), "SELECT NULL, 7531");
        assertQuery(format("SELECT x FROM %s WHERE y = 12345", tableName), "SELECT CAST('406caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID)");
        assertQuery(format("SELECT x FROM %s WHERE y = 67890", tableName), "SELECT CAST('f79c3e09-677c-4bbd-a479-3f349cb785e7' AS UUID)");
        assertQuery(format("SELECT x FROM %s WHERE y = 7531", tableName), "SELECT NULL");

        assertUpdate(format("INSERT INTO %s VALUES (UUID '206caec7-68b9-4778-81b2-a12ece70c8b1', 313), (UUID '906caec7-68b9-4778-81b2-a12ece70c8b1', 314)", tableName), 2);
        assertThat(query("SELECT y FROM " + tableName + " WHERE x >= UUID '206caec7-68b9-4778-81b2-a12ece70c8b1'"))
                .matches("VALUES BIGINT '12345', 67890, 313, 314");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testNestedUuid()
    {
        assertUpdate("CREATE TABLE test_nested_uuid (int_t int, row_t row(uuid_t uuid, int_t int), map_t map(int, uuid), array_t array(uuid))");

        String uuid = "UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'";
        String value = format("VALUES (2, row(%1$s, 1), map(array[1], array[%1$s]), array[%1$s, %1$s])", uuid);
        assertUpdate("INSERT INTO test_nested_uuid " + value, 1);

        assertThat(query("SELECT row_t.int_t, row_t.uuid_t FROM test_nested_uuid"))
                .matches("VALUES (1, UUID '406caec7-68b9-4778-81b2-a12ece70c8b1')");
        assertThat(query("SELECT map_t[1] FROM test_nested_uuid"))
                .matches("VALUES UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'");
        assertThat(query("SELECT array_t FROM test_nested_uuid"))
                .matches("VALUES ARRAY[UUID '406caec7-68b9-4778-81b2-a12ece70c8b1', UUID '406caec7-68b9-4778-81b2-a12ece70c8b1']");

        assertQuery("SELECT row_t.int_t FROM test_nested_uuid WHERE row_t.uuid_t = UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'", "VALUES 1");
        assertQuery("SELECT int_t FROM test_nested_uuid WHERE row_t.uuid_t = UUID '406caec7-68b9-4778-81b2-a12ece70c8b1'", "VALUES 2");
    }

    @Test
    public void testCreatePartitionedTable()
    {
        assertUpdate("" +
                "CREATE TABLE test_partitioned_table (" +
                "  a_boolean boolean, " +
                "  an_integer integer, " +
                "  a_bigint bigint, " +
                "  a_real real, " +
                "  a_double double, " +
                "  a_short_decimal decimal(5,2), " +
                "  a_long_decimal decimal(38,20), " +
                "  a_varchar varchar, " +
                "  a_varbinary varbinary, " +
                "  a_date date, " +
                "  a_time time(6), " +
                "  a_timestamp timestamp(6), " +
                "  a_timestamptz timestamp(6) with time zone, " +
                "  a_uuid uuid, " +
                "  a_row row(id integer , vc varchar), " +
                "  an_array array(varchar), " +
                "  a_map map(integer, varchar) " +
                ") " +
                "WITH (" +
                "partitioning = ARRAY[" +
                "  'a_boolean', " +
                "  'an_integer', " +
                "  'a_bigint', " +
                "  'a_real', " +
                "  'a_double', " +
                "  'a_short_decimal', " +
                "  'a_long_decimal', " +
                "  'a_varchar', " +
                "  'a_varbinary', " +
                "  'a_date', " +
                "  'a_time', " +
                "  'a_timestamp', " +
                "  'a_timestamptz', " +
                "  'a_uuid' " +
                // Note: partitioning on non-primitive columns is not allowed in Iceberg
                "  ]" +
                ")");

        assertQueryReturnsEmptyResult("SELECT * FROM test_partitioned_table");

        String values = "VALUES (" +
                "true, " +
                "1, " +
                "BIGINT '1', " +
                "REAL '1.0', " +
                "DOUBLE '1.0', " +
                "CAST(1.0 AS decimal(5,2)), " +
                "CAST(11.0 AS decimal(38,20)), " +
                "VARCHAR 'onefsadfdsf', " +
                "X'000102f0feff', " +
                "DATE '2021-07-24'," +
                "TIME '02:43:57.987654', " +
                "TIMESTAMP '2021-07-24 03:43:57.987654'," +
                "TIMESTAMP '2021-07-24 04:43:57.987654 UTC', " +
                "UUID '20050910-1330-11e9-ffff-2a86e4085a59', " +
                "CAST(ROW(42, 'this is a random value') AS ROW(id int, vc varchar)), " +
                "ARRAY[VARCHAR 'uno', 'dos', 'tres'], " +
                "map(ARRAY[1,2], ARRAY['ek', VARCHAR 'one'])) ";

        String nullValues = nCopies(17, "NULL").stream()
                .collect(joining(", ", "VALUES (", ")"));

        assertUpdate("INSERT INTO test_partitioned_table " + values, 1);
        assertUpdate("INSERT INTO test_partitioned_table " + nullValues, 1);

        // SELECT
        assertThat(query("SELECT * FROM test_partitioned_table"))
                .matches(values + " UNION ALL " + nullValues);

        // SELECT with predicates
        assertThat(query("SELECT * FROM test_partitioned_table WHERE " +
                "    a_boolean = true " +
                "AND an_integer = 1 " +
                "AND a_bigint = BIGINT '1' " +
                "AND a_real = REAL '1.0' " +
                "AND a_double = DOUBLE '1.0' " +
                "AND a_short_decimal = CAST(1.0 AS decimal(5,2)) " +
                "AND a_long_decimal = CAST(11.0 AS decimal(38,20)) " +
                "AND a_varchar = VARCHAR 'onefsadfdsf' " +
                "AND a_varbinary = X'000102f0feff' " +
                "AND a_date = DATE '2021-07-24' " +
                "AND a_time = TIME '02:43:57.987654' " +
                "AND a_timestamp = TIMESTAMP '2021-07-24 03:43:57.987654' " +
                "AND a_timestamptz = TIMESTAMP '2021-07-24 04:43:57.987654 UTC' " +
                "AND a_uuid = UUID '20050910-1330-11e9-ffff-2a86e4085a59' " +
                "AND a_row = CAST(ROW(42, 'this is a random value') AS ROW(id int, vc varchar)) " +
                "AND an_array = ARRAY[VARCHAR 'uno', 'dos', 'tres'] " +
                "AND a_map = map(ARRAY[1,2], ARRAY['ek', VARCHAR 'one']) " +
                ""))
                .matches(values);

        assertThat(query("SELECT * FROM test_partitioned_table WHERE " +
                "    a_boolean IS NULL " +
                "AND an_integer IS NULL " +
                "AND a_bigint IS NULL " +
                "AND a_real IS NULL " +
                "AND a_double IS NULL " +
                "AND a_short_decimal IS NULL " +
                "AND a_long_decimal IS NULL " +
                "AND a_varchar IS NULL " +
                "AND a_varbinary IS NULL " +
                "AND a_date IS NULL " +
                "AND a_time IS NULL " +
                "AND a_timestamp IS NULL " +
                "AND a_timestamptz IS NULL " +
                "AND a_uuid IS NULL " +
                "AND a_row IS NULL " +
                "AND an_array IS NULL " +
                "AND a_map IS NULL " +
                ""))
                .skippingTypesCheck()
                .matches(nullValues);

        // SHOW STATS
        if (format == ORC) {
            assertQuery("SHOW STATS FOR test_partitioned_table",
                    "VALUES " +
                            "  ('a_boolean', NULL, NULL, 0.5, NULL, 'true', 'true'), " +
                            "  ('an_integer', NULL, NULL, 0.5, NULL, '1', '1'), " +
                            "  ('a_bigint', NULL, NULL, 0.5, NULL, '1', '1'), " +
                            "  ('a_real', NULL, NULL, 0.5, NULL, '1.0', '1.0'), " +
                            "  ('a_double', NULL, NULL, 0.5, NULL, '1.0', '1.0'), " +
                            "  ('a_short_decimal', NULL, NULL, 0.5, NULL, '1.0', '1.0'), " +
                            "  ('a_long_decimal', NULL, NULL, 0.5, NULL, '11.0', '11.0'), " +
                            "  ('a_varchar', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('a_varbinary', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('a_date', NULL, NULL, 0.5, NULL, '2021-07-24', '2021-07-24'), " +
                            "  ('a_time', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('a_timestamp', NULL, NULL, 0.5, NULL, '2021-07-24 03:43:57.987654', '2021-07-24 03:43:57.987654'), " +
                            "  ('a_timestamptz', NULL, NULL, 0.5, NULL, '2021-07-24 04:43:57.987 UTC', '2021-07-24 04:43:57.987 UTC'), " +
                            "  ('a_uuid', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('a_row', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('an_array', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  ('a_map', NULL, NULL, 0.5, NULL, NULL, NULL), " +
                            "  (NULL, NULL, NULL, NULL, 2e0, NULL, NULL)");
        }
        else {
            assertThat(query("SHOW STATS FOR test_partitioned_table"))
                    .skippingTypesCheck()
                    .matches("VALUES " +
                            "  ('a_boolean', NULL, NULL, 0.5e0, NULL, 'true', 'true'), " +
                            "  ('an_integer', NULL, NULL, 0.5e0, NULL, '1', '1'), " +
                            "  ('a_bigint', NULL, NULL, 0.5e0, NULL, '1', '1'), " +
                            "  ('a_real', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                            "  ('a_double', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                            "  ('a_short_decimal', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                            "  ('a_long_decimal', NULL, NULL, 0.5e0, NULL, '11.0', '11.0'), " +
                            "  ('a_varchar', 87e0, NULL, 0.5e0, NULL, NULL, NULL), " +
                            "  ('a_varbinary', 82e0, NULL, 0.5e0, NULL, NULL, NULL), " +
                            "  ('a_date', NULL, NULL, 0.5e0, NULL, '2021-07-24', '2021-07-24'), " +
                            "  ('a_time', NULL, NULL, 0.5e0, NULL, NULL, NULL), " +
                            "  ('a_timestamp', NULL, NULL, 0.5e0, NULL, '2021-07-24 03:43:57.987654', '2021-07-24 03:43:57.987654'), " +
                            "  ('a_timestamptz', NULL, NULL, 0.5e0, NULL, '2021-07-24 04:43:57.987 UTC', '2021-07-24 04:43:57.987 UTC'), " +
                            "  ('a_uuid', NULL, NULL, 0.5e0, NULL, NULL, NULL), " +
                            "  ('a_row', NULL, NULL, NULL, NULL, NULL, NULL), " +
                            "  ('an_array', NULL, NULL, NULL, NULL, NULL, NULL), " +
                            "  ('a_map', NULL, NULL, NULL, NULL, NULL, NULL), " +
                            "  (NULL, NULL, NULL, NULL, 2e0, NULL, NULL)");
        }

        // $partitions
        String schema = getSession().getSchema().orElseThrow();
        assertThat(query("SELECT column_name FROM information_schema.columns WHERE table_schema = '" + schema + "' AND table_name = 'test_partitioned_table$partitions' "))
                .skippingTypesCheck()
                .matches("VALUES 'partition', 'record_count', 'file_count', 'total_size'");
        assertThat(query("SELECT " +
                "  record_count," +
                "  file_count, " +
                "  partition.a_boolean, " +
                "  partition.an_integer, " +
                "  partition.a_bigint, " +
                "  partition.a_real, " +
                "  partition.a_double, " +
                "  partition.a_short_decimal, " +
                "  partition.a_long_decimal, " +
                "  partition.a_varchar, " +
                "  partition.a_varbinary, " +
                "  partition.a_date, " +
                "  partition.a_time, " +
                "  partition.a_timestamp, " +
                "  partition.a_timestamptz, " +
                "  partition.a_uuid " +
                // Note: partitioning on non-primitive columns is not allowed in Iceberg
                " FROM \"test_partitioned_table$partitions\" "))
                .matches("" +
                        "VALUES (" +
                        "  BIGINT '1', " +
                        "  BIGINT '1', " +
                        "  true, " +
                        "  1, " +
                        "  BIGINT '1', " +
                        "  REAL '1.0', " +
                        "  DOUBLE '1.0', " +
                        "  CAST(1.0 AS decimal(5,2)), " +
                        "  CAST(11.0 AS decimal(38,20)), " +
                        "  VARCHAR 'onefsadfdsf', " +
                        "  X'000102f0feff', " +
                        "  DATE '2021-07-24'," +
                        "  TIME '02:43:57.987654', " +
                        "  TIMESTAMP '2021-07-24 03:43:57.987654'," +
                        "  TIMESTAMP '2021-07-24 04:43:57.987654 UTC', " +
                        "  UUID '20050910-1330-11e9-ffff-2a86e4085a59' " +
                        ")" +
                        "UNION ALL " +
                        "VALUES (" +
                        "  BIGINT '1', " +
                        "  BIGINT '1', " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL, " +
                        "  NULL " +
                        ")");

        assertUpdate("DROP TABLE test_partitioned_table");
    }

    @Test
    public void testCreatePartitionedTableWithNestedTypes()
    {
        assertUpdate("" +
                "CREATE TABLE test_partitioned_table_nested_type (" +
                "  _string VARCHAR" +
                ", _struct ROW(_field1 INT, _field2 VARCHAR)" +
                ", _date DATE" +
                ") " +
                "WITH (" +
                "  partitioning = ARRAY['_date']" +
                ")");

        dropTable("test_partitioned_table_nested_type");
    }

    @Test
    public void testCreatePartitionedTableAs()
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        String tempDirPath = tempDir.toURI().toASCIIString() + randomTableSuffix();
        assertUpdate(
                "CREATE TABLE test_create_partitioned_table_as " +
                        "WITH (" +
                        "format_version = 2," +
                        "location = '" + tempDirPath + "', " +
                        "partitioning = ARRAY['ORDER_STATUS', 'Ship_Priority', 'Bucket(order_key,9)']" +
                        ") " +
                        "AS " +
                        "SELECT orderkey AS order_key, shippriority AS ship_priority, orderstatus AS order_status " +
                        "FROM tpch.tiny.orders",
                "SELECT count(*) from orders");

        assertEquals(
                computeScalar("SHOW CREATE TABLE test_create_partitioned_table_as"),
                format(
                        "CREATE TABLE %s.%s.%s (\n" +
                                "   order_key bigint,\n" +
                                "   ship_priority integer,\n" +
                                "   order_status varchar\n" +
                                ")\n" +
                                "WITH (\n" +
                                "   format = '%s',\n" +
                                "   format_version = 2,\n" +
                                "   location = '%s',\n" +
                                "   partitioning = ARRAY['order_status','ship_priority','bucket(order_key, 9)']\n" +
                                ")",
                        getSession().getCatalog().orElseThrow(),
                        getSession().getSchema().orElseThrow(),
                        "test_create_partitioned_table_as",
                        format,
                        tempDirPath));

        assertQuery("SELECT * from test_create_partitioned_table_as", "SELECT orderkey, shippriority, orderstatus FROM orders");

        dropTable("test_create_partitioned_table_as");
    }

    @Test
    public void testTableComments()
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        String tempDirPath = tempDir.toURI().toASCIIString() + randomTableSuffix();
        String createTableTemplate = "" +
                "CREATE TABLE iceberg.tpch.test_table_comments (\n" +
                "   _x bigint\n" +
                ")\n" +
                "COMMENT '%s'\n" +
                "WITH (\n" +
                format("   format = '%s',\n", format) +
                "   format_version = 2,\n" +
                format("   location = '%s'\n", tempDirPath) +
                ")";
        String createTableWithoutComment = "" +
                "CREATE TABLE iceberg.tpch.test_table_comments (\n" +
                "   _x bigint\n" +
                ")\n" +
                "WITH (\n" +
                "   format = '" + format + "',\n" +
                "   format_version = 2,\n" +
                "   location = '" + tempDirPath + "'\n" +
                ")";
        String createTableSql = format(createTableTemplate, "test table comment", format);
        assertUpdate(createTableSql);
        assertEquals(computeScalar("SHOW CREATE TABLE test_table_comments"), createTableSql);

        assertUpdate("COMMENT ON TABLE test_table_comments IS 'different test table comment'");
        assertEquals(computeScalar("SHOW CREATE TABLE test_table_comments"), format(createTableTemplate, "different test table comment", format));

        assertUpdate("COMMENT ON TABLE test_table_comments IS NULL");
        assertEquals(computeScalar("SHOW CREATE TABLE test_table_comments"), createTableWithoutComment);
        dropTable("iceberg.tpch.test_table_comments");

        assertUpdate(createTableWithoutComment);
        assertEquals(computeScalar("SHOW CREATE TABLE test_table_comments"), createTableWithoutComment);

        dropTable("iceberg.tpch.test_table_comments");
    }

    @Test
    public void testRollbackSnapshot()
    {
        assertUpdate("CREATE TABLE test_rollback (col0 INTEGER, col1 BIGINT)");
        long afterCreateTableId = getLatestSnapshotId("test_rollback");

        assertUpdate("INSERT INTO test_rollback (col0, col1) VALUES (123, CAST(987 AS BIGINT))", 1);
        long afterFirstInsertId = getLatestSnapshotId("test_rollback");

        assertUpdate("INSERT INTO test_rollback (col0, col1) VALUES (456, CAST(654 AS BIGINT))", 1);
        assertQuery("SELECT * FROM test_rollback ORDER BY col0", "VALUES (123, CAST(987 AS BIGINT)), (456, CAST(654 AS BIGINT))");

        assertUpdate(format("CALL system.rollback_to_snapshot('tpch', 'test_rollback', %s)", afterFirstInsertId));
        assertQuery("SELECT * FROM test_rollback ORDER BY col0", "VALUES (123, CAST(987 AS BIGINT))");

        assertUpdate(format("CALL system.rollback_to_snapshot('tpch', 'test_rollback', %s)", afterCreateTableId));
        assertEquals((long) computeActual("SELECT COUNT(*) FROM test_rollback").getOnlyValue(), 0);

        assertUpdate("INSERT INTO test_rollback (col0, col1) VALUES (789, CAST(987 AS BIGINT))", 1);
        long afterSecondInsertId = getLatestSnapshotId("test_rollback");

        // extra insert which should be dropped on rollback
        assertUpdate("INSERT INTO test_rollback (col0, col1) VALUES (999, CAST(999 AS BIGINT))", 1);

        assertUpdate(format("CALL system.rollback_to_snapshot('tpch', 'test_rollback', %s)", afterSecondInsertId));
        assertQuery("SELECT * FROM test_rollback ORDER BY col0", "VALUES (789, CAST(987 AS BIGINT))");

        dropTable("test_rollback");
    }

    private long getLatestSnapshotId(String tableName)
    {
        return (long) computeActual(format("SELECT snapshot_id FROM \"%s$snapshots\" ORDER BY committed_at DESC LIMIT 1", tableName))
                .getOnlyValue();
    }

    @Override
    protected String errorMessageForInsertIntoNotNullColumn(String columnName)
    {
        return "NULL value not allowed for NOT NULL column: " + columnName;
    }

    @Test
    public void testSchemaEvolution()
    {
        assertUpdate("CREATE TABLE test_schema_evolution_drop_end (col0 INTEGER, col1 INTEGER, col2 INTEGER)");
        assertUpdate("INSERT INTO test_schema_evolution_drop_end VALUES (0, 1, 2)", 1);
        assertQuery("SELECT * FROM test_schema_evolution_drop_end", "VALUES(0, 1, 2)");
        assertUpdate("ALTER TABLE test_schema_evolution_drop_end DROP COLUMN col2");
        assertQuery("SELECT * FROM test_schema_evolution_drop_end", "VALUES(0, 1)");
        assertUpdate("ALTER TABLE test_schema_evolution_drop_end ADD COLUMN col2 INTEGER");
        assertQuery("SELECT * FROM test_schema_evolution_drop_end", "VALUES(0, 1, NULL)");
        assertUpdate("INSERT INTO test_schema_evolution_drop_end VALUES (3, 4, 5)", 1);
        assertQuery("SELECT * FROM test_schema_evolution_drop_end", "VALUES(0, 1, NULL), (3, 4, 5)");
        dropTable("test_schema_evolution_drop_end");

        assertUpdate("CREATE TABLE test_schema_evolution_drop_middle (col0 INTEGER, col1 INTEGER, col2 INTEGER)");
        assertUpdate("INSERT INTO test_schema_evolution_drop_middle VALUES (0, 1, 2)", 1);
        assertQuery("SELECT * FROM test_schema_evolution_drop_middle", "VALUES(0, 1, 2)");
        assertUpdate("ALTER TABLE test_schema_evolution_drop_middle DROP COLUMN col1");
        assertQuery("SELECT * FROM test_schema_evolution_drop_middle", "VALUES(0, 2)");
        assertUpdate("ALTER TABLE test_schema_evolution_drop_middle ADD COLUMN col1 INTEGER");
        assertUpdate("INSERT INTO test_schema_evolution_drop_middle VALUES (3, 4, 5)", 1);
        assertQuery("SELECT * FROM test_schema_evolution_drop_middle", "VALUES(0, 2, NULL), (3, 4, 5)");
        dropTable("test_schema_evolution_drop_middle");
    }

    @Test
    public void testShowStatsAfterAddColumn()
    {
        assertUpdate("CREATE TABLE test_show_stats_after_add_column (col0 INTEGER, col1 INTEGER, col2 INTEGER)");
        // Insert separately to ensure the table has multiple data files
        assertUpdate("INSERT INTO test_show_stats_after_add_column VALUES (1, 2, 3)", 1);
        assertUpdate("INSERT INTO test_show_stats_after_add_column VALUES (4, 5, 6)", 1);
        assertUpdate("INSERT INTO test_show_stats_after_add_column VALUES (NULL, NULL, NULL)", 1);
        assertUpdate("INSERT INTO test_show_stats_after_add_column VALUES (7, 8, 9)", 1);

        assertThat(query("SHOW STATS FOR test_show_stats_after_add_column"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('col0', NULL, NULL, 25e-2, NULL, '1', '7')," +
                        "  ('col1', NULL, NULL, 25e-2, NULL, '2', '8'), " +
                        "  ('col2', NULL, NULL, 25e-2, NULL, '3', '9'), " +
                        "  (NULL, NULL, NULL, NULL, 4e0, NULL, NULL)");

        // Columns added after some data files exist will not have valid statistics because not all files have min/max/null count statistics for the new column
        assertUpdate("ALTER TABLE test_show_stats_after_add_column ADD COLUMN col3 INTEGER");
        assertUpdate("INSERT INTO test_show_stats_after_add_column VALUES (10, 11, 12, 13)", 1);
        assertThat(query("SHOW STATS FOR test_show_stats_after_add_column"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('col0', NULL, NULL, 2e-1, NULL, '1', '10')," +
                        "  ('col1', NULL, NULL, 2e-1, NULL, '2', '11'), " +
                        "  ('col2', NULL, NULL, 2e-1, NULL, '3', '12'), " +
                        "  ('col3', NULL, NULL, NULL,   NULL, NULL, NULL), " +
                        "  (NULL, NULL, NULL, NULL, 5e0, NULL, NULL)");
    }

    @Test
    public void testLargeInOnPartitionedColumns()
    {
        assertUpdate("CREATE TABLE test_in_predicate_large_set (col1 BIGINT, col2 BIGINT) WITH (partitioning = ARRAY['col2'])");
        assertUpdate("INSERT INTO test_in_predicate_large_set VALUES (1, 10)", 1L);
        assertUpdate("INSERT INTO test_in_predicate_large_set VALUES (2, 20)", 1L);

        List<String> predicates = IntStream.range(0, 25_000).boxed()
                .map(Object::toString)
                .collect(toImmutableList());
        String filter = format("col2 IN (%s)", join(",", predicates));
        assertThat(query("SELECT * FROM test_in_predicate_large_set WHERE " + filter))
                .matches("TABLE test_in_predicate_large_set");

        dropTable("test_in_predicate_large_set");
    }

    @Test
    public void testCreateTableFailsOnNonEmptyPath()
    {
        String tableName = "test_rename_table_" + randomTableSuffix();
        String tmpName = "test_rename_table_tmp_" + randomTableSuffix();
        try {
            assertUpdate("CREATE TABLE " + tmpName + " AS SELECT 1 as a", 1);
            assertUpdate("ALTER TABLE " + tmpName + " RENAME TO " + tableName);
            assertQueryFails("CREATE TABLE " + tmpName + " AS SELECT 1 as a", "Cannot create a table on a non-empty location.*");
        }
        finally {
            assertUpdate("DROP TABLE IF EXISTS " + tableName);
            assertUpdate("DROP TABLE IF EXISTS " + tmpName);
        }
    }

    @Test
    public void testCreateTableSucceedsOnEmptyDirectory()
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        String tmpName = "test_rename_table_tmp_" + randomTableSuffix();
        Path newPath = tempDir.toPath().resolve(tmpName);
        File directory = newPath.toFile();
        verify(directory.mkdirs(), "Could not make directory on filesystem");
        try {
            assertUpdate("CREATE TABLE " + tmpName + " WITH (location='" + directory + "') AS SELECT 1 as a", 1);
        }
        finally {
            assertUpdate("DROP TABLE IF EXISTS " + tmpName);
        }
    }

    @Test
    public void testCreateTableLike()
    {
        IcebergFileFormat otherFormat = (format == PARQUET) ? ORC : PARQUET;
        testCreateTableLikeForFormat(otherFormat);
    }

    private void testCreateTableLikeForFormat(IcebergFileFormat otherFormat)
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        String tempDirPath = tempDir.toURI().toASCIIString() + randomTableSuffix();

        // LIKE source INCLUDING PROPERTIES copies all the properties of the source table, including the `location`.
        // For this reason the source and the copied table will share the same directory.
        // This test does not drop intentionally the created tables to avoid affecting the source table or the information_schema.
        assertUpdate(format("CREATE TABLE test_create_table_like_original (col1 INTEGER, aDate DATE) WITH(format = '%s', location = '%s', partitioning = ARRAY['aDate'])", format, tempDirPath));
        assertEquals(getTablePropertiesString("test_create_table_like_original"), "WITH (\n" +
                format("   format = '%s',\n", format) +
                "   format_version = 2,\n" +
                format("   location = '%s',\n", tempDirPath) +
                "   partitioning = ARRAY['adate']\n" +
                ")");

        assertUpdate("CREATE TABLE test_create_table_like_copy0 (LIKE test_create_table_like_original, col2 INTEGER)");
        assertUpdate("INSERT INTO test_create_table_like_copy0 (col1, aDate, col2) VALUES (1, CAST('1950-06-28' AS DATE), 3)", 1);
        assertQuery("SELECT * from test_create_table_like_copy0", "VALUES(1, CAST('1950-06-28' AS DATE), 3)");

        assertUpdate("CREATE TABLE test_create_table_like_copy1 (LIKE test_create_table_like_original)");
        assertEquals(getTablePropertiesString("test_create_table_like_copy1"), "WITH (\n" +
                format("   format = '%s',\n   format_version = 2,\n   location = '%s'\n)", format, tempDir + "/iceberg_data/tpch/test_create_table_like_copy1"));

        assertUpdate("CREATE TABLE test_create_table_like_copy2 (LIKE test_create_table_like_original EXCLUDING PROPERTIES)");
        assertEquals(getTablePropertiesString("test_create_table_like_copy2"), "WITH (\n" +
                format("   format = '%s',\n   format_version = 2,\n   location = '%s'\n)", format, tempDir + "/iceberg_data/tpch/test_create_table_like_copy2"));
        dropTable("test_create_table_like_copy2");

        assertQueryFails("CREATE TABLE test_create_table_like_copy3 (LIKE test_create_table_like_original INCLUDING PROPERTIES)",
                "Cannot create a table on a non-empty location.*");

        assertQueryFails(format("CREATE TABLE test_create_table_like_copy4 (LIKE test_create_table_like_original INCLUDING PROPERTIES) WITH (format = '%s')", otherFormat),
                "Cannot create a table on a non-empty location.*");
    }

    private String getTablePropertiesString(String tableName)
    {
        MaterializedResult showCreateTable = computeActual("SHOW CREATE TABLE " + tableName);
        String createTable = (String) getOnlyElement(showCreateTable.getOnlyColumnAsSet());
        Matcher matcher = WITH_CLAUSE_EXTRACTOR.matcher(createTable);
        return matcher.matches() ? matcher.group(1) : null;
    }

    @Test
    public void testPredicating()
    {
        assertUpdate("CREATE TABLE test_predicating_on_real (col REAL)");
        assertUpdate("INSERT INTO test_predicating_on_real VALUES 1.2", 1);
        assertQuery("SELECT * FROM test_predicating_on_real WHERE col = 1.2", "VALUES 1.2");
        dropTable("test_predicating_on_real");
    }

    @Test
    public void testHourTransform()
    {
        assertUpdate("CREATE TABLE test_hour_transform (d TIMESTAMP(6), b BIGINT) WITH (partitioning = ARRAY['hour(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1969-12-31 22:22:22.222222', 8)," +
                "(TIMESTAMP '1969-12-31 23:33:11.456789', 9)," +
                "(TIMESTAMP '1969-12-31 23:44:55.567890', 10)," +
                "(TIMESTAMP '1970-01-01 00:55:44.765432', 11)," +
                "(TIMESTAMP '2015-01-01 10:01:23.123456', 1)," +
                "(TIMESTAMP '2015-01-01 10:10:02.987654', 2)," +
                "(TIMESTAMP '2015-01-01 10:55:00.456789', 3)," +
                "(TIMESTAMP '2015-05-15 12:05:01.234567', 4)," +
                "(TIMESTAMP '2015-05-15 12:21:02.345678', 5)," +
                "(TIMESTAMP '2020-02-21 13:11:11.876543', 6)," +
                "(TIMESTAMP '2020-02-21 13:12:12.654321', 7)";
        assertUpdate("INSERT INTO test_hour_transform " + values, 12);
        assertQuery("SELECT * FROM test_hour_transform", values);

        @Language("SQL") String expected = "VALUES " +
                "(NULL, 1, NULL, NULL, 101, 101), " +
                "(-2, 1, TIMESTAMP '1969-12-31 22:22:22.222222', TIMESTAMP '1969-12-31 22:22:22.222222', 8, 8), " +
                "(-1, 2, TIMESTAMP '1969-12-31 23:33:11.456789', TIMESTAMP '1969-12-31 23:44:55.567890', 9, 10), " +
                "(0, 1, TIMESTAMP '1970-01-01 00:55:44.765432', TIMESTAMP '1970-01-01 00:55:44.765432', 11, 11), " +
                "(394474, 3, TIMESTAMP '2015-01-01 10:01:23.123456', TIMESTAMP '2015-01-01 10:55:00.456789', 1, 3), " +
                "(397692, 2, TIMESTAMP '2015-05-15 12:05:01.234567', TIMESTAMP '2015-05-15 12:21:02.345678', 4, 5), " +
                "(439525, 2, TIMESTAMP '2020-02-21 13:11:11.876543', TIMESTAMP '2020-02-21 13:12:12.654321', 6, 7)";
        String expectedTimestampStats = "'1969-12-31 22:22:22.222222', '2020-02-21 13:12:12.654321'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, 1, NULL, NULL, 101, 101), " +
                    "(-2, 1, TIMESTAMP '1969-12-31 22:22:22.222000', TIMESTAMP '1969-12-31 22:22:22.222999', 8, 8), " +
                    "(-1, 2, TIMESTAMP '1969-12-31 23:33:11.456000', TIMESTAMP '1969-12-31 23:44:55.567999', 9, 10), " +
                    "(0, 1, TIMESTAMP '1970-01-01 00:55:44.765000', TIMESTAMP '1970-01-01 00:55:44.765999', 11, 11), " +
                    "(394474, 3, TIMESTAMP '2015-01-01 10:01:23.123000', TIMESTAMP '2015-01-01 10:55:00.456999', 1, 3), " +
                    "(397692, 2, TIMESTAMP '2015-05-15 12:05:01.234000', TIMESTAMP '2015-05-15 12:21:02.345999', 4, 5), " +
                    "(439525, 2, TIMESTAMP '2020-02-21 13:11:11.876000', TIMESTAMP '2020-02-21 13:12:12.654999', 6, 7)";
            expectedTimestampStats = "'1969-12-31 22:22:22.222000', '2020-02-21 13:12:12.654999'";
        }

        assertQuery("SELECT partition.d_hour, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_hour_transform$partitions\"", expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_hour_transform WHERE day_of_week(d) = 3 AND b % 7 = 3",
                "VALUES (TIMESTAMP '1969-12-31 23:44:55.567890', 10)");

        assertThat(query("SHOW STATS FOR test_hour_transform"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0833333e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 12e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_hour_transform WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_hour_transform WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_hour_transform WHERE d >= DATE '2015-05-15'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_hour_transform WHERE CAST(d AS date) >= DATE '2015-05-15'"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_hour_transform WHERE d >= TIMESTAMP '2015-05-15 12:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_hour_transform WHERE d >= TIMESTAMP '2015-05-15 12:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_hour_transform");
    }

    @Test
    public void testDayTransformDate()
    {
        assertUpdate("CREATE TABLE test_day_transform_date (d DATE, b BIGINT) WITH (partitioning = ARRAY['day(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(DATE '1969-01-01', 10), " +
                "(DATE '1969-12-31', 11), " +
                "(DATE '1970-01-01', 1), " +
                "(DATE '1970-03-04', 2), " +
                "(DATE '2015-01-01', 3), " +
                "(DATE '2015-01-13', 4), " +
                "(DATE '2015-01-13', 5), " +
                "(DATE '2015-05-15', 6), " +
                "(DATE '2015-05-15', 7), " +
                "(DATE '2020-02-21', 8), " +
                "(DATE '2020-02-21', 9)";
        assertUpdate("INSERT INTO test_day_transform_date " + values, 12);
        assertQuery("SELECT * FROM test_day_transform_date", values);

        assertQuery(
                "SELECT partition.d_day, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_day_transform_date$partitions\"",
                "VALUES " +
                        "(NULL, 1, NULL, NULL, 101, 101), " +
                        "(DATE '1969-01-01', 1, DATE '1969-01-01', DATE '1969-01-01', 10, 10), " +
                        "(DATE '1969-12-31', 1, DATE '1969-12-31', DATE '1969-12-31', 11, 11), " +
                        "(DATE '1970-01-01', 1, DATE '1970-01-01', DATE '1970-01-01', 1, 1), " +
                        "(DATE '1970-03-04', 1, DATE '1970-03-04', DATE '1970-03-04', 2, 2), " +
                        "(DATE '2015-01-01', 1, DATE '2015-01-01', DATE '2015-01-01', 3, 3), " +
                        "(DATE '2015-01-13', 2, DATE '2015-01-13', DATE '2015-01-13', 4, 5), " +
                        "(DATE '2015-05-15', 2, DATE '2015-05-15', DATE '2015-05-15', 6, 7), " +
                        "(DATE '2020-02-21', 2, DATE '2020-02-21', DATE '2020-02-21', 8, 9)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_day_transform_date WHERE day_of_week(d) = 3 AND b % 7 = 3",
                "VALUES (DATE '1969-01-01', 10)");

        assertThat(query("SHOW STATS FOR test_day_transform_date"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0833333e0, NULL, '1969-01-01', '2020-02-21'), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 12e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_day_transform_date WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_date WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_day_transform_date WHERE d >= DATE '2015-01-13'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_date WHERE CAST(d AS date) >= DATE '2015-01-13'"))
                .isFullyPushedDown();

        // d comparison with TIMESTAMP can be unwrapped
        assertThat(query("SELECT * FROM test_day_transform_date WHERE d >= TIMESTAMP '2015-01-13 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_date WHERE d >= TIMESTAMP '2015-01-13 00:00:00.000001'"))
                .isFullyPushedDown();

        dropTable("test_day_transform_date");
    }

    @Test
    public void testDayTransformTimestamp()
    {
        assertUpdate("CREATE TABLE test_day_transform_timestamp (d TIMESTAMP(6), b BIGINT) WITH (partitioning = ARRAY['day(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1969-12-25 15:13:12.876543', 8)," +
                "(TIMESTAMP '1969-12-30 18:47:33.345678', 9)," +
                "(TIMESTAMP '1969-12-31 00:00:00.000000', 10)," +
                "(TIMESTAMP '1969-12-31 05:06:07.234567', 11)," +
                "(TIMESTAMP '1970-01-01 12:03:08.456789', 12)," +
                "(TIMESTAMP '2015-01-01 10:01:23.123456', 1)," +
                "(TIMESTAMP '2015-01-01 11:10:02.987654', 2)," +
                "(TIMESTAMP '2015-01-01 12:55:00.456789', 3)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567', 4)," +
                "(TIMESTAMP '2015-05-15 14:21:02.345678', 5)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543', 6)," +
                "(TIMESTAMP '2020-02-21 16:12:12.654321', 7)";
        assertUpdate("INSERT INTO test_day_transform_timestamp " + values, 13);
        assertQuery("SELECT * FROM test_day_transform_timestamp", values);

        @Language("SQL") String expected = "VALUES " +
                "(NULL, 1, NULL, NULL, 101, 101), " +
                "(DATE '1969-12-25', 1, TIMESTAMP '1969-12-25 15:13:12.876543', TIMESTAMP '1969-12-25 15:13:12.876543', 8, 8), " +
                "(DATE '1969-12-30', 1, TIMESTAMP '1969-12-30 18:47:33.345678', TIMESTAMP '1969-12-30 18:47:33.345678', 9, 9), " +
                "(DATE '1969-12-31', 2, TIMESTAMP '1969-12-31 00:00:00.000000', TIMESTAMP '1969-12-31 05:06:07.234567', 10, 11), " +
                "(DATE '1970-01-01', 1, TIMESTAMP '1970-01-01 12:03:08.456789', TIMESTAMP '1970-01-01 12:03:08.456789', 12, 12), " +
                "(DATE '2015-01-01', 3, TIMESTAMP '2015-01-01 10:01:23.123456', TIMESTAMP '2015-01-01 12:55:00.456789', 1, 3), " +
                "(DATE '2015-05-15', 2, TIMESTAMP '2015-05-15 13:05:01.234567', TIMESTAMP '2015-05-15 14:21:02.345678', 4, 5), " +
                "(DATE '2020-02-21', 2, TIMESTAMP '2020-02-21 15:11:11.876543', TIMESTAMP '2020-02-21 16:12:12.654321', 6, 7)";
        String expectedTimestampStats = "'1969-12-25 15:13:12.876543', '2020-02-21 16:12:12.654321'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, 1, NULL, NULL, 101, 101), " +
                    "(DATE '1969-12-25', 1, TIMESTAMP '1969-12-25 15:13:12.876000', TIMESTAMP '1969-12-25 15:13:12.876999', 8, 8), " +
                    "(DATE '1969-12-30', 1, TIMESTAMP '1969-12-30 18:47:33.345000', TIMESTAMP '1969-12-30 18:47:33.345999', 9, 9), " +
                    "(DATE '1969-12-31', 2, TIMESTAMP '1969-12-31 00:00:00.000000', TIMESTAMP '1969-12-31 05:06:07.234999', 10, 11), " +
                    "(DATE '1970-01-01', 1, TIMESTAMP '1970-01-01 12:03:08.456000', TIMESTAMP '1970-01-01 12:03:08.456999', 12, 12), " +
                    "(DATE '2015-01-01', 3, TIMESTAMP '2015-01-01 10:01:23.123000', TIMESTAMP '2015-01-01 12:55:00.456999', 1, 3), " +
                    "(DATE '2015-05-15', 2, TIMESTAMP '2015-05-15 13:05:01.234000', TIMESTAMP '2015-05-15 14:21:02.345999', 4, 5), " +
                    "(DATE '2020-02-21', 2, TIMESTAMP '2020-02-21 15:11:11.876000', TIMESTAMP '2020-02-21 16:12:12.654999', 6, 7)";
            expectedTimestampStats = "'1969-12-25 15:13:12.876000', '2020-02-21 16:12:12.654999'";
        }

        assertQuery("SELECT partition.d_day, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_day_transform_timestamp$partitions\"", expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_day_transform_timestamp WHERE day_of_week(d) = 3 AND b % 7 = 3",
                "VALUES (TIMESTAMP '1969-12-31 00:00:00.000000', 10)");

        assertThat(query("SHOW STATS FOR test_day_transform_timestamp"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE d >= DATE '2015-05-15'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE CAST(d AS date) >= DATE '2015-05-15'"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE d >= TIMESTAMP '2015-05-15 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamp WHERE d >= TIMESTAMP '2015-05-15 00:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_day_transform_timestamp");
    }

    @Test
    public void testDayTransformTimestampWithTimeZone()
    {
        assertUpdate("CREATE TABLE test_day_transform_timestamptz (d timestamp(6) with time zone, b integer) WITH (partitioning = ARRAY['day(d)'])");

        String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1969-12-25 15:13:12.876543 UTC', 8)," +
                "(TIMESTAMP '1969-12-30 18:47:33.345678 UTC', 9)," +
                "(TIMESTAMP '1969-12-31 00:00:00.000000 UTC', 10)," +
                "(TIMESTAMP '1969-12-31 05:06:07.234567 UTC', 11)," +
                "(TIMESTAMP '1970-01-01 12:03:08.456789 UTC', 12)," +
                "(TIMESTAMP '2015-01-01 10:01:23.123456 UTC', 1)," +
                "(TIMESTAMP '2015-01-01 11:10:02.987654 UTC', 2)," +
                "(TIMESTAMP '2015-01-01 12:55:00.456789 UTC', 3)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567 UTC', 4)," +
                "(TIMESTAMP '2015-05-15 14:21:02.345678 UTC', 5)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543 UTC', 6)," +
                "(TIMESTAMP '2020-02-21 16:12:12.654321 UTC', 7)";
        assertUpdate("INSERT INTO test_day_transform_timestamptz " + values, 13);
        assertThat(query("SELECT * FROM test_day_transform_timestamptz"))
                .matches(values);

        String expected = "VALUES " +
                "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                "(DATE '1969-12-25', 1, TIMESTAMP '1969-12-25 15:13:12.876543 UTC', TIMESTAMP '1969-12-25 15:13:12.876543 UTC', 8, 8), " +
                "(DATE '1969-12-30', 1, TIMESTAMP '1969-12-30 18:47:33.345678 UTC', TIMESTAMP '1969-12-30 18:47:33.345678 UTC', 9, 9), " +
                "(DATE '1969-12-31', 2, TIMESTAMP '1969-12-31 00:00:00.000000 UTC', TIMESTAMP '1969-12-31 05:06:07.234567 UTC', 10, 11), " +
                "(DATE '1970-01-01', 1, TIMESTAMP '1970-01-01 12:03:08.456789 UTC', TIMESTAMP '1970-01-01 12:03:08.456789 UTC', 12, 12), " +
                "(DATE '2015-01-01', 3, TIMESTAMP '2015-01-01 10:01:23.123456 UTC', TIMESTAMP '2015-01-01 12:55:00.456789 UTC', 1, 3), " +
                "(DATE '2015-05-15', 2, TIMESTAMP '2015-05-15 13:05:01.234567 UTC', TIMESTAMP '2015-05-15 14:21:02.345678 UTC', 4, 5), " +
                "(DATE '2020-02-21', 2, TIMESTAMP '2020-02-21 15:11:11.876543 UTC', TIMESTAMP '2020-02-21 16:12:12.654321 UTC', 6, 7)";
        String expectedTimestampStats = "'1969-12-25 15:13:12.876 UTC', '2020-02-21 16:12:12.654 UTC'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                    "(DATE '1969-12-25', 1, TIMESTAMP '1969-12-25 15:13:12.876000 UTC', TIMESTAMP '1969-12-25 15:13:12.876999 UTC', 8, 8), " +
                    "(DATE '1969-12-30', 1, TIMESTAMP '1969-12-30 18:47:33.345000 UTC', TIMESTAMP '1969-12-30 18:47:33.345999 UTC', 9, 9), " +
                    "(DATE '1969-12-31', 2, TIMESTAMP '1969-12-31 00:00:00.000000 UTC', TIMESTAMP '1969-12-31 05:06:07.234999 UTC', 10, 11), " +
                    "(DATE '1970-01-01', 1, TIMESTAMP '1970-01-01 12:03:08.456000 UTC', TIMESTAMP '1970-01-01 12:03:08.456999 UTC', 12, 12), " +
                    "(DATE '2015-01-01', 3, TIMESTAMP '2015-01-01 10:01:23.123000 UTC', TIMESTAMP '2015-01-01 12:55:00.456999 UTC', 1, 3), " +
                    "(DATE '2015-05-15', 2, TIMESTAMP '2015-05-15 13:05:01.234000 UTC', TIMESTAMP '2015-05-15 14:21:02.345999 UTC', 4, 5), " +
                    "(DATE '2020-02-21', 2, TIMESTAMP '2020-02-21 15:11:11.876000 UTC', TIMESTAMP '2020-02-21 16:12:12.654999 UTC', 6, 7)";
        }

        assertThat(query("SELECT partition.d_day, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_day_transform_timestamptz$partitions\""))
                .matches(expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE day_of_week(d) = 3 AND b % 7 = 3"))
                .matches("VALUES (TIMESTAMP '1969-12-31 00:00:00.000000 UTC', 10)");

        assertThat(query("SHOW STATS FOR test_day_transform_timestamptz"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE d IS NULL"))
                .isFullyPushedDown();

        // Tests run with non-UTC session, so timestamp_tz > a_date will not align with partition boundaries. Use with_timezone to align it.
        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE d >= with_timezone(DATE '2015-05-15', 'UTC')"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-05-15'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-05-15' AND d < TIMESTAMP '2015-05-15 02:00:00 Europe/Warsaw'"))
                // Engine can eliminate the table scan after connector accepts the filter pushdown
                .hasPlan(node(OutputNode.class, node(ValuesNode.class)))
                .returnsEmptyResult();

        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE d >= TIMESTAMP '2015-05-15 00:00:00 UTC'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_day_transform_timestamptz WHERE d >= TIMESTAMP '2015-05-15 00:00:00.000001 UTC'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertUpdate("DROP TABLE test_day_transform_timestamptz");
    }

    @Test
    public void testMonthTransformDate()
    {
        assertUpdate("CREATE TABLE test_month_transform_date (d DATE, b BIGINT) WITH (partitioning = ARRAY['month(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(DATE '1969-11-13', 1)," +
                "(DATE '1969-12-01', 2)," +
                "(DATE '1969-12-02', 3)," +
                "(DATE '1969-12-31', 4)," +
                "(DATE '1970-01-01', 5), " +
                "(DATE '1970-05-13', 6), " +
                "(DATE '1970-12-31', 7), " +
                "(DATE '2020-01-01', 8), " +
                "(DATE '2020-06-16', 9), " +
                "(DATE '2020-06-28', 10), " +
                "(DATE '2020-06-06', 11), " +
                "(DATE '2020-07-18', 12), " +
                "(DATE '2020-07-28', 13), " +
                "(DATE '2020-12-31', 14)";
        assertUpdate("INSERT INTO test_month_transform_date " + values, 15);
        assertQuery("SELECT * FROM test_month_transform_date", values);

        assertQuery(
                "SELECT partition.d_month, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_month_transform_date$partitions\"",
                "VALUES " +
                        "(NULL, 1, NULL, NULL, 101, 101), " +
                        "(-2, 1, DATE '1969-11-13', DATE '1969-11-13', 1, 1), " +
                        "(-1, 3, DATE '1969-12-01', DATE '1969-12-31', 2, 4), " +
                        "(0, 1, DATE '1970-01-01', DATE '1970-01-01', 5, 5), " +
                        "(4, 1, DATE '1970-05-13', DATE '1970-05-13', 6, 6), " +
                        "(11, 1, DATE '1970-12-31', DATE '1970-12-31', 7, 7), " +
                        "(600, 1, DATE '2020-01-01', DATE '2020-01-01', 8, 8), " +
                        "(605, 3, DATE '2020-06-06', DATE '2020-06-28', 9, 11), " +
                        "(606, 2, DATE '2020-07-18', DATE '2020-07-28', 12, 13), " +
                        "(611, 1, DATE '2020-12-31', DATE '2020-12-31', 14, 14)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_month_transform_date WHERE day_of_week(d) = 7 AND b % 7 = 3",
                "VALUES (DATE '2020-06-28', 10)");

        assertThat(query("SHOW STATS FOR test_month_transform_date"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0666667e0, NULL, '1969-11-13', '2020-12-31'), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 15e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_month_transform_date WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_date WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_month_transform_date WHERE d >= DATE '2020-06-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_date WHERE d >= DATE '2020-06-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_month_transform_date WHERE CAST(d AS date) >= DATE '2020-06-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_date WHERE CAST(d AS date) >= DATE '2020-06-02'"))
                .isNotFullyPushedDown(FilterNode.class);

        // d comparison with TIMESTAMP can be unwrapped
        assertThat(query("SELECT * FROM test_month_transform_date WHERE d >= TIMESTAMP '2015-06-01 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_date WHERE d >= TIMESTAMP '2015-05-01 00:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_month_transform_date");
    }

    @Test
    public void testMonthTransformTimestamp()
    {
        assertUpdate("CREATE TABLE test_month_transform_timestamp (d TIMESTAMP(6), b BIGINT) WITH (partitioning = ARRAY['month(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1969-11-15 15:13:12.876543', 8)," +
                "(TIMESTAMP '1969-11-19 18:47:33.345678', 9)," +
                "(TIMESTAMP '1969-12-01 00:00:00.000000', 10)," +
                "(TIMESTAMP '1969-12-01 05:06:07.234567', 11)," +
                "(TIMESTAMP '1970-01-01 12:03:08.456789', 12)," +
                "(TIMESTAMP '2015-01-01 10:01:23.123456', 1)," +
                "(TIMESTAMP '2015-01-01 11:10:02.987654', 2)," +
                "(TIMESTAMP '2015-01-01 12:55:00.456789', 3)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567', 4)," +
                "(TIMESTAMP '2015-05-15 14:21:02.345678', 5)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543', 6)," +
                "(TIMESTAMP '2020-02-21 16:12:12.654321', 7)";
        assertUpdate("INSERT INTO test_month_transform_timestamp " + values, 13);
        assertQuery("SELECT * FROM test_month_transform_timestamp", values);

        @Language("SQL") String expected = "VALUES " +
                "(NULL, 1, NULL, NULL, 101, 101), " +
                "(-2, 2, TIMESTAMP '1969-11-15 15:13:12.876543', TIMESTAMP '1969-11-19 18:47:33.345678', 8, 9), " +
                "(-1, 2, TIMESTAMP '1969-12-01 00:00:00.000000', TIMESTAMP '1969-12-01 05:06:07.234567', 10, 11), " +
                "(0, 1, TIMESTAMP '1970-01-01 12:03:08.456789', TIMESTAMP '1970-01-01 12:03:08.456789', 12, 12), " +
                "(540, 3, TIMESTAMP '2015-01-01 10:01:23.123456', TIMESTAMP '2015-01-01 12:55:00.456789', 1, 3), " +
                "(544, 2, TIMESTAMP '2015-05-15 13:05:01.234567', TIMESTAMP '2015-05-15 14:21:02.345678', 4, 5), " +
                "(601, 2, TIMESTAMP '2020-02-21 15:11:11.876543', TIMESTAMP '2020-02-21 16:12:12.654321', 6, 7)";
        String expectedTimestampStats = "'1969-11-15 15:13:12.876543', '2020-02-21 16:12:12.654321'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, 1, NULL, NULL, 101, 101), " +
                    "(-2, 2, TIMESTAMP '1969-11-15 15:13:12.876000', TIMESTAMP '1969-11-19 18:47:33.345999', 8, 9), " +
                    "(-1, 2, TIMESTAMP '1969-12-01 00:00:00.000000', TIMESTAMP '1969-12-01 05:06:07.234999', 10, 11), " +
                    "(0, 1, TIMESTAMP '1970-01-01 12:03:08.456000', TIMESTAMP '1970-01-01 12:03:08.456999', 12, 12), " +
                    "(540, 3, TIMESTAMP '2015-01-01 10:01:23.123000', TIMESTAMP '2015-01-01 12:55:00.456999', 1, 3), " +
                    "(544, 2, TIMESTAMP '2015-05-15 13:05:01.234000', TIMESTAMP '2015-05-15 14:21:02.345999', 4, 5), " +
                    "(601, 2, TIMESTAMP '2020-02-21 15:11:11.876000', TIMESTAMP '2020-02-21 16:12:12.654999', 6, 7)";
            expectedTimestampStats = "'1969-11-15 15:13:12.876000', '2020-02-21 16:12:12.654999'";
        }

        assertQuery("SELECT partition.d_month, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_month_transform_timestamp$partitions\"", expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_month_transform_timestamp WHERE day_of_week(d) = 1 AND b % 7 = 3",
                "VALUES (TIMESTAMP '1969-12-01 00:00:00.000000', 10)");

        assertThat(query("SHOW STATS FOR test_month_transform_timestamp"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d >= DATE '2015-05-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d >= DATE '2015-05-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE CAST(d AS date) >= DATE '2015-05-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE CAST(d AS date) >= DATE '2015-05-02'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d >= TIMESTAMP '2015-05-01 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamp WHERE d >= TIMESTAMP '2015-05-01 00:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_month_transform_timestamp");
    }

    @Test
    public void testMonthTransformTimestampWithTimeZone()
    {
        assertUpdate("CREATE TABLE test_month_transform_timestamptz (d timestamp(6) with time zone, b integer) WITH (partitioning = ARRAY['month(d)'])");

        String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1969-11-15 15:13:12.876543 UTC', 8)," +
                "(TIMESTAMP '1969-11-19 18:47:33.345678 UTC', 9)," +
                "(TIMESTAMP '1969-12-01 00:00:00.000000 UTC', 10)," +
                "(TIMESTAMP '1969-12-01 05:06:07.234567 UTC', 11)," +
                "(TIMESTAMP '1970-01-01 12:03:08.456789 UTC', 12)," +
                "(TIMESTAMP '2015-01-01 10:01:23.123456 UTC', 1)," +
                "(TIMESTAMP '2015-01-01 11:10:02.987654 UTC', 2)," +
                "(TIMESTAMP '2015-01-01 12:55:00.456789 UTC', 3)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567 UTC', 4)," +
                "(TIMESTAMP '2015-05-15 14:21:02.345678 UTC', 5)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543 UTC', 6)," +
                "(TIMESTAMP '2020-02-21 16:12:12.654321 UTC', 7)";
        assertUpdate("INSERT INTO test_month_transform_timestamptz " + values, 13);
        assertThat(query("SELECT * FROM test_month_transform_timestamptz"))
                .matches(values);

        String expected = "VALUES " +
                "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                "(-2, 2, TIMESTAMP '1969-11-15 15:13:12.876543 UTC', TIMESTAMP '1969-11-19 18:47:33.345678 UTC', 8, 9), " +
                "(-1, 2, TIMESTAMP '1969-12-01 00:00:00.000000 UTC', TIMESTAMP '1969-12-01 05:06:07.234567 UTC', 10, 11), " +
                "(0, 1, TIMESTAMP '1970-01-01 12:03:08.456789 UTC', TIMESTAMP '1970-01-01 12:03:08.456789 UTC', 12, 12), " +
                "(540, 3, TIMESTAMP '2015-01-01 10:01:23.123456 UTC', TIMESTAMP '2015-01-01 12:55:00.456789 UTC', 1, 3), " +
                "(544, 2, TIMESTAMP '2015-05-15 13:05:01.234567 UTC', TIMESTAMP '2015-05-15 14:21:02.345678 UTC', 4, 5), " +
                "(601, 2, TIMESTAMP '2020-02-21 15:11:11.876543 UTC', TIMESTAMP '2020-02-21 16:12:12.654321 UTC', 6, 7)";
        String expectedTimestampStats = "'1969-11-15 15:13:12.876 UTC', '2020-02-21 16:12:12.654 UTC'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                    "(-2, 2, TIMESTAMP '1969-11-15 15:13:12.876000 UTC', TIMESTAMP '1969-11-19 18:47:33.345999 UTC', 8, 9), " +
                    "(-1, 2, TIMESTAMP '1969-12-01 00:00:00.000000 UTC', TIMESTAMP '1969-12-01 05:06:07.234999 UTC', 10, 11), " +
                    "(0, 1, TIMESTAMP '1970-01-01 12:03:08.456000 UTC', TIMESTAMP '1970-01-01 12:03:08.456999 UTC', 12, 12), " +
                    "(540, 3, TIMESTAMP '2015-01-01 10:01:23.123000 UTC', TIMESTAMP '2015-01-01 12:55:00.456999 UTC', 1, 3), " +
                    "(544, 2, TIMESTAMP '2015-05-15 13:05:01.234000 UTC', TIMESTAMP '2015-05-15 14:21:02.345999 UTC', 4, 5), " +
                    "(601, 2, TIMESTAMP '2020-02-21 15:11:11.876000 UTC', TIMESTAMP '2020-02-21 16:12:12.654999 UTC', 6, 7)";
        }

        assertThat(query("SELECT partition.d_month, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_month_transform_timestamptz$partitions\""))
                .matches(expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE day_of_week(d) = 1 AND b % 7 = 3"))
                .matches("VALUES (TIMESTAMP '1969-12-01 00:00:00.000000 UTC', 10)");

        assertThat(query("SHOW STATS FOR test_month_transform_timestamptz"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d IS NULL"))
                .isFullyPushedDown();

        // Tests run with non-UTC session, so timestamp_tz > a_date will not align with partition boundaries. Use with_timezone to align it.
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d >= with_timezone(DATE '2015-05-01', 'UTC')"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d >= with_timezone(DATE '2015-05-02', 'UTC')"))
                .isNotFullyPushedDown(FilterNode.class);

        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-05-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-05-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-05-01' AND d < TIMESTAMP '2015-05-01 02:00:00 Europe/Warsaw'"))
                // Engine can eliminate the table scan after connector accepts the filter pushdown
                .hasPlan(node(OutputNode.class, node(ValuesNode.class)))
                .returnsEmptyResult();

        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d >= TIMESTAMP '2015-05-01 00:00:00 UTC'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_month_transform_timestamptz WHERE d >= TIMESTAMP '2015-05-01 00:00:00.000001 UTC'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertUpdate("DROP TABLE test_month_transform_timestamptz");
    }

    @Test
    public void testYearTransformDate()
    {
        assertUpdate("CREATE TABLE test_year_transform_date (d DATE, b BIGINT) WITH (partitioning = ARRAY['year(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(DATE '1968-10-13', 1), " +
                "(DATE '1969-01-01', 2), " +
                "(DATE '1969-03-15', 3), " +
                "(DATE '1970-01-01', 4), " +
                "(DATE '1970-03-05', 5), " +
                "(DATE '2015-01-01', 6), " +
                "(DATE '2015-06-16', 7), " +
                "(DATE '2015-07-28', 8), " +
                "(DATE '2016-05-15', 9), " +
                "(DATE '2016-06-06', 10), " +
                "(DATE '2020-02-21', 11), " +
                "(DATE '2020-11-10', 12)";
        assertUpdate("INSERT INTO test_year_transform_date " + values, 13);
        assertQuery("SELECT * FROM test_year_transform_date", values);

        assertQuery(
                "SELECT partition.d_year, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_year_transform_date$partitions\"",
                "VALUES " +
                        "(NULL, 1, NULL, NULL, 101, 101), " +
                        "(-2, 1, DATE '1968-10-13', DATE '1968-10-13', 1, 1), " +
                        "(-1, 2, DATE '1969-01-01', DATE '1969-03-15', 2, 3), " +
                        "(0, 2, DATE '1970-01-01', DATE '1970-03-05', 4, 5), " +
                        "(45, 3, DATE '2015-01-01', DATE '2015-07-28', 6, 8), " +
                        "(46, 2, DATE '2016-05-15', DATE '2016-06-06', 9, 10), " +
                        "(50, 2, DATE '2020-02-21', DATE '2020-11-10', 11, 12)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_year_transform_date WHERE day_of_week(d) = 1 AND b % 7 = 3",
                "VALUES (DATE '2016-06-06', 10)");

        assertThat(query("SHOW STATS FOR test_year_transform_date"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, '1968-10-13', '2020-11-10'), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_year_transform_date WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_date WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_year_transform_date WHERE d >= DATE '2015-01-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_date WHERE d >= DATE '2015-01-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_year_transform_date WHERE CAST(d AS date) >= DATE '2015-01-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_date WHERE CAST(d AS date) >= DATE '2015-01-02'"))
                .isNotFullyPushedDown(FilterNode.class);

        // d comparison with TIMESTAMP can be unwrapped
        assertThat(query("SELECT * FROM test_year_transform_date WHERE d >= TIMESTAMP '2015-01-01 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_date WHERE d >= TIMESTAMP '2015-01-01 00:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_year_transform_date");
    }

    @Test
    public void testYearTransformTimestamp()
    {
        assertUpdate("CREATE TABLE test_year_transform_timestamp (d TIMESTAMP(6), b BIGINT) WITH (partitioning = ARRAY['year(d)'])");

        @Language("SQL") String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1968-03-15 15:13:12.876543', 1)," +
                "(TIMESTAMP '1968-11-19 18:47:33.345678', 2)," +
                "(TIMESTAMP '1969-01-01 00:00:00.000000', 3)," +
                "(TIMESTAMP '1969-01-01 05:06:07.234567', 4)," +
                "(TIMESTAMP '1970-01-18 12:03:08.456789', 5)," +
                "(TIMESTAMP '1970-03-14 10:01:23.123456', 6)," +
                "(TIMESTAMP '1970-08-19 11:10:02.987654', 7)," +
                "(TIMESTAMP '1970-12-31 12:55:00.456789', 8)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567', 9)," +
                "(TIMESTAMP '2015-09-15 14:21:02.345678', 10)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543', 11)," +
                "(TIMESTAMP '2020-08-21 16:12:12.654321', 12)";
        assertUpdate("INSERT INTO test_year_transform_timestamp " + values, 13);
        assertQuery("SELECT * FROM test_year_transform_timestamp", values);

        @Language("SQL") String expected = "VALUES " +
                "(NULL, 1, NULL, NULL, 101, 101), " +
                "(-2, 2, TIMESTAMP '1968-03-15 15:13:12.876543', TIMESTAMP '1968-11-19 18:47:33.345678', 1, 2), " +
                "(-1, 2, TIMESTAMP '1969-01-01 00:00:00.000000', TIMESTAMP '1969-01-01 05:06:07.234567', 3, 4), " +
                "(0, 4, TIMESTAMP '1970-01-18 12:03:08.456789', TIMESTAMP '1970-12-31 12:55:00.456789', 5, 8), " +
                "(45, 2, TIMESTAMP '2015-05-15 13:05:01.234567', TIMESTAMP '2015-09-15 14:21:02.345678', 9, 10), " +
                "(50, 2, TIMESTAMP '2020-02-21 15:11:11.876543', TIMESTAMP '2020-08-21 16:12:12.654321', 11, 12)";
        String expectedTimestampStats = "'1968-03-15 15:13:12.876543', '2020-08-21 16:12:12.654321'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, 1, NULL, NULL, 101, 101), " +
                    "(-2, 2, TIMESTAMP '1968-03-15 15:13:12.876000', TIMESTAMP '1968-11-19 18:47:33.345999', 1, 2), " +
                    "(-1, 2, TIMESTAMP '1969-01-01 00:00:00.000000', TIMESTAMP '1969-01-01 05:06:07.234999', 3, 4), " +
                    "(0, 4, TIMESTAMP '1970-01-18 12:03:08.456000', TIMESTAMP '1970-12-31 12:55:00.456999', 5, 8), " +
                    "(45, 2, TIMESTAMP '2015-05-15 13:05:01.234000', TIMESTAMP '2015-09-15 14:21:02.345999', 9, 10), " +
                    "(50, 2, TIMESTAMP '2020-02-21 15:11:11.876000', TIMESTAMP '2020-08-21 16:12:12.654999', 11, 12)";
            expectedTimestampStats = "'1968-03-15 15:13:12.876000', '2020-08-21 16:12:12.654999'";
        }

        assertQuery("SELECT partition.d_year, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_year_transform_timestamp$partitions\"", expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_year_transform_timestamp WHERE day_of_week(d) = 2 AND b % 7 = 3",
                "VALUES (TIMESTAMP '2015-09-15 14:21:02.345678', 10)");

        assertThat(query("SHOW STATS FOR test_year_transform_timestamp"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d >= DATE '2015-01-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d >= DATE '2015-01-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE CAST(d AS date) >= DATE '2015-01-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE CAST(d AS date) >= DATE '2015-01-02'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d >= TIMESTAMP '2015-01-01 00:00:00'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamp WHERE d >= TIMESTAMP '2015-01-01 00:00:00.000001'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_year_transform_timestamp");
    }

    @Test
    public void testYearTransformTimestampWithTimeZone()
    {
        assertUpdate("CREATE TABLE test_year_transform_timestamptz (d timestamp(6) with time zone, b integer) WITH (partitioning = ARRAY['year(d)'])");

        String values = "VALUES " +
                "(NULL, 101)," +
                "(TIMESTAMP '1968-03-15 15:13:12.876543 UTC', 1)," +
                "(TIMESTAMP '1968-11-19 18:47:33.345678 UTC', 2)," +
                "(TIMESTAMP '1969-01-01 00:00:00.000000 UTC', 3)," +
                "(TIMESTAMP '1969-01-01 05:06:07.234567 UTC', 4)," +
                "(TIMESTAMP '1970-01-18 12:03:08.456789 UTC', 5)," +
                "(TIMESTAMP '1970-03-14 10:01:23.123456 UTC', 6)," +
                "(TIMESTAMP '1970-08-19 11:10:02.987654 UTC', 7)," +
                "(TIMESTAMP '1970-12-31 12:55:00.456789 UTC', 8)," +
                "(TIMESTAMP '2015-05-15 13:05:01.234567 UTC', 9)," +
                "(TIMESTAMP '2015-09-15 14:21:02.345678 UTC', 10)," +
                "(TIMESTAMP '2020-02-21 15:11:11.876543 UTC', 11)," +
                "(TIMESTAMP '2020-08-21 16:12:12.654321 UTC', 12)";
        assertUpdate("INSERT INTO test_year_transform_timestamptz " + values, 13);
        assertThat(query("SELECT * FROM test_year_transform_timestamptz"))
                .matches(values);

        String expected = "VALUES " +
                "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                "(-2, 2, TIMESTAMP '1968-03-15 15:13:12.876543 UTC', TIMESTAMP '1968-11-19 18:47:33.345678 UTC', 1, 2), " +
                "(-1, 2, TIMESTAMP '1969-01-01 00:00:00.000000 UTC', TIMESTAMP '1969-01-01 05:06:07.234567 UTC', 3, 4), " +
                "(0, 4, TIMESTAMP '1970-01-18 12:03:08.456789 UTC', TIMESTAMP '1970-12-31 12:55:00.456789 UTC', 5, 8), " +
                "(45, 2, TIMESTAMP '2015-05-15 13:05:01.234567 UTC', TIMESTAMP '2015-09-15 14:21:02.345678 UTC', 9, 10), " +
                "(50, 2, TIMESTAMP '2020-02-21 15:11:11.876543 UTC', TIMESTAMP '2020-08-21 16:12:12.654321 UTC', 11, 12)";
        String expectedTimestampStats = "'1968-03-15 15:13:12.876 UTC', '2020-08-21 16:12:12.654 UTC'";
        if (format == ORC) {
            expected = "VALUES " +
                    "(NULL, BIGINT '1', NULL, NULL, 101, 101), " +
                    "(-2, 2, TIMESTAMP '1968-03-15 15:13:12.876000 UTC', TIMESTAMP '1968-11-19 18:47:33.345999 UTC', 1, 2), " +
                    "(-1, 2, TIMESTAMP '1969-01-01 00:00:00.000000 UTC', TIMESTAMP '1969-01-01 05:06:07.234999 UTC', 3, 4), " +
                    "(0, 4, TIMESTAMP '1970-01-18 12:03:08.456000 UTC', TIMESTAMP '1970-12-31 12:55:00.456999 UTC', 5, 8), " +
                    "(45, 2, TIMESTAMP '2015-05-15 13:05:01.234000 UTC', TIMESTAMP '2015-09-15 14:21:02.345999 UTC', 9, 10), " +
                    "(50, 2, TIMESTAMP '2020-02-21 15:11:11.876000 UTC', TIMESTAMP '2020-08-21 16:12:12.654999 UTC', 11, 12)";
        }

        assertThat(query("SELECT partition.d_year, record_count, data.d.min, data.d.max, data.b.min, data.b.max FROM \"test_year_transform_timestamptz$partitions\""))
                .matches(expected);

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE day_of_week(d) = 2 AND b % 7 = 3"))
                .matches("VALUES (TIMESTAMP '2015-09-15 14:21:02.345678 UTC', 10)");

        assertThat(query("SHOW STATS FOR test_year_transform_timestamptz"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0769231e0, NULL, " + expectedTimestampStats + "), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 13e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d IS NULL"))
                .isFullyPushedDown();

        // Tests run with non-UTC session, so timestamp_tz > a_date will not align with partition boundaries. Use with_timezone to align it.
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d >= with_timezone(DATE '2015-01-01', 'UTC')"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d >= with_timezone(DATE '2015-01-02', 'UTC')"))
                .isNotFullyPushedDown(FilterNode.class);

        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-01-01'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-01-02'"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE CAST(d AS date) >= DATE '2015-01-01' AND d < TIMESTAMP '2015-01-01 01:00:00 Europe/Warsaw'"))
                // Engine can eliminate the table scan after connector accepts the filter pushdown
                .hasPlan(node(OutputNode.class, node(ValuesNode.class)))
                .returnsEmptyResult();

        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d >= TIMESTAMP '2015-01-01 00:00:00 UTC'"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_year_transform_timestamptz WHERE d >= TIMESTAMP '2015-01-01 00:00:00.000001 UTC'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertUpdate("DROP TABLE test_year_transform_timestamptz");
    }

    @Test
    public void testTruncateTextTransform()
    {
        assertUpdate("CREATE TABLE test_truncate_text_transform (d VARCHAR, b BIGINT) WITH (partitioning = ARRAY['truncate(d, 2)'])");
        String select = "SELECT partition.d_trunc, record_count, data.d.min AS d_min, data.d.max AS d_max, data.b.min AS b_min, data.b.max AS b_max FROM \"test_truncate_text_transform$partitions\"";

        assertUpdate("INSERT INTO test_truncate_text_transform VALUES" +
                "(NULL, 101)," +
                "('abcd', 1)," +
                "('abxy', 2)," +
                "('ab598', 3)," +
                "('mommy', 4)," +
                "('moscow', 5)," +
                "('Greece', 6)," +
                "('Grozny', 7)", 8);

        assertQuery("SELECT partition.d_trunc FROM \"test_truncate_text_transform$partitions\"", "VALUES NULL, 'ab', 'mo', 'Gr'");

        assertQuery("SELECT b FROM test_truncate_text_transform WHERE substring(d, 1, 2) = 'ab'", "VALUES 1, 2, 3");
        assertQuery(select + " WHERE partition.d_trunc = 'ab'", "VALUES ('ab', 3, 'ab598', 'abxy', 1, 3)");

        assertQuery("SELECT b FROM test_truncate_text_transform WHERE substring(d, 1, 2) = 'mo'", "VALUES 4, 5");
        assertQuery(select + " WHERE partition.d_trunc = 'mo'", "VALUES ('mo', 2, 'mommy', 'moscow', 4, 5)");

        assertQuery("SELECT b FROM test_truncate_text_transform WHERE substring(d, 1, 2) = 'Gr'", "VALUES 6, 7");
        assertQuery(select + " WHERE partition.d_trunc = 'Gr'", "VALUES ('Gr', 2, 'Greece', 'Grozny', 6, 7)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_truncate_text_transform WHERE length(d) = 4 AND b % 7 = 2",
                "VALUES ('abxy', 2)");

        assertThat(query("SHOW STATS FOR test_truncate_text_transform"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', " + (format == PARQUET ? "205e0" : "NULL") + ", NULL, 0.125e0, NULL, NULL, NULL), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 8e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d >= 'ab'"))
                .isNotFullyPushedDown(FilterNode.class); // TODO subsume partition boundary filters on varchar
        // Currently, prefix-checking LIKE -> range conversion is part of DomainTranslator and doesn't allow for filter elimination. TODO subsume prefix-checking LIKE with truncate().
        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d LIKE 'ab%'"))
                .isNotFullyPushedDown(FilterNode.class);
        // condition to long to subsume, we use truncate(2)
        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d >= 'abc'"))
                .isNotFullyPushedDown(FilterNode.class);
        // condition to long to subsume, we use truncate(2)
        assertThat(query("SELECT * FROM test_truncate_text_transform WHERE d LIKE 'abc%'"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_truncate_text_transform");
    }

    @Test(dataProvider = "truncateNumberTypesProvider")
    public void testTruncateIntegerTransform(String dataType)
    {
        String table = format("test_truncate_%s_transform", dataType);
        assertUpdate(format("CREATE TABLE " + table + " (d %s, b BIGINT) WITH (partitioning = ARRAY['truncate(d, 10)'])", dataType));
        String select = "SELECT partition.d_trunc, record_count, data.d.min AS d_min, data.d.max AS d_max, data.b.min AS b_min, data.b.max AS b_max FROM \"" + table + "$partitions\"";

        assertUpdate("INSERT INTO " + table + " VALUES" +
                "(NULL, 101)," +
                "(0, 1)," +
                "(1, 2)," +
                "(5, 3)," +
                "(9, 4)," +
                "(10, 5)," +
                "(11, 6)," +
                "(120, 7)," +
                "(121, 8)," +
                "(123, 9)," +
                "(-1, 10)," +
                "(-5, 11)," +
                "(-10, 12)," +
                "(-11, 13)," +
                "(-123, 14)," +
                "(-130, 15)", 16);

        assertQuery("SELECT partition.d_trunc FROM \"" + table + "$partitions\"", "VALUES NULL, 0, 10, 120, -10, -20, -130");

        assertQuery("SELECT b FROM " + table + " WHERE d IN (0, 1, 5, 9)", "VALUES 1, 2, 3, 4");
        assertQuery(select + " WHERE partition.d_trunc = 0", "VALUES (0, 4, 0, 9, 1, 4)");

        assertQuery("SELECT b FROM " + table + " WHERE d IN (10, 11)", "VALUES 5, 6");
        assertQuery(select + " WHERE partition.d_trunc = 10", "VALUES (10, 2, 10, 11, 5, 6)");

        assertQuery("SELECT b FROM " + table + " WHERE d IN (120, 121, 123)", "VALUES 7, 8, 9");
        assertQuery(select + " WHERE partition.d_trunc = 120", "VALUES (120, 3, 120, 123, 7, 9)");

        assertQuery("SELECT b FROM " + table + " WHERE d IN (-1, -5, -10)", "VALUES 10, 11, 12");
        assertQuery(select + " WHERE partition.d_trunc = -10", "VALUES (-10, 3, -10, -1, 10, 12)");

        assertQuery("SELECT b FROM " + table + " WHERE d = -11", "VALUES 13");
        assertQuery(select + " WHERE partition.d_trunc = -20", "VALUES (-20, 1, -11, -11, 13, 13)");

        assertQuery("SELECT b FROM " + table + " WHERE d IN (-123, -130)", "VALUES 14, 15");
        assertQuery(select + " WHERE partition.d_trunc = -130", "VALUES (-130, 2, -130, -123, 14, 15)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM " + table + " WHERE d % 10 = -1 AND b % 7 = 3",
                "VALUES (-1, 10)");

        assertThat(query("SHOW STATS FOR " + table))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.0625e0, NULL, '-130', '123'), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 16e0, NULL, NULL)");

        assertThat(query("SELECT * FROM " + table + " WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM " + table + " WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM " + table + " WHERE d >= 10"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM " + table + " WHERE d > 10"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM " + table + " WHERE d >= 11"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable(table);
    }

    @DataProvider
    public Object[][] truncateNumberTypesProvider()
    {
        return new Object[][] {
                {"integer"},
                {"bigint"},
        };
    }

    @Test
    public void testTruncateDecimalTransform()
    {
        assertUpdate("CREATE TABLE test_truncate_decimal_transform (d DECIMAL(9, 2), b BIGINT) WITH (partitioning = ARRAY['truncate(d, 10)'])");
        String select = "SELECT partition.d_trunc, record_count, data.d.min AS d_min, data.d.max AS d_max, data.b.min AS b_min, data.b.max AS b_max FROM \"test_truncate_decimal_transform$partitions\"";

        assertUpdate("INSERT INTO test_truncate_decimal_transform VALUES" +
                "(NULL, 101)," +
                "(12.34, 1)," +
                "(12.30, 2)," +
                "(12.29, 3)," +
                "(0.05, 4)," +
                "(-0.05, 5)", 6);

        assertQuery("SELECT partition.d_trunc FROM \"test_truncate_decimal_transform$partitions\"", "VALUES NULL, 12.30, 12.20, 0.00, -0.10");

        assertQuery("SELECT b FROM test_truncate_decimal_transform WHERE d IN (12.34, 12.30)", "VALUES 1, 2");
        assertQuery(select + " WHERE partition.d_trunc = 12.30", "VALUES (12.30, 2, 12.30, 12.34, 1, 2)");

        assertQuery("SELECT b FROM test_truncate_decimal_transform WHERE d = 12.29", "VALUES 3");
        assertQuery(select + " WHERE partition.d_trunc = 12.20", "VALUES (12.20, 1, 12.29, 12.29, 3, 3)");

        assertQuery("SELECT b FROM test_truncate_decimal_transform WHERE d = 0.05", "VALUES 4");
        assertQuery(select + " WHERE partition.d_trunc = 0.00", "VALUES (0.00, 1, 0.05, 0.05, 4, 4)");

        assertQuery("SELECT b FROM test_truncate_decimal_transform WHERE d = -0.05", "VALUES 5");
        assertQuery(select + " WHERE partition.d_trunc = -0.10", "VALUES (-0.10, 1, -0.05, -0.05, 5, 5)");

        // Exercise IcebergMetadata.applyFilter with non-empty Constraint.predicate, via non-pushdownable predicates
        assertQuery(
                "SELECT * FROM test_truncate_decimal_transform WHERE d * 100 % 10 = 9 AND b % 7 = 3",
                "VALUES (12.29, 3)");

        assertThat(query("SHOW STATS FOR test_truncate_decimal_transform"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', NULL, NULL, 0.166667e0, NULL, '-0.05', '12.34'), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '101'), " +
                        "  (NULL, NULL, NULL, NULL, 6e0, NULL, NULL)");

        assertThat(query("SELECT * FROM test_truncate_decimal_transform WHERE d IS NOT NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM test_truncate_decimal_transform WHERE d IS NULL"))
                .isFullyPushedDown();

        assertThat(query("SELECT * FROM test_truncate_decimal_transform WHERE d >= 12.20"))
                .isNotFullyPushedDown(FilterNode.class); // TODO subsume partition boundary filters on decimals
        assertThat(query("SELECT * FROM test_truncate_decimal_transform WHERE d > 12.20"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_truncate_decimal_transform WHERE d >= 12.21"))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable("test_truncate_decimal_transform");
    }

    @Test
    public void testBucketTransform()
    {
        testBucketTransformForType("DATE", "DATE '2020-05-19'", "DATE '2020-08-19'", "DATE '2020-11-19'");
        testBucketTransformForType("VARCHAR", "CAST('abcd' AS VARCHAR)", "CAST('mommy' AS VARCHAR)", "CAST('abxy' AS VARCHAR)");
        testBucketTransformForType("INTEGER", "10", "12", "20");
        testBucketTransformForType("BIGINT", "CAST(100000000 AS BIGINT)", "CAST(200000002 AS BIGINT)", "CAST(400000001 AS BIGINT)");
        testBucketTransformForType(
                "UUID",
                "CAST('206caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID)",
                "CAST('906caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID)",
                "CAST('406caec7-68b9-4778-81b2-a12ece70c8b1' AS UUID)");
    }

    protected void testBucketTransformForType(
            String type,
            String value,
            String greaterValueInSameBucket,
            String valueInOtherBucket)
    {
        String tableName = format("test_bucket_transform%s", type.toLowerCase(Locale.ENGLISH));

        assertUpdate(format("CREATE TABLE %s (d %s) WITH (partitioning = ARRAY['bucket(d, 2)'])", tableName, type));
        assertUpdate(format("INSERT INTO %s VALUES (NULL), (%s), (%s), (%s)", tableName, value, greaterValueInSameBucket, valueInOtherBucket), 4);
        assertThat(query(format("SELECT * FROM %s", tableName))).matches(format("VALUES (NULL), (%s), (%s), (%s)", value, greaterValueInSameBucket, valueInOtherBucket));
        assertThat(query(format("SELECT * FROM %s WHERE d <= %s AND (rand() = 42 OR d != %s)", tableName, value, valueInOtherBucket)))
                .matches("VALUES " + value);
        assertThat(query(format("SELECT * FROM %s WHERE d >= %s AND (rand() = 42 OR d != %s)", tableName, greaterValueInSameBucket, valueInOtherBucket)))
                .matches("VALUES " + greaterValueInSameBucket);

        String selectFromPartitions = format("SELECT partition.d_bucket, record_count, data.d.min AS d_min, data.d.max AS d_max FROM \"%s$partitions\"", tableName);

        if (supportsIcebergFileStatistics(type)) {
            assertQuery(selectFromPartitions + " WHERE partition.d_bucket = 0", format("VALUES(0, %d, %s, %s)", 2, value, greaterValueInSameBucket));
            assertQuery(selectFromPartitions + " WHERE partition.d_bucket = 1", format("VALUES(1, %d, %s, %s)", 1, valueInOtherBucket, valueInOtherBucket));
        }
        else {
            assertQuery(selectFromPartitions + " WHERE partition.d_bucket = 0", format("VALUES(0, %d, null, null)", 2));
            assertQuery(selectFromPartitions + " WHERE partition.d_bucket = 1", format("VALUES(1, %d, null, null)", 1));
        }

        assertThat(query("SHOW STATS FOR " + tableName))
                .skippingTypesCheck()
                .projected(0, 2, 3, 4) // data size, min and max may vary between types
                .matches("VALUES " +
                        "  ('d', NULL, 0.25e0, NULL), " +
                        "  (NULL, NULL, NULL, 4e0)");

        assertThat(query("SELECT * FROM " + tableName + " WHERE d IS NULL"))
                .isFullyPushedDown();
        assertThat(query("SELECT * FROM " + tableName + " WHERE d IS NOT NULL"))
                .isNotFullyPushedDown(FilterNode.class); // this could be subsumed

        // Bucketing transform doesn't allow comparison filter elimination
        assertThat(query("SELECT * FROM " + tableName + " WHERE d >= " + value))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM " + tableName + " WHERE d >= " + greaterValueInSameBucket))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM " + tableName + " WHERE d >= " + valueInOtherBucket))
                .isNotFullyPushedDown(FilterNode.class);

        dropTable(tableName);
    }

    @Test
    public void testApplyFilterWithNonEmptyConstraintPredicate()
    {
        assertUpdate("CREATE TABLE test_apply_functional_constraint (d VARCHAR, b BIGINT) WITH (partitioning = ARRAY['bucket(d, 2)'])");
        assertUpdate(
                "INSERT INTO test_apply_functional_constraint VALUES" +
                        "('abcd', 1)," +
                        "('abxy', 2)," +
                        "('ab598', 3)," +
                        "('mommy', 4)," +
                        "('moscow', 5)," +
                        "('Greece', 6)," +
                        "('Grozny', 7)",
                7);

        assertQuery(
                "SELECT * FROM test_apply_functional_constraint WHERE length(d) = 4 AND b % 7 = 2",
                "VALUES ('abxy', 2)");

        assertUpdate("DROP TABLE test_apply_functional_constraint");
    }

    @Test
    public void testVoidTransform()
    {
        assertUpdate("CREATE TABLE test_void_transform (d VARCHAR, b BIGINT) WITH (partitioning = ARRAY['void(d)'])");
        String values = "VALUES " +
                "('abcd', 1)," +
                "('abxy', 2)," +
                "('ab598', 3)," +
                "('mommy', 4)," +
                "('Warsaw', 5)," +
                "(NULL, 6)," +
                "(NULL, 7)";
        assertUpdate("INSERT INTO test_void_transform " + values, 7);
        assertQuery("SELECT * FROM test_void_transform", values);

        assertQuery("SELECT COUNT(*) FROM \"test_void_transform$partitions\"", "SELECT 1");
        assertQuery(
                "SELECT partition.d_null, record_count, file_count, data.d.min, data.d.max, data.d.null_count, data.d.nan_count, data.b.min, data.b.max, data.b.null_count, data.b.nan_count FROM \"test_void_transform$partitions\"",
                "VALUES (NULL, 7, 1, 'Warsaw', 'mommy', 2, NULL, 1, 7, 0, NULL)");

        assertQuery(
                "SELECT d, b FROM test_void_transform WHERE d IS NOT NULL",
                "VALUES " +
                        "('abcd', 1)," +
                        "('abxy', 2)," +
                        "('ab598', 3)," +
                        "('mommy', 4)," +
                        "('Warsaw', 5)");

        assertQuery("SELECT b FROM test_void_transform WHERE d IS NULL", "VALUES 6, 7");

        assertThat(query("SHOW STATS FOR test_void_transform"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('d', " + (format == PARQUET ? "76e0" : "NULL") + ", NULL, 0.2857142857142857, NULL, NULL, NULL), " +
                        "  ('b', NULL, NULL, 0e0, NULL, '1', '7'), " +
                        "  (NULL, NULL, NULL, NULL, 7e0, NULL, NULL)");

        // Void transform doesn't allow filter elimination
        assertThat(query("SELECT * FROM test_void_transform WHERE d IS NULL"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_void_transform WHERE d IS NOT NULL"))
                .isNotFullyPushedDown(FilterNode.class);
        assertThat(query("SELECT * FROM test_void_transform WHERE d >= 'abc'"))
                .isNotFullyPushedDown(FilterNode.class);

        assertUpdate("DROP TABLE " + "test_void_transform");
    }

    @Test
    public void testMetadataDeleteSimple()
    {
        assertUpdate("CREATE TABLE test_metadata_delete_simple (col1 BIGINT, col2 BIGINT) WITH (partitioning = ARRAY['col1'])");
        assertUpdate("INSERT INTO test_metadata_delete_simple VALUES(1, 100), (1, 101), (1, 102), (2, 200), (2, 201), (3, 300)", 6);
        assertQuery("SELECT sum(col2) FROM test_metadata_delete_simple", "SELECT 1004");
        assertQuery("SELECT count(*) FROM \"test_metadata_delete_simple$partitions\"", "SELECT 3");
        assertUpdate("DELETE FROM test_metadata_delete_simple WHERE col1 = 1", 3);
        assertQuery("SELECT sum(col2) FROM test_metadata_delete_simple", "SELECT 701");
        assertQuery("SELECT count(*) FROM \"test_metadata_delete_simple$partitions\"", "SELECT 2");
        dropTable("test_metadata_delete_simple");
    }

    @Test
    public void testMetadataDelete()
    {
        assertUpdate("CREATE TABLE test_metadata_delete (" +
                "  orderkey BIGINT," +
                "  linenumber INTEGER," +
                "  linestatus VARCHAR" +
                ") " +
                "WITH (" +
                "  partitioning = ARRAY[ 'linenumber', 'linestatus' ]" +
                ")");

        assertUpdate(
                "" +
                        "INSERT INTO test_metadata_delete " +
                        "SELECT orderkey, linenumber, linestatus " +
                        "FROM tpch.tiny.lineitem",
                "SELECT count(*) FROM lineitem");

        assertQuery("SELECT COUNT(*) FROM \"test_metadata_delete$partitions\"", "SELECT 14");

        assertUpdate("DELETE FROM test_metadata_delete WHERE linestatus = 'F' AND linenumber = 3", 5378);
        assertQuery("SELECT * FROM test_metadata_delete", "SELECT orderkey, linenumber, linestatus FROM lineitem WHERE linestatus <> 'F' or linenumber <> 3");
        assertQuery("SELECT count(*) FROM \"test_metadata_delete$partitions\"", "SELECT 13");

        assertUpdate("DELETE FROM test_metadata_delete WHERE linestatus='O'", 30049);
        assertQuery("SELECT count(*) FROM \"test_metadata_delete$partitions\"", "SELECT 6");
        assertQuery("SELECT * FROM test_metadata_delete", "SELECT orderkey, linenumber, linestatus FROM lineitem WHERE linestatus <> 'O' AND linenumber <> 3");

        dropTable("test_metadata_delete");
    }

    @Test
    public void testInSet()
    {
        testInSet(31);
        testInSet(35);
    }

    private void testInSet(int inCount)
    {
        String values = range(1, inCount + 1)
                .mapToObj(n -> format("(%s, %s)", n, n + 10))
                .collect(joining(", "));
        String inList = range(1, inCount + 1)
                .mapToObj(Integer::toString)
                .collect(joining(", "));

        assertUpdate("CREATE TABLE test_in_set (col1 INTEGER, col2 BIGINT)");
        assertUpdate(format("INSERT INTO test_in_set VALUES %s", values), inCount);
        // This proves that SELECTs with large IN phrases work correctly
        computeActual(format("SELECT col1 FROM test_in_set WHERE col1 IN (%s)", inList));
        dropTable("test_in_set");
    }

    @Test
    public void testBasicTableStatistics()
    {
        String tableName = "test_basic_table_statistics";
        assertUpdate(format("CREATE TABLE %s (col REAL)", tableName));

        assertThat(query("SHOW STATS FOR " + tableName))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('col', 0e0, 0e0, 1e0, NULL, NULL, NULL), " +
                        "  (NULL, NULL, NULL, NULL, 0e0, NULL, NULL)");

        assertUpdate("INSERT INTO " + tableName + " VALUES -10", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES 100", 1);

        assertThat(query("SHOW STATS FOR " + tableName))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('col', NULL, NULL, 0e0, NULL, '-10.0', '100.0'), " +
                        "  (NULL, NULL, NULL, NULL, 2e0, NULL, NULL)");

        assertUpdate("INSERT INTO " + tableName + " VALUES 200", 1);

        assertThat(query("SHOW STATS FOR " + tableName))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('col', NULL, NULL, 0e0, NULL, '-10.0', '200.0'), " +
                        "  (NULL, NULL, NULL, NULL, 3e0, NULL, NULL)");

        dropTable(tableName);
    }

    @Test
    public void testMultipleColumnTableStatistics()
    {
        String tableName = "test_multiple_table_statistics";
        assertUpdate(format("CREATE TABLE %s (col1 REAL, col2 INTEGER, col3 DATE)", tableName));
        assertUpdate("INSERT INTO " + tableName + " VALUES (-10, -1, DATE '2019-06-28')", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES (100, 10, DATE '2020-01-01')", 1);

        MaterializedResult result = computeActual("SHOW STATS FOR " + tableName);

        MaterializedResult expectedStatistics =
                resultBuilder(getSession(), VARCHAR, DOUBLE, DOUBLE, DOUBLE, DOUBLE, VARCHAR, VARCHAR)
                        .row("col1", null, null, 0.0, null, "-10.0", "100.0")
                        .row("col2", null, null, 0.0, null, "-1", "10")
                        .row("col3", null, null, 0.0, null, "2019-06-28", "2020-01-01")
                        .row(null, null, null, null, 2.0, null, null)
                        .build();
        assertEquals(result, expectedStatistics);

        assertUpdate("INSERT INTO " + tableName + " VALUES (200, 20, DATE '2020-06-28')", 1);
        result = computeActual("SHOW STATS FOR " + tableName);
        expectedStatistics =
                resultBuilder(getSession(), VARCHAR, DOUBLE, DOUBLE, DOUBLE, DOUBLE, VARCHAR, VARCHAR)
                        .row("col1", null, null, 0.0, null, "-10.0", "200.0")
                        .row("col2", null, null, 0.0, null, "-1", "20")
                        .row("col3", null, null, 0.0, null, "2019-06-28", "2020-06-28")
                        .row(null, null, null, null, 3.0, null, null)
                        .build();
        assertEquals(result, expectedStatistics);

        assertUpdate("INSERT INTO " + tableName + " VALUES " + IntStream.rangeClosed(21, 25)
                .mapToObj(i -> format("(200, %d, DATE '2020-07-%d')", i, i))
                .collect(joining(", ")), 5);

        assertUpdate("INSERT INTO " + tableName + " VALUES " + IntStream.rangeClosed(26, 30)
                .mapToObj(i -> format("(NULL, %d, DATE '2020-06-%d')", i, i))
                .collect(joining(", ")), 5);

        result = computeActual("SHOW STATS FOR " + tableName);

        expectedStatistics =
                resultBuilder(getSession(), VARCHAR, DOUBLE, DOUBLE, DOUBLE, DOUBLE, VARCHAR, VARCHAR)
                        .row("col1", null, null, 5.0 / 13.0, null, "-10.0", "200.0")
                        .row("col2", null, null, 0.0, null, "-1", "30")
                        .row("col3", null, null, 0.0, null, "2019-06-28", "2020-07-25")
                        .row(null, null, null, null, 13.0, null, null)
                        .build();
        assertEquals(result, expectedStatistics);

        dropTable(tableName);
    }

    @Test
    public void testPartitionedTableStatistics()
    {
        assertUpdate("CREATE TABLE iceberg.tpch.test_partitioned_table_statistics (col1 REAL, col2 BIGINT) WITH (partitioning = ARRAY['col2'])");

        assertUpdate("INSERT INTO test_partitioned_table_statistics VALUES (-10, -1)", 1);
        assertUpdate("INSERT INTO test_partitioned_table_statistics VALUES (100, 10)", 1);

        MaterializedResult result = computeActual("SHOW STATS FOR iceberg.tpch.test_partitioned_table_statistics");
        assertEquals(result.getRowCount(), 3);

        MaterializedRow row0 = result.getMaterializedRows().get(0);
        assertEquals(row0.getField(0), "col1");
        assertEquals(row0.getField(3), 0.0);
        assertEquals(row0.getField(5), "-10.0");
        assertEquals(row0.getField(6), "100.0");

        MaterializedRow row1 = result.getMaterializedRows().get(1);
        assertEquals(row1.getField(0), "col2");
        assertEquals(row1.getField(3), 0.0);
        assertEquals(row1.getField(5), "-1");
        assertEquals(row1.getField(6), "10");

        MaterializedRow row2 = result.getMaterializedRows().get(2);
        assertEquals(row2.getField(4), 2.0);

        assertUpdate("INSERT INTO test_partitioned_table_statistics VALUES " + IntStream.rangeClosed(1, 5)
                .mapToObj(i -> format("(%d, 10)", i + 100))
                .collect(joining(", ")), 5);

        assertUpdate("INSERT INTO test_partitioned_table_statistics VALUES " + IntStream.rangeClosed(6, 10)
                .mapToObj(i -> "(NULL, 10)")
                .collect(joining(", ")), 5);

        result = computeActual("SHOW STATS FOR iceberg.tpch.test_partitioned_table_statistics");
        assertEquals(result.getRowCount(), 3);
        row0 = result.getMaterializedRows().get(0);
        assertEquals(row0.getField(0), "col1");
        assertEquals(row0.getField(3), 5.0 / 12.0);
        assertEquals(row0.getField(5), "-10.0");
        assertEquals(row0.getField(6), "105.0");

        row1 = result.getMaterializedRows().get(1);
        assertEquals(row1.getField(0), "col2");
        assertEquals(row1.getField(3), 0.0);
        assertEquals(row1.getField(5), "-1");
        assertEquals(row1.getField(6), "10");

        row2 = result.getMaterializedRows().get(2);
        assertEquals(row2.getField(4), 12.0);

        assertUpdate("INSERT INTO test_partitioned_table_statistics VALUES " + IntStream.rangeClosed(6, 10)
                .mapToObj(i -> "(100, NULL)")
                .collect(joining(", ")), 5);

        result = computeActual("SHOW STATS FOR iceberg.tpch.test_partitioned_table_statistics");
        row0 = result.getMaterializedRows().get(0);
        assertEquals(row0.getField(0), "col1");
        assertEquals(row0.getField(3), 5.0 / 17.0);
        assertEquals(row0.getField(5), "-10.0");
        assertEquals(row0.getField(6), "105.0");

        row1 = result.getMaterializedRows().get(1);
        assertEquals(row1.getField(0), "col2");
        assertEquals(row1.getField(3), 5.0 / 17.0);
        assertEquals(row1.getField(5), "-1");
        assertEquals(row1.getField(6), "10");

        row2 = result.getMaterializedRows().get(2);
        assertEquals(row2.getField(4), 17.0);

        dropTable("iceberg.tpch.test_partitioned_table_statistics");
    }

    @Test
    public void testPredicatePushdown()
    {
        QualifiedObjectName tableName = new QualifiedObjectName("iceberg", "tpch", "test_predicate");
        assertUpdate(format("CREATE TABLE %s (col1 BIGINT, col2 BIGINT, col3 BIGINT) WITH (partitioning = ARRAY['col2', 'col3'])", tableName));
        assertUpdate(format("INSERT INTO %s VALUES (1, 10, 100)", tableName), 1L);
        assertUpdate(format("INSERT INTO %s VALUES (2, 20, 200)", tableName), 1L);

        assertQuery(format("SELECT * FROM %s WHERE col1 = 1", tableName), "VALUES (1, 10, 100)");
        assertFilterPushdown(
                tableName,
                ImmutableMap.of("col1", singleValue(BIGINT, 1L)),
                ImmutableMap.of(),
                ImmutableMap.of("col1", singleValue(BIGINT, 1L)));

        assertQuery(format("SELECT * FROM %s WHERE col2 = 10", tableName), "VALUES (1, 10, 100)");
        assertFilterPushdown(
                tableName,
                ImmutableMap.of("col2", singleValue(BIGINT, 10L)),
                ImmutableMap.of("col2", singleValue(BIGINT, 10L)),
                ImmutableMap.of());

        assertQuery(format("SELECT * FROM %s WHERE col1 = 1 AND col2 = 10", tableName), "VALUES (1, 10, 100)");
        assertFilterPushdown(
                tableName,
                ImmutableMap.of("col1", singleValue(BIGINT, 1L), "col2", singleValue(BIGINT, 10L)),
                ImmutableMap.of("col2", singleValue(BIGINT, 10L)),
                ImmutableMap.of("col1", singleValue(BIGINT, 1L)));

        // Assert pushdown for an IN predicate with value count above the default compaction threshold
        List<Long> values = LongStream.range(1L, 1010L).boxed()
                .filter(index -> index != 20L)
                .collect(toImmutableList());
        assertThat(values).hasSizeGreaterThan(ICEBERG_DOMAIN_COMPACTION_THRESHOLD);
        String valuesString = join(",", values.stream().map(Object::toString).collect(toImmutableList()));
        String inPredicate = "%s IN (" + valuesString + ")";
        assertQuery(
                format("SELECT * FROM %s WHERE %s AND %s", tableName, format(inPredicate, "col1"), format(inPredicate, "col2")),
                "VALUES (1, 10, 100)");

        assertFilterPushdown(
                tableName,
                ImmutableMap.of("col1", multipleValues(BIGINT, values), "col2", multipleValues(BIGINT, values)),
                ImmutableMap.of("col2", multipleValues(BIGINT, values)),
                // Unenforced predicate is simplified during split generation, but not reflected here
                ImmutableMap.of("col1", multipleValues(BIGINT, values)));

        dropTable(tableName.getObjectName());
    }

    @Test
    public void testPredicatesWithStructuralTypes()
    {
        String tableName = "test_predicate_with_structural_types";
        assertUpdate("CREATE TABLE " + tableName + " (id INT, array_t ARRAY(BIGINT), map_t MAP(BIGINT, BIGINT), struct_t ROW(f1 BIGINT, f2 BIGINT))");
        assertUpdate("INSERT INTO " + tableName + " VALUES " +
                        "(1, ARRAY[1, 2, 3], MAP(ARRAY[1,3], ARRAY[2,4]), ROW(1, 2)), " +
                        "(11, ARRAY[11, 12, 13], MAP(ARRAY[11, 13], ARRAY[12, 14]), ROW(11, 12)), " +
                        "(11, ARRAY[111, 112, 113], MAP(ARRAY[111, 13], ARRAY[112, 114]), ROW(111, 112)), " +
                        "(21, ARRAY[21, 22, 23], MAP(ARRAY[21, 23], ARRAY[22, 24]), ROW(21, 22))",
                4);

        assertQuery("SELECT id FROM " + tableName + " WHERE array_t = ARRAY[1, 2, 3]", "VALUES 1");
        assertQuery("SELECT id FROM " + tableName + " WHERE map_t = MAP(ARRAY[11, 13], ARRAY[12, 14])", "VALUES 11");
        assertQuery("SELECT id FROM " + tableName + " WHERE struct_t = ROW(21, 22)", "VALUES 21");
        assertQuery("SELECT struct_t.f1  FROM " + tableName + " WHERE id = 11 AND map_t = MAP(ARRAY[11, 13], ARRAY[12, 14])", "VALUES 11");

        dropTable(tableName);
    }

    @Test(dataProviderClass = DataProviders.class, dataProvider = "trueFalse")
    public void testPartitionsTableWithColumnNameConflict(boolean partitioned)
    {
        assertUpdate("DROP TABLE IF EXISTS test_partitions_with_conflict");
        assertUpdate("CREATE TABLE test_partitions_with_conflict (" +
                " p integer, " +
                " row_count integer, " +
                " record_count integer, " +
                " file_count integer, " +
                " total_size integer " +
                ") " +
                (partitioned ? "WITH(partitioning = ARRAY['p'])" : ""));

        assertUpdate("INSERT INTO test_partitions_with_conflict VALUES (11, 12, 13, 14, 15)", 1);

        // sanity check
        assertThat(query("SELECT * FROM test_partitions_with_conflict"))
                .matches("VALUES (11, 12, 13, 14, 15)");

        // test $partitions
        assertThat(query("SELECT * FROM \"test_partitions_with_conflict$partitions\""))
                .matches("SELECT " +
                        (partitioned ? "CAST(ROW(11) AS row(p integer)), " : "") +
                        "BIGINT '1', " +
                        "BIGINT '1', " +
                        // total_size is not exactly deterministic, so grab whatever value there is
                        "(SELECT total_size FROM \"test_partitions_with_conflict$partitions\"), " +
                        "CAST(" +
                        "  ROW (" +
                        (partitioned ? "" : "  ROW(11, 11, 0, NULL), ") +
                        "    ROW(12, 12, 0, NULL), " +
                        "    ROW(13, 13, 0, NULL), " +
                        "    ROW(14, 14, 0, NULL), " +
                        "    ROW(15, 15, 0, NULL) " +
                        "  ) " +
                        "  AS row(" +
                        (partitioned ? "" : "    p row(min integer, max integer, null_count bigint, nan_count bigint), ") +
                        "    row_count row(min integer, max integer, null_count bigint, nan_count bigint), " +
                        "    record_count row(min integer, max integer, null_count bigint, nan_count bigint), " +
                        "    file_count row(min integer, max integer, null_count bigint, nan_count bigint), " +
                        "    total_size row(min integer, max integer, null_count bigint, nan_count bigint) " +
                        "  )" +
                        ")");

        assertUpdate("DROP TABLE test_partitions_with_conflict");
    }

    private void assertFilterPushdown(
            QualifiedObjectName tableName,
            Map<String, Domain> filter,
            Map<String, Domain> expectedEnforcedPredicate,
            Map<String, Domain> expectedUnenforcedPredicate)
    {
        Metadata metadata = getQueryRunner().getMetadata();

        newTransaction().execute(getSession(), session -> {
            TableHandle table = metadata.getTableHandle(session, tableName)
                    .orElseThrow(() -> new TableNotFoundException(tableName.asSchemaTableName()));

            Map<String, ColumnHandle> columns = metadata.getColumnHandles(session, table);
            TupleDomain<ColumnHandle> domains = TupleDomain.withColumnDomains(
                    filter.entrySet().stream()
                            .collect(toImmutableMap(entry -> columns.get(entry.getKey()), Map.Entry::getValue)));

            Optional<ConstraintApplicationResult<TableHandle>> result = metadata.applyFilter(session, table, new Constraint(domains));

            assertTrue(result.isEmpty() == (expectedUnenforcedPredicate == null && expectedEnforcedPredicate == null));

            if (result.isPresent()) {
                IcebergTableHandle newTable = (IcebergTableHandle) result.get().getHandle().getConnectorHandle();

                assertEquals(
                        newTable.getEnforcedPredicate(),
                        TupleDomain.withColumnDomains(expectedEnforcedPredicate.entrySet().stream()
                                .collect(toImmutableMap(entry -> columns.get(entry.getKey()), Map.Entry::getValue))));

                assertEquals(
                        newTable.getUnenforcedPredicate(),
                        TupleDomain.withColumnDomains(expectedUnenforcedPredicate.entrySet().stream()
                                .collect(toImmutableMap(entry -> columns.get(entry.getKey()), Map.Entry::getValue))));
            }
        });
    }

    @Test
    public void testCreateNestedPartitionedTable()
    {
        assertUpdate("CREATE TABLE test_nested_table_1 (" +
                " bool BOOLEAN" +
                ", int INTEGER" +
                ", arr ARRAY(VARCHAR)" +
                ", big BIGINT" +
                ", rl REAL" +
                ", dbl DOUBLE" +
                ", mp MAP(INTEGER, VARCHAR)" +
                ", dec DECIMAL(5,2)" +
                ", vc VARCHAR" +
                ", vb VARBINARY" +
                ", ts TIMESTAMP(6)" +
                ", tstz TIMESTAMP(6) WITH TIME ZONE" +
                ", str ROW(id INTEGER , vc VARCHAR)" +
                ", dt DATE)" +
                " WITH (partitioning = ARRAY['int'])");

        assertUpdate(
                "INSERT INTO test_nested_table_1 " +
                        " select true, 1, array['uno', 'dos', 'tres'], BIGINT '1', REAL '1.0', DOUBLE '1.0', map(array[1,2,3,4], array['ek','don','teen','char'])," +
                        " CAST(1.0 as DECIMAL(5,2))," +
                        " 'one', VARBINARY 'binary0/1values',\n" +
                        " TIMESTAMP '2021-07-24 02:43:57.348000'," +
                        " TIMESTAMP '2021-07-24 02:43:57.348000 UTC'," +
                        " (CAST(ROW(null, 'this is a random value') AS ROW(int, varchar))), " +
                        " DATE '2021-07-24'",
                1);
        assertEquals(computeActual("SELECT * from test_nested_table_1").getRowCount(), 1);

        assertThat(query("SHOW STATS FOR test_nested_table_1"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('bool', NULL, NULL, 0e0, NULL, 'true', 'true'), " +
                        "  ('int', NULL, NULL, 0e0, NULL, '1', '1'), " +
                        "  ('arr', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('big', NULL, NULL, 0e0, NULL, '1', '1'), " +
                        "  ('rl', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('dbl', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('mp', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('dec', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('vc', " + (format == PARQUET ? "43e0" : "NULL") + ", NULL, 0e0, NULL, NULL, NULL), " +
                        "  ('vb', " + (format == PARQUET ? "55e0" : "NULL") + ", NULL, 0e0, NULL, NULL, NULL), " +
                        "  ('ts', NULL, NULL, 0e0, NULL, '2021-07-24 02:43:57.348000', " + (format == ORC ? "'2021-07-24 02:43:57.348999'" : "'2021-07-24 02:43:57.348000'") + "), " +
                        "  ('tstz', NULL, NULL, 0e0, NULL, '2021-07-24 02:43:57.348 UTC', '2021-07-24 02:43:57.348 UTC'), " +
                        "  ('str', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('dt', NULL, NULL, 0e0, NULL, '2021-07-24', '2021-07-24'), " +
                        "  (NULL, NULL, NULL, NULL, 1e0, NULL, NULL)");

        dropTable("test_nested_table_1");

        assertUpdate("" +
                "CREATE TABLE test_nested_table_2 (" +
                " int INTEGER" +
                ", arr ARRAY(ROW(id INTEGER, vc VARCHAR))" +
                ", big BIGINT" +
                ", rl REAL" +
                ", dbl DOUBLE" +
                ", mp MAP(INTEGER, ARRAY(VARCHAR))" +
                ", dec DECIMAL(5,2)" +
                ", str ROW(id INTEGER, vc VARCHAR, arr ARRAY(INTEGER))" +
                ", vc VARCHAR)" +
                " WITH (partitioning = ARRAY['int'])");

        assertUpdate(
                "INSERT INTO test_nested_table_2 " +
                        " select 1, array[cast(row(1, null) as row(int, varchar)), cast(row(2, 'dos') as row(int, varchar))], BIGINT '1', REAL '1.0', DOUBLE '1.0', " +
                        "map(array[1,2], array[array['ek', 'one'], array['don', 'do', 'two']]), CAST(1.0 as DECIMAL(5,2)), " +
                        "CAST(ROW(1, 'this is a random value', null) AS ROW(int, varchar, array(int))), 'one'",
                1);
        assertEquals(computeActual("SELECT * from test_nested_table_2").getRowCount(), 1);

        assertThat(query("SHOW STATS FOR test_nested_table_2"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('int', NULL, NULL, 0e0, NULL, '1', '1'), " +
                        "  ('arr', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('big', NULL, NULL, 0e0, NULL, '1', '1'), " +
                        "  ('rl', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('dbl', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('mp', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('dec', NULL, NULL, 0e0, NULL, '1.0', '1.0'), " +
                        "  ('vc', " + (format == PARQUET ? "43e0" : "NULL") + ", NULL, 0e0, NULL, NULL, NULL), " +
                        "  ('str', NULL, NULL, " + (format == ORC ? "0e0" : "NULL") + ", NULL, NULL, NULL), " +
                        "  (NULL, NULL, NULL, NULL, 1e0, NULL, NULL)");

        assertUpdate("CREATE TABLE test_nested_table_3 WITH (partitioning = ARRAY['int']) AS SELECT * FROM test_nested_table_2", 1);

        assertEquals(computeActual("SELECT * FROM test_nested_table_3").getRowCount(), 1);

        assertThat(query("SHOW STATS FOR test_nested_table_3"))
                .matches("SHOW STATS FOR test_nested_table_2");

        dropTable("test_nested_table_2");
        dropTable("test_nested_table_3");
    }

    @Test
    public void testSerializableReadIsolation()
    {
        assertUpdate("CREATE TABLE test_read_isolation (x int)");
        assertUpdate("INSERT INTO test_read_isolation VALUES 123, 456", 2);

        withTransaction(session -> {
            assertQuery(session, "SELECT * FROM test_read_isolation", "VALUES 123, 456");

            assertUpdate("INSERT INTO test_read_isolation VALUES 789", 1);
            assertQuery("SELECT * FROM test_read_isolation", "VALUES 123, 456, 789");

            assertQuery(session, "SELECT * FROM test_read_isolation", "VALUES 123, 456");
        });

        assertQuery("SELECT * FROM test_read_isolation", "VALUES 123, 456, 789");

        dropTable("test_read_isolation");
    }

    private void withTransaction(Consumer<Session> consumer)
    {
        transaction(getQueryRunner().getTransactionManager(), getQueryRunner().getAccessControl())
                .readCommitted()
                .execute(getSession(), consumer);
    }

    private void dropTable(String table)
    {
        Session session = getSession();
        assertUpdate(session, "DROP TABLE " + table);
        assertFalse(getQueryRunner().tableExists(session, table));
    }

    @Test
    public void testOptimizedMetadataQueries()
    {
        Session session = Session.builder(getSession())
                .setSystemProperty("optimize_metadata_queries", "true")
                .build();

        assertUpdate("CREATE TABLE test_metadata_optimization (a BIGINT, b BIGINT, c BIGINT) WITH (PARTITIONING = ARRAY['b', 'c'])");

        assertUpdate("INSERT INTO test_metadata_optimization VALUES (5, 6, 7), (8, 9, 10)", 2);

        assertQuery(session, "SELECT DISTINCT b FROM test_metadata_optimization", "VALUES (6), (9)");
        assertQuery(session, "SELECT DISTINCT b, c FROM test_metadata_optimization", "VALUES (6, 7), (9, 10)");
        assertQuery(session, "SELECT DISTINCT b FROM test_metadata_optimization WHERE b < 7", "VALUES (6)");
        assertQuery(session, "SELECT DISTINCT b FROM test_metadata_optimization WHERE c > 8", "VALUES (9)");

        // Assert behavior after metadata delete
        assertUpdate("DELETE FROM test_metadata_optimization WHERE b = 6", 1);
        assertQuery(session, "SELECT DISTINCT b FROM test_metadata_optimization", "VALUES (9)");

        // TODO: assert behavior after deleting the last row of a partition, once row-level deletes are supported.
        // i.e. a query like 'DELETE FROM test_metadata_optimization WHERE b = 6 AND a = 5'

        dropTable("test_metadata_optimization");
    }

    @Test
    public void testFileSizeInManifest()
            throws Exception
    {
        assertUpdate("CREATE TABLE test_file_size_in_manifest (" +
                "a_bigint bigint, " +
                "a_varchar varchar, " +
                "a_long_decimal decimal(38,20), " +
                "a_map map(varchar, integer))");

        assertUpdate(
                "INSERT INTO test_file_size_in_manifest VALUES " +
                        "(NULL, NULL, NULL, NULL), " +
                        "(42, 'some varchar value', DECIMAL '123456789123456789.123456789123456789', map(ARRAY['abc', 'def'], ARRAY[113, -237843832]))",
                2);

        MaterializedResult files = computeActual("SELECT file_path, record_count, file_size_in_bytes FROM \"test_file_size_in_manifest$files\"");
        long totalRecordCount = 0;
        for (MaterializedRow row : files.getMaterializedRows()) {
            String path = (String) row.getField(0);
            Long recordCount = (Long) row.getField(1);
            Long fileSizeInBytes = (Long) row.getField(2);

            totalRecordCount += recordCount;
            assertThat(fileSizeInBytes).isEqualTo(Files.size(Paths.get(path)));
        }
        // Verify sum(record_count) to make sure we have all the files.
        assertThat(totalRecordCount).isEqualTo(2);
    }

    @Test
    public void testIncorrectIcebergFileSizes()
            throws Exception
    {
        // Create a table with a single insert
        assertUpdate("CREATE TABLE test_iceberg_file_size (x BIGINT)");
        assertUpdate("INSERT INTO test_iceberg_file_size VALUES (123), (456), (758)", 3);

        // Get manifest file
        MaterializedResult result = computeActual("SELECT path FROM \"test_iceberg_file_size$manifests\"");
        assertEquals(result.getRowCount(), 1);
        String manifestFile = (String) result.getOnlyValue();

        // Read manifest file
        Schema schema;
        GenericData.Record entry = null;
        try (DataFileReader<GenericData.Record> dataFileReader = new DataFileReader<>(new File(manifestFile), new GenericDatumReader<>())) {
            schema = dataFileReader.getSchema();
            int recordCount = 0;
            while (dataFileReader.hasNext()) {
                entry = dataFileReader.next();
                recordCount++;
            }
            assertEquals(recordCount, 1);
        }

        // Alter data file entry to store incorrect file size
        GenericData.Record dataFile = (GenericData.Record) entry.get("data_file");
        long alteredValue = 50L;
        assertNotEquals((long) dataFile.get("file_size_in_bytes"), alteredValue);
        dataFile.put("file_size_in_bytes", alteredValue);

        // Replace the file through HDFS client. This is required for correct checksums.
        HdfsEnvironment.HdfsContext context = new HdfsContext(getSession().toConnectorSession());
        org.apache.hadoop.fs.Path manifestFilePath = new org.apache.hadoop.fs.Path(manifestFile);
        FileSystem fs = HDFS_ENVIRONMENT.getFileSystem(context, manifestFilePath);

        // Write altered metadata
        try (OutputStream out = fs.create(manifestFilePath);
                DataFileWriter<GenericData.Record> dataFileWriter = new DataFileWriter<>(new GenericDatumWriter<>(schema))) {
            dataFileWriter.create(schema, out);
            dataFileWriter.append(entry);
        }

        // Ignoring Iceberg provided file size makes the query succeed
        Session session = Session.builder(getSession())
                .setCatalogSessionProperty("iceberg", "use_file_size_from_metadata", "false")
                .build();
        assertQuery(session, "SELECT * FROM test_iceberg_file_size", "VALUES (123), (456), (758)");

        // Using Iceberg provided file size fails the query
        assertQueryFails("SELECT * FROM test_iceberg_file_size",
                format == ORC
                        ? format(".*Error opening Iceberg split.*\\QIncorrect file size (%s) for file (end of stream not reached)\\E.*", alteredValue)
                        : format("Error reading tail from .* with length %d", alteredValue));

        dropTable("test_iceberg_file_size");
    }

    @Test
    public void testSplitPruningForFilterOnPartitionColumn()
    {
        String tableName = "nation_partitioned_pruning";

        assertUpdate("DROP TABLE IF EXISTS " + tableName);

        // disable writes redistribution to have predictable number of files written per partition (one).
        Session noRedistributeWrites = Session.builder(getSession())
                .setSystemProperty("redistribute_writes", "false")
                .build();

        assertUpdate(noRedistributeWrites, "CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM nation", 25);

        // sanity check that table contains exactly 5 files
        assertThat(query("SELECT count(*) FROM \"" + tableName + "$files\"")).matches("VALUES CAST(5 AS BIGINT)");

        verifySplitCount("SELECT * FROM " + tableName, 5);
        verifySplitCount("SELECT * FROM " + tableName + " WHERE regionkey = 3", 1);
        verifySplitCount("SELECT * FROM " + tableName + " WHERE regionkey < 2", 2);
        verifySplitCount("SELECT * FROM " + tableName + " WHERE regionkey < 0", 0);
        verifySplitCount("SELECT * FROM " + tableName + " WHERE regionkey > 1 AND regionkey < 4", 2);
        verifySplitCount("SELECT * FROM " + tableName + " WHERE regionkey % 5 = 3", 1);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testAllAvailableTypes()
    {
        assertUpdate("CREATE TABLE test_all_types (" +
                "  a_boolean boolean, " +
                "  an_integer integer, " +
                "  a_bigint bigint, " +
                "  a_real real, " +
                "  a_double double, " +
                "  a_short_decimal decimal(5,2), " +
                "  a_long_decimal decimal(38,20), " +
                "  a_varchar varchar, " +
                "  a_varbinary varbinary, " +
                "  a_date date, " +
                "  a_time time(6), " +
                "  a_timestamp timestamp(6), " +
                "  a_timestamptz timestamp(6) with time zone, " +
                "  a_uuid uuid, " +
                "  a_row row(id integer , vc varchar), " +
                "  an_array array(varchar), " +
                "  a_map map(integer, varchar) " +
                ")");

        String values = "VALUES (" +
                "true, " +
                "1, " +
                "BIGINT '1', " +
                "REAL '1.0', " +
                "DOUBLE '1.0', " +
                "CAST(1.0 AS decimal(5,2)), " +
                "CAST(11.0 AS decimal(38,20)), " +
                "VARCHAR 'onefsadfdsf', " +
                "X'000102f0feff', " +
                "DATE '2021-07-24'," +
                "TIME '02:43:57.987654', " +
                "TIMESTAMP '2021-07-24 03:43:57.987654'," +
                "TIMESTAMP '2021-07-24 04:43:57.987654 UTC', " +
                "UUID '20050910-1330-11e9-ffff-2a86e4085a59', " +
                "CAST(ROW(42, 'this is a random value') AS ROW(id int, vc varchar)), " +
                "ARRAY[VARCHAR 'uno', 'dos', 'tres'], " +
                "map(ARRAY[1,2], ARRAY['ek', VARCHAR 'one'])) ";

        String nullValues = nCopies(17, "NULL").stream()
                .collect(joining(", ", "VALUES (", ")"));

        assertUpdate("INSERT INTO test_all_types " + values, 1);
        assertUpdate("INSERT INTO test_all_types " + nullValues, 1);

        // SELECT
        assertThat(query("SELECT * FROM test_all_types"))
                .matches(values + " UNION ALL " + nullValues);

        // SELECT with predicates
        assertThat(query("SELECT * FROM test_all_types WHERE " +
                "    a_boolean = true " +
                "AND an_integer = 1 " +
                "AND a_bigint = BIGINT '1' " +
                "AND a_real = REAL '1.0' " +
                "AND a_double = DOUBLE '1.0' " +
                "AND a_short_decimal = CAST(1.0 AS decimal(5,2)) " +
                "AND a_long_decimal = CAST(11.0 AS decimal(38,20)) " +
                "AND a_varchar = VARCHAR 'onefsadfdsf' " +
                "AND a_varbinary = X'000102f0feff' " +
                "AND a_date = DATE '2021-07-24' " +
                "AND a_time = TIME '02:43:57.987654' " +
                "AND a_timestamp = TIMESTAMP '2021-07-24 03:43:57.987654' " +
                "AND a_timestamptz = TIMESTAMP '2021-07-24 04:43:57.987654 UTC' " +
                "AND a_uuid = UUID '20050910-1330-11e9-ffff-2a86e4085a59' " +
                "AND a_row = CAST(ROW(42, 'this is a random value') AS ROW(id int, vc varchar)) " +
                "AND an_array = ARRAY[VARCHAR 'uno', 'dos', 'tres'] " +
                "AND a_map = map(ARRAY[1,2], ARRAY['ek', VARCHAR 'one']) " +
                ""))
                .matches(values);

        assertThat(query("SELECT * FROM test_all_types WHERE " +
                "    a_boolean IS NULL " +
                "AND an_integer IS NULL " +
                "AND a_bigint IS NULL " +
                "AND a_real IS NULL " +
                "AND a_double IS NULL " +
                "AND a_short_decimal IS NULL " +
                "AND a_long_decimal IS NULL " +
                "AND a_varchar IS NULL " +
                "AND a_varbinary IS NULL " +
                "AND a_date IS NULL " +
                "AND a_time IS NULL " +
                "AND a_timestamp IS NULL " +
                "AND a_timestamptz IS NULL " +
                "AND a_uuid IS NULL " +
                "AND a_row IS NULL " +
                "AND an_array IS NULL " +
                "AND a_map IS NULL " +
                ""))
                .skippingTypesCheck()
                .matches(nullValues);

        // SHOW STATS
        assertThat(query("SHOW STATS FOR test_all_types"))
                .skippingTypesCheck()
                .matches("VALUES " +
                        "  ('a_boolean', NULL, NULL, 0.5e0, NULL, 'true', 'true'), " +
                        "  ('an_integer', NULL, NULL, 0.5e0, NULL, '1', '1'), " +
                        "  ('a_bigint', NULL, NULL, 0.5e0, NULL, '1', '1'), " +
                        "  ('a_real', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                        "  ('a_double', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                        "  ('a_short_decimal', NULL, NULL, 0.5e0, NULL, '1.0', '1.0'), " +
                        "  ('a_long_decimal', NULL, NULL, 0.5e0, NULL, '11.0', '11.0'), " +
                        "  ('a_varchar', " + (format == PARQUET ? "87e0" : "NULL") + ", NULL, 0.5e0, NULL, NULL, NULL), " +
                        "  ('a_varbinary', " + (format == PARQUET ? "82e0" : "NULL") + ", NULL, 0.5e0, NULL, NULL, NULL), " +
                        "  ('a_date', NULL, NULL, 0.5e0, NULL, '2021-07-24', '2021-07-24'), " +
                        "  ('a_time', NULL, NULL, 0.5e0, NULL, NULL, NULL), " +
                        "  ('a_timestamp', NULL, NULL, 0.5e0, NULL, " + (format == ORC ? "'2021-07-24 03:43:57.987000', '2021-07-24 03:43:57.987999'" : "'2021-07-24 03:43:57.987654', '2021-07-24 03:43:57.987654'") + "), " +
                        "  ('a_timestamptz', NULL, NULL, 0.5e0, NULL, '2021-07-24 04:43:57.987 UTC', '2021-07-24 04:43:57.987 UTC'), " +
                        "  ('a_uuid', NULL, NULL, 0.5e0, NULL, NULL, NULL), " +
                        "  ('a_row', NULL, NULL, " + (format == ORC ? "0.5" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('an_array', NULL, NULL, " + (format == ORC ? "0.5" : "NULL") + ", NULL, NULL, NULL), " +
                        "  ('a_map', NULL, NULL, " + (format == ORC ? "0.5" : "NULL") + ", NULL, NULL, NULL), " +
                        "  (NULL, NULL, NULL, NULL, 2e0, NULL, NULL)");

        // $partitions
        String schema = getSession().getSchema().orElseThrow();
        assertThat(query("SELECT column_name FROM information_schema.columns WHERE table_schema = '" + schema + "' AND table_name = 'test_all_types$partitions' "))
                .skippingTypesCheck()
                .matches("VALUES 'record_count', 'file_count', 'total_size', 'data'");
        assertThat(query("SELECT " +
                "  record_count," +
                "  file_count, " +
                "  data.a_boolean, " +
                "  data.an_integer, " +
                "  data.a_bigint, " +
                "  data.a_real, " +
                "  data.a_double, " +
                "  data.a_short_decimal, " +
                "  data.a_long_decimal, " +
                "  data.a_varchar, " +
                "  data.a_varbinary, " +
                "  data.a_date, " +
                "  data.a_time, " +
                "  data.a_timestamp, " +
                "  data.a_timestamptz, " +
                "  data.a_uuid " +
                " FROM \"test_all_types$partitions\" "))
                .matches(
                        "VALUES (" +
                                "  BIGINT '2', " +
                                "  BIGINT '2', " +
                                "  CAST(ROW(true, true, 1, NULL) AS ROW(min boolean, max boolean, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(1, 1, 1, NULL) AS ROW(min integer, max integer, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(1, 1, 1, NULL) AS ROW(min bigint, max bigint, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(1, 1, 1, NULL) AS ROW(min real, max real, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(1, 1, 1, NULL) AS ROW(min double, max double, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(1, 1, 1, NULL) AS ROW(min decimal(5,2), max decimal(5,2), null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(11, 11, 1, NULL) AS ROW(min decimal(38,20), max decimal(38,20), null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW('onefsadfdsf', 'onefsadfdsf', 1, NULL) AS ROW(min varchar, max varchar, null_count bigint, nan_count bigint)), " +
                                (format == ORC ?
                                        "  CAST(ROW(NULL, NULL, 1, NULL) AS ROW(min varbinary, max varbinary, null_count bigint, nan_count bigint)), " :
                                        "  CAST(ROW(X'000102f0feff', X'000102f0feff', 1, NULL) AS ROW(min varbinary, max varbinary, null_count bigint, nan_count bigint)), ") +
                                "  CAST(ROW(DATE '2021-07-24', DATE '2021-07-24', 1, NULL) AS ROW(min date, max date, null_count bigint, nan_count bigint)), " +
                                "  CAST(ROW(TIME '02:43:57.987654', TIME '02:43:57.987654', 1, NULL) AS ROW(min time(6), max time(6), null_count bigint, nan_count bigint)), " +
                                (format == ORC ?
                                        "  CAST(ROW(TIMESTAMP '2021-07-24 03:43:57.987000', TIMESTAMP '2021-07-24 03:43:57.987999', 1, NULL) AS ROW(min timestamp(6), max timestamp(6), null_count bigint, nan_count bigint)), " :
                                        "  CAST(ROW(TIMESTAMP '2021-07-24 03:43:57.987654', TIMESTAMP '2021-07-24 03:43:57.987654', 1, NULL) AS ROW(min timestamp(6), max timestamp(6), null_count bigint, nan_count bigint)), ") +
                                (format == ORC ?
                                        "  CAST(ROW(TIMESTAMP '2021-07-24 04:43:57.987000 UTC', TIMESTAMP '2021-07-24 04:43:57.987999 UTC', 1, NULL) AS ROW(min timestamp(6) with time zone, max timestamp(6) with time zone, null_count bigint, nan_count bigint)), " :
                                        "  CAST(ROW(TIMESTAMP '2021-07-24 04:43:57.987654 UTC', TIMESTAMP '2021-07-24 04:43:57.987654 UTC', 1, NULL) AS ROW(min timestamp(6) with time zone, max timestamp(6) with time zone, null_count bigint, nan_count bigint)), ") +
                                (format == ORC ?
                                        "  CAST(ROW(NULL, NULL, 1, NULL) AS ROW(min uuid, max uuid, null_count bigint, nan_count bigint)) " :
                                        "  CAST(ROW(UUID '20050910-1330-11e9-ffff-2a86e4085a59', UUID '20050910-1330-11e9-ffff-2a86e4085a59', 1, NULL) AS ROW(min uuid, max uuid, null_count bigint, nan_count bigint)) "
                                ) +
                                ")");

        assertUpdate("DROP TABLE test_all_types");
    }

    @Test(timeOut = 25_000)
    public void testLocalDynamicFilteringWithSelectiveBuildSideJoin()
    {
        // We need to prepare tables for this test. The test is required to use tables that are backed by at lest two files
        Session session = Session.builder(getSession())
                .setSystemProperty(TASK_WRITER_COUNT, "2")
                .build();
        getQueryRunner().execute(session, format("CREATE TABLE IF NOT EXISTS %s AS SELECT * FROM %s", "linetime_multiple_file_backed", "tpch.tiny.lineitem")).getMaterializedRows();
        getQueryRunner().execute(session, format("CREATE TABLE IF NOT EXISTS %s AS SELECT * FROM %s", "orders_multiple_file_backed", "tpch.tiny.orders")).getMaterializedRows();

        long fullTableScan = (Long) computeActual("SELECT count(*) FROM linetime_multiple_file_backed").getOnlyValue();
        // Pick a value for totalprice where file level stats will not be able to filter out any data
        // This assumes the totalprice ranges in every file have some overlap, otherwise this test will fail.
        MaterializedRow range = getOnlyElement(computeActual("SELECT max(lower_bounds[4]), min(upper_bounds[4]) FROM \"orders_multiple_file_backed$files\"").getMaterializedRows());
        double totalPrice = (Double) computeActual(format(
                "SELECT totalprice FROM orders_multiple_file_backed WHERE totalprice > %s AND totalprice < %s LIMIT 1",
                range.getField(0),
                range.getField(1)))
                .getOnlyValue();

        session = Session.builder(getSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .setCatalogSessionProperty(ICEBERG_CATALOG, "dynamic_filtering_wait_timeout", "1h")
                .build();

        ResultWithQueryId<MaterializedResult> result = getDistributedQueryRunner().executeWithQueryId(
                session,
                "SELECT * FROM linetime_multiple_file_backed JOIN orders_multiple_file_backed ON linetime_multiple_file_backed.orderkey = orders_multiple_file_backed.orderkey AND orders_multiple_file_backed.totalprice = " + totalPrice);
        OperatorStats probeStats = searchScanFilterAndProjectOperatorStats(
                result.getQueryId(),
                new QualifiedObjectName(ICEBERG_CATALOG, "tpch", "linetime_multiple_file_backed"));

        // Assert some lineitem rows were filtered out on file level
        assertThat(probeStats.getInputPositions()).isLessThan(fullTableScan);
    }

    @Test(dataProvider = "repartitioningDataProvider")
    public void testRepartitionDataOnCtas(Session session, String partitioning, int expectedFiles)
    {
        testRepartitionData(session, "tpch.tiny.orders", true, partitioning, expectedFiles);
    }

    @Test(dataProvider = "repartitioningDataProvider")
    public void testRepartitionDataOnInsert(Session session, String partitioning, int expectedFiles)
    {
        testRepartitionData(session, "tpch.tiny.orders", false, partitioning, expectedFiles);
    }

    @DataProvider
    public Object[][] repartitioningDataProvider()
    {
        Session defaultSession = getSession();
        // For identity-only partitioning, Iceberg connector returns ConnectorTableLayout with partitionColumns set, but without partitioning.
        // This is treated by engine as "preferred", but not mandatory partitioning, and gets ignored if stats suggest number of partitions
        // written is low. Without partitioning, number of files created is nondeterministic, as a writer (worker node) may or may not receive data.
        Session obeyConnectorPartitioning = Session.builder(defaultSession)
                .setSystemProperty(PREFERRED_WRITE_PARTITIONING_MIN_NUMBER_OF_PARTITIONS, "1")
                .build();

        return new Object[][] {
                // identity partitioning column
                {obeyConnectorPartitioning, "'orderstatus'", 3},
                // bucketing
                {defaultSession, "'bucket(custkey, 13)'", 13},
                // varchar-based
                {defaultSession, "'truncate(comment, 1)'", 35},
                // complex; would exceed 100 open writers limit in IcebergPageSink without write repartitioning
                {defaultSession, "'bucket(custkey, 4)', 'truncate(comment, 1)'", 131},
                // same column multiple times
                {defaultSession, "'truncate(comment, 1)', 'orderstatus', 'bucket(comment, 2)'", 180},
        };
    }

    @Test
    public void testStatsBasedRepartitionDataOnCtas()
    {
        testStatsBasedRepartitionData(true);
    }

    @Test
    public void testStatsBasedRepartitionDataOnInsert()
    {
        testStatsBasedRepartitionData(false);
    }

    private void testStatsBasedRepartitionData(boolean ctas)
    {
        Session sessionRepartitionSmall = Session.builder(getSession())
                .setSystemProperty(PREFERRED_WRITE_PARTITIONING_MIN_NUMBER_OF_PARTITIONS, "2")
                .build();
        Session sessionRepartitionMany = Session.builder(getSession())
                .setSystemProperty(PREFERRED_WRITE_PARTITIONING_MIN_NUMBER_OF_PARTITIONS, "5")
                .setSystemProperty(SCALE_WRITERS, "false")
                .build();
        // Use DISTINCT to add data redistribution between source table and the writer. This makes it more likely that all writers get some data.
        String sourceRelation = "(SELECT DISTINCT orderkey, custkey, orderstatus FROM tpch.tiny.orders)";
        testRepartitionData(
                sessionRepartitionSmall,
                sourceRelation,
                ctas,
                "'orderstatus'",
                3);
        // Test uses relatively small table (60K rows). When engine doesn't redistribute data for writes,
        // occasionally a worker node doesn't get any data and fewer files get created.
        assertEventually(() -> {
            testRepartitionData(
                    sessionRepartitionMany,
                    sourceRelation,
                    ctas,
                    "'orderstatus'",
                    9);
        });
    }

    private void testRepartitionData(Session session, String sourceRelation, boolean ctas, String partitioning, int expectedFiles)
    {
        String tableName = "repartition" +
                "_" + sourceRelation.replaceAll("[^a-zA-Z0-9]", "") +
                (ctas ? "ctas" : "insert") +
                "_" + partitioning.replaceAll("[^a-zA-Z0-9]", "") +
                "_" + randomTableSuffix();

        long rowCount = (long) computeScalar(session, "SELECT count(*) FROM " + sourceRelation);

        if (ctas) {
            assertUpdate(
                    session,
                    "CREATE TABLE " + tableName + " WITH (partitioning = ARRAY[" + partitioning + "]) " +
                            "AS SELECT * FROM " + sourceRelation,
                    rowCount);
        }
        else {
            assertUpdate(
                    session,
                    "CREATE TABLE " + tableName + " WITH (partitioning = ARRAY[" + partitioning + "]) " +
                            "AS SELECT * FROM " + sourceRelation + " WITH NO DATA",
                    0);
            // Use source table big enough so that there will be multiple pages being written.
            assertUpdate(session, "INSERT INTO " + tableName + " SELECT * FROM " + sourceRelation, rowCount);
        }

        // verify written data
        assertThat(query(session, "TABLE " + tableName))
                .skippingTypesCheck()
                .matches("SELECT * FROM " + sourceRelation);

        // verify data files, i.e. repartitioning took place
        assertThat(query(session, "SELECT count(*) FROM \"" + tableName + "$files\""))
                .matches("VALUES BIGINT '" + expectedFiles + "'");

        assertUpdate(session, "DROP TABLE " + tableName);
    }

    @Test(dataProvider = "testDataMappingSmokeTestDataProvider")
    public void testSplitPruningForFilterOnNonPartitionColumn(DataMappingTestSetup testSetup)
    {
        if (testSetup.isUnsupportedType()) {
            return;
        }
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_split_pruning_non_partitioned", "(row_id int, col " + testSetup.getTrinoTypeName() + ")")) {
            String tableName = table.getName();
            String sampleValue = testSetup.getSampleValueLiteral();
            String highValue = testSetup.getHighValueLiteral();
            // Insert separately to ensure two files with one value each
            assertUpdate("INSERT INTO " + tableName + " VALUES (1, " + sampleValue + ")", 1);
            assertUpdate("INSERT INTO " + tableName + " VALUES (2, " + highValue + ")", 1);
            assertQuery("select count(*) from \"" + tableName + "$files\"", "VALUES 2");

            int expectedSplitCount = supportsIcebergFileStatistics(testSetup.getTrinoTypeName()) ? 1 : 2;
            verifySplitCount("SELECT row_id FROM " + tableName, 2);
            verifySplitCount("SELECT row_id FROM " + tableName + " WHERE col = " + sampleValue, expectedSplitCount);
            verifySplitCount("SELECT row_id FROM " + tableName + " WHERE col = " + highValue, expectedSplitCount);

            // ORC max timestamp statistics are truncated to millisecond precision and then appended with 999 microseconds.
            // Therefore, sampleValue and highValue are within the max timestamp & there will be 2 splits.
            verifySplitCount("SELECT row_id FROM " + tableName + " WHERE col > " + sampleValue,
                    (format == ORC && testSetup.getTrinoTypeName().contains("timestamp") ? 2 : expectedSplitCount));
            verifySplitCount("SELECT row_id FROM " + tableName + " WHERE col < " + highValue,
                    (format == ORC && testSetup.getTrinoTypeName().contains("timestamp") ? 2 : expectedSplitCount));
        }
    }

    @Test
    public void testGetIcebergTableProperties()
    {
        assertUpdate("CREATE TABLE test_iceberg_get_table_props (x BIGINT)");
        assertThat(query("SELECT * FROM \"test_iceberg_get_table_props$properties\""))
                .matches(format("VALUES (VARCHAR 'write.format.default', VARCHAR '%s')", format.name()));
        dropTable("test_iceberg_get_table_props");
    }

    protected abstract boolean supportsIcebergFileStatistics(String typeName);

    @Test(dataProvider = "testDataMappingSmokeTestDataProvider")
    public void testSplitPruningFromDataFileStatistics(DataMappingTestSetup testSetup)
    {
        if (testSetup.isUnsupportedType()) {
            return;
        }
        try (TestTable table = new TestTable(
                getQueryRunner()::execute,
                "test_split_pruning_data_file_statistics",
                // Random double is needed to make sure rows are different. Otherwise compression may deduplicate rows, resulting in only one row group
                "(col " + testSetup.getTrinoTypeName() + ", r double)")) {
            String tableName = table.getName();
            String values =
                    Stream.concat(
                                    nCopies(100, testSetup.getSampleValueLiteral()).stream(),
                                    nCopies(100, testSetup.getHighValueLiteral()).stream())
                            .map(value -> "(" + value + ", rand())")
                            .collect(Collectors.joining(", "));
            assertUpdate(withSmallRowGroups(getSession()), "INSERT INTO " + tableName + " VALUES " + values, 200);

            String query = "SELECT * FROM " + tableName + " WHERE col = " + testSetup.getSampleValueLiteral();
            verifyPredicatePushdownDataRead(query, supportsRowGroupStatistics(testSetup.getTrinoTypeName()));
        }
    }

    protected abstract Session withSmallRowGroups(Session session);

    protected abstract boolean supportsRowGroupStatistics(String typeName);

    private void verifySplitCount(String query, int expectedSplitCount)
    {
        ResultWithQueryId<MaterializedResult> selectAllPartitionsResult = getDistributedQueryRunner().executeWithQueryId(getSession(), query);
        assertEqualsIgnoreOrder(selectAllPartitionsResult.getResult().getMaterializedRows(), computeActual(withoutPredicatePushdown(getSession()), query).getMaterializedRows());
        verifySplitCount(selectAllPartitionsResult.getQueryId(), expectedSplitCount);
    }

    private void verifyPredicatePushdownDataRead(@Language("SQL") String query, boolean supportsPushdown)
    {
        ResultWithQueryId<MaterializedResult> resultWithPredicatePushdown = getDistributedQueryRunner().executeWithQueryId(getSession(), query);
        ResultWithQueryId<MaterializedResult> resultWithoutPredicatePushdown = getDistributedQueryRunner().executeWithQueryId(
                withoutPredicatePushdown(getSession()),
                query);

        DataSize withPushdownDataSize = getOperatorStats(resultWithPredicatePushdown.getQueryId()).getInputDataSize();
        DataSize withoutPushdownDataSize = getOperatorStats(resultWithoutPredicatePushdown.getQueryId()).getInputDataSize();
        if (supportsPushdown) {
            assertThat(withPushdownDataSize).isLessThan(withoutPushdownDataSize);
        }
        else {
            assertThat(withPushdownDataSize).isEqualTo(withoutPushdownDataSize);
        }
    }

    private Session withoutPredicatePushdown(Session session)
    {
        return Session.builder(session)
                .setSystemProperty("allow_pushdown_into_connectors", "false")
                .build();
    }

    private void verifySplitCount(QueryId queryId, long expectedSplitCount)
    {
        checkArgument(expectedSplitCount >= 0);
        OperatorStats operatorStats = getOperatorStats(queryId);
        if (expectedSplitCount > 0) {
            assertThat(operatorStats.getTotalDrivers()).isEqualTo(expectedSplitCount);
            assertThat(operatorStats.getPhysicalInputPositions()).isGreaterThan(0);
        }
        else {
            // expectedSplitCount == 0
            assertThat(operatorStats.getTotalDrivers()).isEqualTo(1);
            assertThat(operatorStats.getPhysicalInputPositions()).isEqualTo(0);
        }
    }

    private OperatorStats getOperatorStats(QueryId queryId)
    {
        try {
            return getDistributedQueryRunner().getCoordinator()
                    .getQueryManager()
                    .getFullQueryInfo(queryId)
                    .getQueryStats()
                    .getOperatorSummaries()
                    .stream()
                    .filter(summary -> summary.getOperatorType().startsWith("TableScan") || summary.getOperatorType().startsWith("Scan"))
                    .collect(onlyElement());
        }
        catch (NoSuchElementException e) {
            throw new RuntimeException("Couldn't find operator summary, probably due to query statistic collection error", e);
        }
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        throw new SkipException("Iceberg connector does not support column default values");
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("tinyint")
                || typeName.equals("smallint")
                || typeName.startsWith("char(")) {
            // These types are not supported by Iceberg
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }

        // According to Iceberg specification all time and timestamp values are stored with microsecond precision.
        if (typeName.equals("time") ||
                typeName.equals("timestamp") ||
                typeName.equals("timestamp(3) with time zone")) {
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }

        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected Optional<DataMappingTestSetup> filterCaseSensitiveDataMappingTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("char(1)")) {
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }
        return Optional.of(dataMappingTestSetup);
    }

    @Test
    public void testAmbiguousColumnsWithDots()
    {
        assertThatThrownBy(() -> assertUpdate("CREATE TABLE ambiguous (\"a.cow\" BIGINT, a ROW(cow BIGINT))"))
                .hasMessage("Invalid schema: multiple fields for name a.cow: 1 and 3");

        assertUpdate("CREATE TABLE ambiguous (\"a.cow\" BIGINT, b ROW(cow BIGINT))");
        assertThatThrownBy(() -> assertUpdate("ALTER TABLE ambiguous RENAME COLUMN b TO a"))
                .hasMessage("Invalid schema: multiple fields for name a.cow: 1 and 3");
        assertUpdate("DROP TABLE ambiguous");

        assertUpdate("CREATE TABLE ambiguous (a ROW(cow BIGINT))");
        assertThatThrownBy(() -> assertUpdate("ALTER TABLE ambiguous ADD COLUMN \"a.cow\" BIGINT"))
                .hasMessage("Cannot add column with ambiguous name: a.cow, use addColumn(parent, name, type)");
        assertUpdate("DROP TABLE ambiguous");
    }

    @Test
    public void testSchemaEvolutionWithDereferenceProjections()
    {
        // Fields are identified uniquely based on unique id's. If a column is dropped and recreated with the same name it should not return dropped data.
        assertUpdate("CREATE TABLE evolve_test (dummy BIGINT, a row(b BIGINT, c VARCHAR))");
        assertUpdate("INSERT INTO evolve_test VALUES (1, ROW(1, 'abc'))", 1);
        assertUpdate("ALTER TABLE evolve_test DROP COLUMN a");
        assertUpdate("ALTER TABLE evolve_test ADD COLUMN a ROW(b VARCHAR, c BIGINT)");
        assertQuery("SELECT a.b FROM evolve_test", "VALUES NULL");
        assertUpdate("DROP TABLE evolve_test");

        // Very changing subfield ordering does not revive dropped data
        assertUpdate("CREATE TABLE evolve_test (dummy BIGINT, a ROW(b BIGINT, c VARCHAR), d BIGINT) with (partitioning = ARRAY['d'])");
        assertUpdate("INSERT INTO evolve_test VALUES (1, ROW(2, 'abc'), 3)", 1);
        assertUpdate("ALTER TABLE evolve_test DROP COLUMN a");
        assertUpdate("ALTER TABLE evolve_test ADD COLUMN a ROW(c VARCHAR, b BIGINT)");
        assertUpdate("INSERT INTO evolve_test VALUES (4, 5, ROW('def', 6))", 1);
        assertQuery("SELECT a.b FROM evolve_test WHERE d = 3", "VALUES NULL");
        assertQuery("SELECT a.b FROM evolve_test WHERE d = 5", "VALUES 6");
        assertUpdate("DROP TABLE evolve_test");
    }

    @Test
    public void testHighlyNestedData()
    {
        assertUpdate("CREATE TABLE nested_data (id INT, row_t ROW(f1 INT, f2 INT, row_t ROW (f1 INT, f2 INT, row_t ROW(f1 INT, f2 INT))))");
        assertUpdate("INSERT INTO nested_data VALUES (1, ROW(2, 3, ROW(4, 5, ROW(6, 7)))), (11, ROW(12, 13, ROW(14, 15, ROW(16, 17))))", 2);
        assertUpdate("INSERT INTO nested_data VALUES (21, ROW(22, 23, ROW(24, 25, ROW(26, 27))))", 1);

        // Test select projected columns, with and without their parent column
        assertQuery("SELECT id, row_t.row_t.row_t.f2 FROM nested_data", "VALUES (1, 7), (11, 17), (21, 27)");
        assertQuery("SELECT id, row_t.row_t.row_t.f2, CAST(row_t AS JSON) FROM nested_data",
                "VALUES (1, 7, '{\"f1\":2,\"f2\":3,\"row_t\":{\"f1\":4,\"f2\":5,\"row_t\":{\"f1\":6,\"f2\":7}}}'), " +
                        "(11, 17, '{\"f1\":12,\"f2\":13,\"row_t\":{\"f1\":14,\"f2\":15,\"row_t\":{\"f1\":16,\"f2\":17}}}'), " +
                        "(21, 27, '{\"f1\":22,\"f2\":23,\"row_t\":{\"f1\":24,\"f2\":25,\"row_t\":{\"f1\":26,\"f2\":27}}}')");

        // Test predicates on immediate child column and deeper nested column
        assertQuery("SELECT id, CAST(row_t.row_t.row_t AS JSON) FROM nested_data WHERE row_t.row_t.row_t.f2 = 27", "VALUES (21, '{\"f1\":26,\"f2\":27}')");
        assertQuery("SELECT id, CAST(row_t.row_t.row_t AS JSON) FROM nested_data WHERE row_t.row_t.row_t.f2 > 20", "VALUES (21, '{\"f1\":26,\"f2\":27}')");
        assertQuery("SELECT id, CAST(row_t AS JSON) FROM nested_data WHERE row_t.row_t.row_t.f2 = 27",
                "VALUES (21, '{\"f1\":22,\"f2\":23,\"row_t\":{\"f1\":24,\"f2\":25,\"row_t\":{\"f1\":26,\"f2\":27}}}')");
        assertQuery("SELECT id, CAST(row_t AS JSON) FROM nested_data WHERE row_t.row_t.row_t.f2 > 20",
                "VALUES (21, '{\"f1\":22,\"f2\":23,\"row_t\":{\"f1\":24,\"f2\":25,\"row_t\":{\"f1\":26,\"f2\":27}}}')");

        // Test predicates on parent columns
        assertQuery("SELECT id, row_t.row_t.row_t.f1 FROM nested_data WHERE row_t.row_t.row_t = ROW(16, 17)", "VALUES (11, 16)");
        assertQuery("SELECT id, row_t.row_t.row_t.f1 FROM nested_data WHERE row_t = ROW(22, 23, ROW(24, 25, ROW(26, 27)))", "VALUES (21, 26)");

        assertUpdate("DROP TABLE IF EXISTS nested_data");
    }

    @Test
    public void testProjectionPushdownAfterRename()
    {
        assertUpdate("CREATE TABLE projection_pushdown_after_rename (id INT, a ROW(b INT, c ROW (d INT)))");
        assertUpdate("INSERT INTO projection_pushdown_after_rename VALUES (1, ROW(2, ROW(3))), (11, ROW(12, ROW(13)))", 2);
        assertUpdate("INSERT INTO projection_pushdown_after_rename VALUES (21, ROW(22, ROW(23)))", 1);

        String expected = "VALUES (11, JSON '{\"b\":12,\"c\":{\"d\":13}}', 13)";
        assertQuery("SELECT id, CAST(a AS JSON), a.c.d FROM projection_pushdown_after_rename WHERE a.b = 12", expected);
        assertUpdate("ALTER TABLE projection_pushdown_after_rename RENAME COLUMN a TO row_t");
        assertQuery("SELECT id, CAST(row_t AS JSON), row_t.c.d FROM projection_pushdown_after_rename WHERE row_t.b = 12", expected);

        assertUpdate("DROP TABLE IF EXISTS projection_pushdown_after_rename");
    }

    @Test
    public void testProjectionWithCaseSensitiveField()
    {
        assertUpdate("CREATE TABLE projection_with_case_sensitive_field (id INT, a ROW(\"UPPER_CASE\" INT, \"lower_case\" INT, \"MiXeD_cAsE\" INT))");
        assertUpdate("INSERT INTO projection_with_case_sensitive_field VALUES (1, ROW(2, 3, 4)), (5, ROW(6, 7, 8))", 2);

        String expected = "VALUES (2, 3, 4), (6, 7, 8)";
        assertQuery("SELECT a.UPPER_CASE, a.lower_case, a.MiXeD_cAsE FROM projection_with_case_sensitive_field", expected);
        assertQuery("SELECT a.upper_case, a.lower_case, a.mixed_case FROM projection_with_case_sensitive_field", expected);
        assertQuery("SELECT a.UPPER_CASE, a.LOWER_CASE, a.MIXED_CASE FROM projection_with_case_sensitive_field", expected);

        assertUpdate("DROP TABLE IF EXISTS projection_with_case_sensitive_field");
    }

    @Test
    public void testProjectionPushdownReadsLessData()
    {
        String largeVarchar = "ZZZ".repeat(1000);
        assertUpdate("CREATE TABLE projection_pushdown_reads_less_data (id INT, a ROW(b VARCHAR, c INT))");
        assertUpdate(
                format("INSERT INTO projection_pushdown_reads_less_data VALUES (1, ROW('%s', 3)), (11, ROW('%1$s', 13)), (21, ROW('%1$s', 23)), (31, ROW('%1$s', 33))", largeVarchar),
                4);

        String selectQuery = "SELECT a.c FROM projection_pushdown_reads_less_data";
        Set<Integer> expected = ImmutableSet.of(3, 13, 23, 33);
        Session sessionWithoutPushdown = Session.builder(getSession())
                .setCatalogSessionProperty(ICEBERG_CATALOG, "projection_pushdown_enabled", "false")
                .build();

        assertQueryStats(
                getSession(),
                selectQuery,
                statsWithPushdown -> {
                    DataSize processedDataSizeWithPushdown = statsWithPushdown.getProcessedInputDataSize();
                    assertQueryStats(
                            sessionWithoutPushdown,
                            selectQuery,
                            statsWithoutPushdown -> assertThat(statsWithoutPushdown.getProcessedInputDataSize()).isGreaterThan(processedDataSizeWithPushdown),
                            results -> assertEquals(results.getOnlyColumnAsSet(), expected));
                },
                results -> assertEquals(results.getOnlyColumnAsSet(), expected));

        assertUpdate("DROP TABLE IF EXISTS projection_pushdown_reads_less_data");
    }

    @Test
    public void testProjectionPushdownOnPartitionedTables()
    {
        assertUpdate("CREATE TABLE table_with_partition_at_beginning (id BIGINT, root ROW(f1 BIGINT, f2 BIGINT)) WITH (partitioning = ARRAY['id'])");
        assertUpdate("INSERT INTO table_with_partition_at_beginning VALUES (1, ROW(1, 2)), (1, ROW(2, 3)), (1, ROW(3, 4))", 3);
        assertQuery("SELECT id, root.f2 FROM table_with_partition_at_beginning", "VALUES (1, 2), (1, 3), (1, 4)");
        assertUpdate("DROP TABLE table_with_partition_at_beginning");

        assertUpdate("CREATE TABLE table_with_partition_at_end (root ROW(f1 BIGINT, f2 BIGINT), id BIGINT) WITH (partitioning = ARRAY['id'])");
        assertUpdate("INSERT INTO table_with_partition_at_end VALUES (ROW(1, 2), 1), (ROW(2, 3), 1), (ROW(3, 4), 1)", 3);
        assertQuery("SELECT root.f2, id FROM table_with_partition_at_end", "VALUES (2, 1), (3, 1), (4, 1)");
        assertUpdate("DROP TABLE table_with_partition_at_end");
    }

    @Test
    public void testProjectionPushdownOnPartitionedTableWithComments()
    {
        assertUpdate("CREATE TABLE test_projection_pushdown_comments (id BIGINT COMMENT 'id', qid BIGINT COMMENT 'QID', root ROW(f1 BIGINT, f2 BIGINT) COMMENT 'root') WITH (partitioning = ARRAY['id'])");
        assertUpdate("INSERT INTO test_projection_pushdown_comments VALUES (1, 1, ROW(1, 2)), (1, 2, ROW(2, 3)), (1, 3, ROW(3, 4))", 3);
        assertQuery("SELECT id, root.f2 FROM test_projection_pushdown_comments", "VALUES (1, 2), (1, 3), (1, 4)");
        // Query with predicates on both nested and top-level columns (with partition column)
        assertQuery("SELECT id, root.f2 FROM test_projection_pushdown_comments WHERE id = 1 AND qid = 1 AND root.f1 = 1", "VALUES (1, 2)");
        // Query with predicates on both nested and top-level columns (no partition column)
        assertQuery("SELECT id, root.f2 FROM test_projection_pushdown_comments WHERE qid = 2 AND root.f1 = 2", "VALUES (1, 3)");
        // Query with predicates on top-level columns only
        assertQuery("SELECT id, root.f2 FROM test_projection_pushdown_comments WHERE id = 1 AND qid = 1", "VALUES (1, 2)");
        // Query with predicates on nested columns only
        assertQuery("SELECT id, root.f2 FROM test_projection_pushdown_comments WHERE root.f1 = 2", "VALUES (1, 3)");
        assertUpdate("DROP TABLE IF EXISTS test_projection_pushdown_comments");
    }

    @Test(dataProvider = "tableFormatVersion")
    public void testOptimize(int formatVersion)
            throws Exception
    {
        String tableName = "test_optimize_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (key integer, value varchar) WITH (format_version = " + formatVersion + ")");

        // DistributedQueryRunner sets node-scheduler.include-coordinator by default, so include coordinator
        int workerCount = getQueryRunner().getNodeCount();

        // optimize an empty table
        assertQuerySucceeds("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        assertThat(getActiveFiles(tableName)).isEmpty();

        assertUpdate("INSERT INTO " + tableName + " VALUES (11, 'eleven')", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES (12, 'zwölf')", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES (13, 'trzynaście')", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES (14, 'quatorze')", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES (15, 'пʼятнадцять')", 1);

        List<String> initialFiles = getActiveFiles(tableName);
        assertThat(initialFiles)
                .hasSize(5)
                // Verify we have sufficiently many test rows with respect to worker count.
                .hasSizeGreaterThan(workerCount);

        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        assertThat(query("SELECT sum(key), listagg(value, ' ') WITHIN GROUP (ORDER BY key) FROM " + tableName))
                .matches("VALUES (BIGINT '65', VARCHAR 'eleven zwölf trzynaście quatorze пʼятнадцять')");
        List<String> updatedFiles = getActiveFiles(tableName);
        assertThat(updatedFiles)
                .hasSizeBetween(1, workerCount)
                .doesNotContainAnyElementsOf(initialFiles);
        // No files should be removed (this is expire_snapshots's job, when it exists)
        assertThat(getAllDataFilesFromTableDirectory(tableName))
                .containsExactlyInAnyOrderElementsOf(concat(initialFiles, updatedFiles));

        // optimize with low retention threshold, nothing should change
        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE (file_size_threshold => '33B')");
        assertThat(query("SELECT sum(key), listagg(value, ' ') WITHIN GROUP (ORDER BY key) FROM " + tableName))
                .matches("VALUES (BIGINT '65', VARCHAR 'eleven zwölf trzynaście quatorze пʼятнадцять')");
        assertThat(getActiveFiles(tableName)).isEqualTo(updatedFiles);
        assertThat(getAllDataFilesFromTableDirectory(tableName))
                .containsExactlyInAnyOrderElementsOf(concat(initialFiles, updatedFiles));

        // optimize with delimited procedure name
        assertQueryFails("ALTER TABLE " + tableName + " EXECUTE \"optimize\"", "Table procedure not registered: optimize");
        assertUpdate("ALTER TABLE " + tableName + " EXECUTE \"OPTIMIZE\"");
        // optimize with delimited parameter name (and procedure name)
        assertUpdate("ALTER TABLE " + tableName + " EXECUTE \"OPTIMIZE\" (\"file_size_threshold\" => '33B')"); // TODO (https://github.com/trinodb/trino/issues/11326) this should fail
        assertUpdate("ALTER TABLE " + tableName + " EXECUTE \"OPTIMIZE\" (\"FILE_SIZE_THRESHOLD\" => '33B')");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test(dataProvider = "tableFormatVersion")
    public void testOptimizeForPartitionedTable(int formatVersion)
            throws IOException
    {
        // This test will have its own session to make sure partitioning is indeed forced and is not a result
        // of session configuration
        Session session = testSessionBuilder()
                .setCatalog(getQueryRunner().getDefaultSession().getCatalog())
                .setSchema(getQueryRunner().getDefaultSession().getSchema())
                .setSystemProperty("use_preferred_write_partitioning", "true")
                .setSystemProperty("preferred_write_partitioning_min_number_of_partitions", "100")
                .build();
        String tableName = "test_repartitiong_during_optimize_" + randomTableSuffix();
        assertUpdate(session, "CREATE TABLE " + tableName + " (key varchar, value integer) WITH (format_version = " + formatVersion + ", partitioning = ARRAY['key'])");
        // optimize an empty table
        assertQuerySucceeds(session, "ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");

        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 2)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 3)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 4)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 5)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 6)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('one', 7)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('two', 8)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('two', 9)", 1);
        assertUpdate(session, "INSERT INTO " + tableName + " VALUES ('three', 10)", 1);

        List<String> initialFiles = getActiveFiles(tableName);
        assertThat(initialFiles).hasSize(10);

        computeActual(session, "ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");

        assertThat(query(session, "SELECT sum(value), listagg(key, ' ') WITHIN GROUP (ORDER BY key) FROM " + tableName))
                .matches("VALUES (BIGINT '55', VARCHAR 'one one one one one one one three two two')");

        List<String> updatedFiles = getActiveFiles(tableName);
        // as we force repartitioning there should be only 3 partitions
        assertThat(updatedFiles).hasSize(3);
        assertThat(getAllDataFilesFromTableDirectory(tableName)).containsExactlyInAnyOrderElementsOf(concat(initialFiles, updatedFiles));

        assertUpdate("DROP TABLE " + tableName);
    }

    @DataProvider
    public Object[][] tableFormatVersion()
    {
        return IntStream.rangeClosed(IcebergConfig.FORMAT_VERSION_SUPPORT_MIN, IcebergConfig.FORMAT_VERSION_SUPPORT_MAX).boxed()
                .collect(DataProviders.toDataProvider());
    }

    @Test(dataProvider = "testOptimizeTimePartitionedTableDataProvider")
    public void testOptimizeTimePartitionedTable(String dataType, String partitioningFormat, int expectedFilesAfterOptimize)
    {
        String tableName = "test_optimize_time_partitioned_" +
                (dataType + "_" + partitioningFormat).toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9_]", "");
        assertUpdate(format("CREATE TABLE %s(p %s, val varchar) WITH (partitioning = ARRAY['%s'])", tableName, dataType, format(partitioningFormat, "p")));

        // Do several inserts so ensure more than one input file
        for (int hour = 0; hour < 5; hour++) {
            assertUpdate(
                    "" +
                            "INSERT INTO " + tableName + " " +
                            "SELECT CAST(t AS " + dataType + "), CAST(t AS varchar) " +
                            "FROM (" +
                            "    SELECT " +
                            "        TIMESTAMP '2022-01-16 10:05:06.123456 UTC'" +
                            "            + month * INTERVAL '1' MONTH " +
                            "            + day * INTERVAL '1' DAY " +
                            "            + " + hour + " * INTERVAL '1' HOUR " +
                            "            AS t" +
                            "    FROM UNNEST(sequence(1, 5)) AS _(month)" +
                            "    CROSS JOIN UNNEST(sequence(1, 5)) AS _(day)" +
                            ")",
                    25);
        }

        String optimizeDate = "DATE '2022-04-01'";
        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName))
                .as("total file count")
                .isGreaterThanOrEqualTo(5);
        long filesBeforeOptimizeDate = (long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p < " + optimizeDate);
        assertThat(filesBeforeOptimizeDate)
                .as("file count before optimize date")
                .isGreaterThanOrEqualTo(5);
        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p >= " + optimizeDate))
                .as("file count after optimize date")
                .isGreaterThanOrEqualTo(5);

        assertUpdate(
                // Use UTC zone so that DATE and TIMESTAMP WITH TIME ZONE comparisons align with partition boundaries.
                Session.builder(getSession())
                        .setTimeZoneKey(UTC_KEY)
                        .build(),
                "ALTER TABLE " + tableName + " EXECUTE optimize WHERE p >= " + optimizeDate);

        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p < " + optimizeDate))
                .as("file count before optimize date, after the optimize")
                .isEqualTo(filesBeforeOptimizeDate);
        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p >= " + optimizeDate))
                .as("file count after optimize date, after the optimize")
                .isEqualTo(expectedFilesAfterOptimize);

        // Verify that WHERE CAST(p AS date) ... form works in non-UTC zone
        assertUpdate(
                Session.builder(getSession())
                        .setTimeZoneKey(getTimeZoneKey("Asia/Kathmandu"))
                        .build(),
                "ALTER TABLE " + tableName + " EXECUTE optimize WHERE CAST(p AS date) >= " + optimizeDate);

        // Table state shouldn't change substantially (but files may be rewritten)
        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p < " + optimizeDate))
                .as("file count before optimize date, after the second optimize")
                .isEqualTo(filesBeforeOptimizeDate);
        assertThat((long) computeScalar("SELECT count(DISTINCT \"$path\") FROM " + tableName + " WHERE p >= " + optimizeDate))
                .as("file count after optimize date, after the second optimize")
                .isEqualTo(expectedFilesAfterOptimize);

        assertUpdate("DROP TABLE " + tableName);
    }

    @DataProvider
    public static Object[][] testOptimizeTimePartitionedTableDataProvider()
    {
        return new Object[][] {
                {"date", "%s", 15},
                {"date", "day(%s)", 15},
                {"date", "month(%s)", 3},
                {"timestamp(6)", "day(%s)", 15},
                {"timestamp(6)", "month(%s)", 3},
                {"timestamp(6) with time zone", "day(%s)", 15},
                {"timestamp(6) with time zone", "month(%s)", 3},
        };
    }

    @Test
    public void testOptimizeTableAfterDeleteWithFormatVersion2()
    {
        String tableName = "test_optimize_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM nation", 25);

        List<String> initialFiles = getActiveFiles(tableName);

        assertUpdate("DELETE FROM " + tableName + " WHERE nationkey = 7", 1);

        // Verify that delete files exists
        assertQuery(
                "SELECT summary['total-delete-files'] FROM \"" + tableName + "$snapshots\" WHERE snapshot_id = " + getCurrentSnapshotId(tableName),
                "VALUES '1'");

        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");

        List<String> updatedFiles = getActiveFiles(tableName);
        assertThat(updatedFiles)
                .hasSize(1)
                .isNotEqualTo(initialFiles);

        assertThat(query("SELECT * FROM " + tableName))
                .matches("SELECT * FROM nation WHERE nationkey != 7");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testOptimizeCleansUpDeleteFiles()
            throws IOException
    {
        String tableName = "test_optimize_" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM nation", 25);

        List<String> allDataFilesInitially = getAllDataFilesFromTableDirectory(tableName);
        assertThat(allDataFilesInitially).hasSize(5);

        assertUpdate("DELETE FROM " + tableName + " WHERE nationkey = 7", 1);

        assertQuery(
                "SELECT summary['total-delete-files'] FROM \"" + tableName + "$snapshots\" WHERE snapshot_id = " + getCurrentSnapshotId(tableName),
                "VALUES '1'");

        List<String> allDataFilesAfterDelete = getAllDataFilesFromTableDirectory(tableName);
        assertThat(allDataFilesAfterDelete).hasSize(6);

        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE WHERE regionkey = 4");
        computeActual(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')");
        computeActual(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");

        assertQuery(
                "SELECT summary['total-delete-files'] FROM \"" + tableName + "$snapshots\" WHERE snapshot_id = " + getCurrentSnapshotId(tableName),
                "VALUES '1'");
        List<String> allDataFilesAfterOptimizeWithWhere = getAllDataFilesFromTableDirectory(tableName);
        assertThat(allDataFilesAfterOptimizeWithWhere)
                .hasSize(6)
                .doesNotContain(allDataFilesInitially.stream().filter(file -> file.contains("regionkey=4"))
                        .collect(toImmutableList())
                        .toArray(new String[0]));

        assertThat(query("SELECT * FROM " + tableName))
                .matches("SELECT * FROM nation WHERE nationkey != 7");

        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        computeActual(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')");
        computeActual(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");

        assertQuery(
                "SELECT summary['total-delete-files'] FROM \"" + tableName + "$snapshots\" WHERE snapshot_id = " + getCurrentSnapshotId(tableName),
                "VALUES '0'");
        List<String> allDataFilesAfterFullOptimize = getAllDataFilesFromTableDirectory(tableName);
        assertThat(allDataFilesAfterFullOptimize)
                .hasSize(5)
                .doesNotContain(allDataFilesInitially.toArray(new String[0]));

        assertThat(query("SELECT * FROM " + tableName))
                .matches("SELECT * FROM nation WHERE nationkey != 7");

        assertUpdate("DROP TABLE " + tableName);
    }

    private List<String> getActiveFiles(String tableName)
    {
        return computeActual(format("SELECT file_path FROM \"%s$files\"", tableName)).getOnlyColumn()
                .map(String.class::cast)
                .collect(toImmutableList());
    }

    private List<String> getAllDataFilesFromTableDirectory(String tableName)
            throws IOException
    {
        String schema = getSession().getSchema().orElseThrow();
        Path tableDataDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().resolve("iceberg_data").resolve(schema).resolve(tableName).resolve("data");
        try (Stream<Path> walk = Files.walk(tableDataDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().matches("\\..*\\.crc"))
                    .map(Path::toString)
                    .collect(toImmutableList());
        }
    }

    @Test
    public void testOptimizeParameterValidation()
    {
        assertQueryFails(
                "ALTER TABLE no_such_table_exists EXECUTE OPTIMIZE",
                "\\Qline 1:7: Table 'iceberg.tpch.no_such_table_exists' does not exist");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE OPTIMIZE (file_size_threshold => '33')",
                "\\QUnable to set catalog 'iceberg' table procedure 'OPTIMIZE' property 'file_size_threshold' to ['33']: size is not a valid data size string: 33");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE OPTIMIZE (file_size_threshold => '33s')",
                "\\QUnable to set catalog 'iceberg' table procedure 'OPTIMIZE' property 'file_size_threshold' to ['33s']: Unknown unit: s");
    }

    @Test
    public void testTargetMaxFileSize()
    {
        String tableName = "test_default_max_file_size" + randomTableSuffix();
        @Language("SQL") String createTableSql = format("CREATE TABLE %s AS SELECT * FROM tpch.sf1.lineitem LIMIT 100000", tableName);

        Session session = Session.builder(getSession())
                .setSystemProperty("task_writer_count", "1")
                .build();
        assertUpdate(session, createTableSql, 100000);
        List<String> initialFiles = getActiveFiles(tableName);
        assertThat(initialFiles.size()).isLessThanOrEqualTo(3);
        assertUpdate(format("DROP TABLE %s", tableName));

        DataSize maxSize = DataSize.of(40, DataSize.Unit.KILOBYTE);
        session = Session.builder(getSession())
                .setSystemProperty("task_writer_count", "1")
                .setCatalogSessionProperty("iceberg", "target_max_file_size", maxSize.toString())
                .build();

        assertUpdate(session, createTableSql, 100000);
        assertThat(query(format("SELECT count(*) FROM %s", tableName))).matches("VALUES BIGINT '100000'");
        List<String> updatedFiles = getActiveFiles(tableName);
        assertThat(updatedFiles.size()).isGreaterThan(10);

        computeActual(format("SELECT file_size_in_bytes FROM \"%s$files\"", tableName))
                .getMaterializedRows()
                // as target_max_file_size is set to quite low value it can happen that created files are bigger,
                // so just to be safe we check if it is not much bigger
                .forEach(row -> assertThat((Long) row.getField(0)).isBetween(1L, maxSize.toBytes() * 3));
    }

    @Test
    public void testDroppingIcebergAndCreatingANewTableWithTheSameNameShouldBePossible()
    {
        assertUpdate("CREATE TABLE test_iceberg_recreate (a_int) AS VALUES (1)", 1);
        assertThat(query("SELECT min(a_int) FROM test_iceberg_recreate")).matches("VALUES 1");
        dropTable("test_iceberg_recreate");

        assertUpdate("CREATE TABLE test_iceberg_recreate (a_varchar) AS VALUES ('Trino')", 1);
        assertThat(query("SELECT min(a_varchar) FROM test_iceberg_recreate")).matches("VALUES CAST('Trino' AS varchar)");
        dropTable("test_iceberg_recreate");
    }

    @Test
    public void testPathHiddenColumn()
    {
        String tableName = "test_path_" + randomTableSuffix();
        @Language("SQL") String createTable = "CREATE TABLE " + tableName + " " +
                "WITH ( partitioning = ARRAY['zip'] ) AS " +
                "SELECT * FROM (VALUES " +
                "(0, 0), (3, 0), (6, 0), " +
                "(1, 1), (4, 1), (7, 1), " +
                "(2, 2), (5, 2) " +
                " ) t(userid, zip)";
        assertUpdate(createTable, 8);

        MaterializedResult expectedColumns = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("userid", "integer", "", "")
                .row("zip", "integer", "", "")
                .build();
        MaterializedResult actualColumns = computeActual(format("DESCRIBE %s", tableName));
        // Describe output should not have the $path hidden column
        assertEquals(actualColumns, expectedColumns);

        assertThat(query("SELECT file_path FROM \"" + tableName + "$files\""))
                .matches("SELECT DISTINCT \"$path\" as file_path FROM " + tableName);

        String somePath = (String) computeScalar("SELECT \"$path\" FROM " + tableName + " WHERE userid = 2");
        assertThat(query("SELECT userid FROM " + tableName + " WHERE \"$path\" = '" + somePath + "'"))
                .matches("VALUES 2, 5");
        assertThat(query("SELECT userid FROM " + tableName + " WHERE \"$path\" = '" + somePath + "' AND userid > 0"))
                .matches("VALUES 2, 5");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testExpireSnapshots()
            throws Exception
    {
        String tableName = "test_expiring_snapshots_" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer)");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);
        assertThat(query("SELECT sum(value), listagg(key, ' ') WITHIN GROUP (ORDER BY key) FROM " + tableName))
                .matches("VALUES (BIGINT '3', VARCHAR 'one two')");

        List<Long> initialSnapshots = getSnapshotIds(tableName);
        List<String> initialFiles = getAllMetadataFilesFromTableDirectoryForTable(tableName);
        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')");

        assertThat(query("SELECT sum(value), listagg(key, ' ') WITHIN GROUP (ORDER BY key) FROM " + tableName))
                .matches("VALUES (BIGINT '3', VARCHAR 'one two')");
        List<String> updatedFiles = getAllMetadataFilesFromTableDirectoryForTable(tableName);
        List<Long> updatedSnapshots = getSnapshotIds(tableName);
        assertThat(updatedFiles.size()).isEqualTo(initialFiles.size() - 1);
        assertThat(updatedSnapshots.size()).isLessThan(initialSnapshots.size());
        assertThat(updatedSnapshots.size()).isEqualTo(1);
        assertThat(initialSnapshots).containsAll(updatedSnapshots);
    }

    @Test
    public void testExpireSnapshotsPartitionedTable()
            throws Exception
    {
        String tableName = "test_expiring_snapshots_partitioned_table" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " (col1 BIGINT, col2 BIGINT) WITH (partitioning = ARRAY['col1'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES(1, 100), (1, 101), (1, 102), (2, 200), (2, 201), (3, 300)", 6);
        assertUpdate("DELETE FROM " + tableName + " WHERE col1 = 1", 3);
        assertUpdate("INSERT INTO " + tableName + " VALUES(4, 400)", 1);
        assertQuery("SELECT sum(col2) FROM " + tableName, "SELECT 1101");
        List<String> initialDataFiles = getAllDataFilesFromTableDirectory(tableName);
        List<Long> initialSnapshots = getSnapshotIds(tableName);

        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')");

        List<String> updatedDataFiles = getAllDataFilesFromTableDirectory(tableName);
        List<Long> updatedSnapshots = getSnapshotIds(tableName);
        assertQuery("SELECT sum(col2) FROM " + tableName, "SELECT 1101");
        assertThat(updatedDataFiles.size()).isLessThan(initialDataFiles.size());
        assertThat(updatedSnapshots.size()).isLessThan(initialSnapshots.size());
    }

    @Test
    public void testExplainExpireSnapshotOutput()
    {
        String tableName = "test_expiring_snapshots_output" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer) WITH (partitioning = ARRAY['key'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);

        assertExplain("EXPLAIN ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')",
                "SimpleTableExecute\\[table = iceberg:schemaTableName:tpch.test_expiring_snapshots.*\\{retentionThreshold=0\\.00s}.*");
    }

    @Test
    public void testExpireSnapshotsParameterValidation()
    {
        assertQueryFails(
                "ALTER TABLE no_such_table_exists EXECUTE EXPIRE_SNAPSHOTS",
                "\\Qline 1:7: Table 'iceberg.tpch.no_such_table_exists' does not exist");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '33')",
                "\\QUnable to set catalog 'iceberg' table procedure 'EXPIRE_SNAPSHOTS' property 'retention_threshold' to ['33']: duration is not a valid data duration string: 33");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '33mb')",
                "\\QUnable to set catalog 'iceberg' table procedure 'EXPIRE_SNAPSHOTS' property 'retention_threshold' to ['33mb']: Unknown time unit: mb");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '33s')",
                "\\QRetention specified (33.00s) is shorter than the minimum retention configured in the system (7.00d). Minimum retention can be changed with iceberg.expire_snapshots.min-retention configuration property or iceberg.expire_snapshots_min_retention session property");
    }

    @Test
    public void testRemoveOrphanFiles()
            throws Exception
    {
        String tableName = "test_deleting_orphan_files_unnecessary_files" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer)");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        Path orphanFile = Files.createFile(Path.of(getIcebergTableDataPath(tableName).toString(), "invalidData." + format));
        List<String> initialDataFiles = getAllDataFilesFromTableDirectory(tableName);

        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");

        List<String> updatedDataFiles = getAllDataFilesFromTableDirectory(tableName);
        assertThat(updatedDataFiles.size()).isLessThan(initialDataFiles.size());
        assertThat(updatedDataFiles).doesNotContain(orphanFile.toString());
    }

    @Test
    public void testIfRemoveOrphanFilesCleansUnnecessaryDataFilesInPartitionedTable()
            throws Exception
    {
        String tableName = "test_deleting_orphan_files_unnecessary_files" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer) WITH (partitioning = ARRAY['key'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);
        Path orphanFile = Files.createFile(Path.of(getIcebergTableDataPath(tableName) + "/key=one/", "invalidData." + format));
        List<String> initialDataFiles = getAllDataFilesFromTableDirectory(tableName);

        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");

        List<String> updatedDataFiles = getAllDataFilesFromTableDirectory(tableName);
        assertThat(updatedDataFiles.size()).isLessThan(initialDataFiles.size());
        assertThat(updatedDataFiles).doesNotContain(orphanFile.toString());
    }

    @Test
    public void testIfRemoveOrphanFilesCleansUnnecessaryMetadataFilesInPartitionedTable()
            throws Exception
    {
        String tableName = "test_deleting_orphan_files_unnecessary_files" + randomTableSuffix();
        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer) WITH (partitioning = ARRAY['key'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);
        Path orphanMetadataFile = Files.createFile(Path.of(getIcebergTableMetadataPath(tableName).toString(), "invalidData." + format));
        List<String> initialMetadataFiles = getAllMetadataFilesFromTableDirectoryForTable(tableName);

        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");

        List<String> updatedMetadataFiles = getAllMetadataFilesFromTableDirectoryForTable(tableName);
        assertThat(updatedMetadataFiles.size()).isLessThan(initialMetadataFiles.size());
        assertThat(updatedMetadataFiles).doesNotContain(orphanMetadataFile.toString());
    }

    @Test
    public void testCleaningUpWithTableWithSpecifiedLocationWithSlashAtTheEnd()
            throws IOException
    {
        testCleaningUpWithTableWithSpecifiedLocation("/");
    }

    @Test
    public void testCleaningUpWithTableWithSpecifiedLocationWithoutSlashAtTheEnd()
            throws IOException
    {
        testCleaningUpWithTableWithSpecifiedLocation("");
    }

    private void testCleaningUpWithTableWithSpecifiedLocation(String suffix)
            throws IOException
    {
        File tempDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().toFile();
        String tempDirPath = tempDir.toURI().toASCIIString() + randomTableSuffix() + suffix;
        String tableName = "test_table_cleaning_up_with_location" + randomTableSuffix();

        assertUpdate(format("CREATE TABLE %s (key varchar, value integer) WITH(location = '%s')", tableName, tempDirPath));
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);

        List<String> initialFiles = getAllMetadataFilesFromTableDirectory(tempDirPath);
        List<Long> initialSnapshots = getSnapshotIds(tableName);

        Session sessionWithShortRetentionUnlocked = prepareCleanUpSession();
        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE EXPIRE_SNAPSHOTS (retention_threshold => '0s')");
        assertQuerySucceeds(sessionWithShortRetentionUnlocked, "ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')");
        List<String> updatedFiles = getAllMetadataFilesFromTableDirectory(tempDirPath);
        List<Long> updatedSnapshots = getSnapshotIds(tableName);
        assertThat(updatedFiles.size()).isEqualTo(initialFiles.size() - 1);
        assertThat(updatedSnapshots.size()).isLessThan(initialSnapshots.size());
        assertThat(updatedSnapshots.size()).isEqualTo(1);
        assertThat(initialSnapshots).containsAll(updatedSnapshots);
    }

    @Test
    public void testExplainRemoveOrphanFilesOutput()
    {
        String tableName = "test_remove_orphan_files_output" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer) WITH (partitioning = ARRAY['key'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);

        assertExplain("EXPLAIN ALTER TABLE " + tableName + " EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '0s')",
                "SimpleTableExecute\\[table = iceberg:schemaTableName:tpch.test_remove_orphan_files.*\\{retentionThreshold=0\\.00s}.*");
    }

    @Test
    public void testRemoveOrphanFilesParameterValidation()
    {
        assertQueryFails(
                "ALTER TABLE no_such_table_exists EXECUTE REMOVE_ORPHAN_FILES",
                "\\Qline 1:7: Table 'iceberg.tpch.no_such_table_exists' does not exist");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '33')",
                "\\QUnable to set catalog 'iceberg' table procedure 'REMOVE_ORPHAN_FILES' property 'retention_threshold' to ['33']: duration is not a valid data duration string: 33");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '33mb')",
                "\\QUnable to set catalog 'iceberg' table procedure 'REMOVE_ORPHAN_FILES' property 'retention_threshold' to ['33mb']: Unknown time unit: mb");
        assertQueryFails(
                "ALTER TABLE nation EXECUTE REMOVE_ORPHAN_FILES (retention_threshold => '33s')",
                "\\QRetention specified (33.00s) is shorter than the minimum retention configured in the system (7.00d). Minimum retention can be changed with iceberg.remove_orphan_files.min-retention configuration property or iceberg.remove_orphan_files_min_retention session property");
    }

    @Test
    public void testIfDeletesReturnsNumberOfRemovedRows()
    {
        String tableName = "test_delete_returns_number_of_rows_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (key varchar, value integer) WITH (partitioning = ARRAY['key'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 2)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('one', 3)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES ('two', 2)", 1);
        assertUpdate("DELETE FROM " + tableName + " WHERE key = 'one'", 3);
        assertUpdate("DELETE FROM " + tableName + " WHERE key = 'one'"); // TODO change this when iceberg will guarantee to always return this (https://github.com/apache/iceberg/issues/4647)
        assertUpdate("DELETE FROM " + tableName + " WHERE key = 'three'");
        assertUpdate("DELETE FROM " + tableName + " WHERE key = 'two'", 2);
    }

    @Test
    public void testUpdatingFileFormat()
    {
        String tableName = "test_updating_file_format_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " WITH (format = 'orc') AS SELECT * FROM nation WHERE nationkey < 10", "SELECT count(*) FROM nation WHERE nationkey < 10");
        assertQuery("SELECT value FROM \"" + tableName + "$properties\" WHERE key = 'write.format.default'", "VALUES 'ORC'");

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES format = 'parquet'");
        assertQuery("SELECT value FROM \"" + tableName + "$properties\" WHERE key = 'write.format.default'", "VALUES 'PARQUET'");
        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM nation WHERE nationkey >= 10", "SELECT count(*) FROM nation WHERE nationkey >= 10");

        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation");
        assertQuery("SELECT count(*) FROM \"" + tableName + "$files\" WHERE file_path LIKE '%.orc'", "VALUES 1");
        assertQuery("SELECT count(*) FROM \"" + tableName + "$files\" WHERE file_path LIKE '%.parquet'", "VALUES 1");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testUpdatingInvalidTableProperty()
    {
        String tableName = "test_updating_invalid_table_property_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (a INT, b INT)");
        assertThatThrownBy(() -> query("ALTER TABLE " + tableName + " SET PROPERTIES not_a_valid_table_property = 'a value'"))
                .hasMessage("Catalog 'iceberg' table property 'not_a_valid_table_property' does not exist");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testEmptyCreateTableAsSelect()
    {
        String tableName = "test_empty_ctas_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM nation WHERE false", 0);
        List<Long> initialTableSnapshots = getSnapshotIds(tableName);
        assertThat(initialTableSnapshots.size())
                .withFailMessage("CTAS operations must create Iceberg snapshot independently whether the selection is empty or not")
                .isEqualTo(1);
        assertQueryReturnsEmptyResult("SELECT * FROM " + tableName);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testEmptyInsert()
    {
        String tableName = "test_empty_insert_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM nation", "SELECT count(*) FROM nation");
        List<Long> initialTableSnapshots = getSnapshotIds(tableName);

        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM nation WHERE false", 0);
        List<Long> updatedTableSnapshots = getSnapshotIds(tableName);

        assertThat(initialTableSnapshots)
                .withFailMessage("INSERT operations that are not changing the state of the table must not cause the creation of a new Iceberg snapshot")
                .hasSize(1)
                .isEqualTo(updatedTableSnapshots);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testEmptyUpdate()
    {
        String tableName = "test_empty_update_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM nation", "SELECT count(*) FROM nation");
        List<Long> initialTableSnapshots = getSnapshotIds(tableName);

        assertUpdate("UPDATE " + tableName + " SET comment = 'new comment' WHERE nationkey IS NULL", 0);
        List<Long> updatedTableSnapshots = getSnapshotIds(tableName);

        assertThat(initialTableSnapshots)
                .withFailMessage("UPDATE operations that are not changing the state of the table must not cause the creation of a new Iceberg snapshot")
                .hasSize(1)
                .isEqualTo(updatedTableSnapshots);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testEmptyDelete()
    {
        String tableName = "test_empty_delete_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " WITH (format = '" + format.name() + "') AS SELECT * FROM nation", "SELECT count(*) FROM nation");
        List<Long> initialTableSnapshots = getSnapshotIds(tableName);

        assertUpdate("DELETE FROM " + tableName + " WHERE nationkey IS NULL", 0);
        List<Long> updatedTableSnapshots = getSnapshotIds(tableName);

        assertThat(initialTableSnapshots)
                .withFailMessage("DELETE operations that are not changing the state of the table must not cause the creation of a new Iceberg snapshot")
                .hasSize(1)
                .isEqualTo(updatedTableSnapshots);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testModifyingOldSnapshotIsNotPossible()
    {
        Session sessionWithLegacySyntaxSupport = Session.builder(getSession())
                .setCatalogSessionProperty("iceberg", "allow_legacy_snapshot_syntax", "true")
                .build();
        String tableName = "test_modifying_old_snapshot_" + randomTableSuffix();
        assertUpdate(format("CREATE TABLE %s (col int)", tableName));
        assertUpdate(format("INSERT INTO %s VALUES 1,2,3", tableName), 3);
        long oldSnapshotId = getCurrentSnapshotId(tableName);
        assertUpdate(format("INSERT INTO %s VALUES 4,5,6", tableName), 3);
        assertQuery(sessionWithLegacySyntaxSupport, format("SELECT * FROM \"%s@%d\"", tableName, oldSnapshotId), "VALUES 1,2,3");
        assertThatThrownBy(() -> query(sessionWithLegacySyntaxSupport, format("INSERT INTO \"%s@%d\" VALUES 7,8,9", tableName, oldSnapshotId)))
                .hasMessage("Modifying old snapshot is not supported in Iceberg.");
        assertThatThrownBy(() -> query(sessionWithLegacySyntaxSupport, format("DELETE FROM \"%s@%d\" WHERE col = 5", tableName, oldSnapshotId)))
                .hasMessage("Modifying old snapshot is not supported in Iceberg.");
        assertThatThrownBy(() -> query(sessionWithLegacySyntaxSupport, format("UPDATE \"%s@%d\" SET col = 50 WHERE col = 5", tableName, oldSnapshotId)))
                .hasMessage("Modifying old snapshot is not supported in Iceberg.");
        assertThatThrownBy(() -> query(sessionWithLegacySyntaxSupport, format("ALTER TABLE \"%s@%d\" EXECUTE OPTIMIZE", tableName, oldSnapshotId)))
                .hasMessage("Modifying old snapshot is not supported in Iceberg.");
        assertUpdate(sessionWithLegacySyntaxSupport, format("INSERT INTO \"%s@%d\" VALUES 7,8,9", tableName, getCurrentSnapshotId(tableName)), 3);
        assertUpdate(sessionWithLegacySyntaxSupport, format("DELETE FROM \"%s@%d\" WHERE col = 9", tableName, getCurrentSnapshotId(tableName)), 1);
        assertUpdate(sessionWithLegacySyntaxSupport, format("UPDATE \"%s@%d\" set col = 50 WHERE col = 5", tableName, getCurrentSnapshotId(tableName)), 1);
        assertQuerySucceeds(sessionWithLegacySyntaxSupport, format("ALTER TABLE \"%s@%d\" EXECUTE OPTIMIZE", tableName, getCurrentSnapshotId(tableName)));
        assertQuery(format("SELECT * FROM %s", tableName), "VALUES 1,2,3,4,50,6,7,8");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testCreateTableAsSelectFromVersionedTable()
            throws Exception
    {
        String sourceTableName = "test_ctas_versioned_source_" + randomTableSuffix();
        String snapshotVersionedSinkTableName = "test_ctas_snapshot_versioned_sink_" + randomTableSuffix();
        String timestampVersionedSinkTableName = "test_ctas_timestamp_versioned_sink_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + sourceTableName + "(an_integer integer)");
        // Enforce having exactly one snapshot of the table at the timestamp corresponding to `afterInsert123EpochMillis`
        Thread.sleep(1);
        assertUpdate("INSERT INTO " + sourceTableName + " VALUES 1, 2, 3", 3);
        long afterInsert123SnapshotId = getLatestSnapshotId(sourceTableName);
        long afterInsert123EpochMillis = getCommittedAtInEpochMilliseconds(sourceTableName, afterInsert123SnapshotId);
        Thread.sleep(1);
        assertUpdate("INSERT INTO " + sourceTableName + " VALUES 4, 5, 6", 3);
        long afterInsert456SnapshotId = getLatestSnapshotId(sourceTableName);
        assertUpdate("INSERT INTO " + sourceTableName + " VALUES 7, 8, 9", 3);

        assertUpdate("CREATE TABLE " + snapshotVersionedSinkTableName + " AS SELECT * FROM " + sourceTableName + " FOR VERSION AS OF " + afterInsert456SnapshotId, 6);
        assertUpdate("CREATE TABLE " + timestampVersionedSinkTableName + " AS SELECT * FROM " + sourceTableName + " FOR TIMESTAMP AS OF " + timestampLiteral(afterInsert123EpochMillis, 9), 3);

        assertQuery("SELECT * FROM " + sourceTableName, "VALUES 1, 2, 3, 4, 5, 6, 7, 8, 9");
        assertQuery("SELECT * FROM " + snapshotVersionedSinkTableName, "VALUES 1, 2, 3, 4, 5, 6");
        assertQuery("SELECT * FROM " + timestampVersionedSinkTableName, "VALUES 1, 2, 3");

        assertUpdate("DROP TABLE " + sourceTableName);
        assertUpdate("DROP TABLE " + snapshotVersionedSinkTableName);
        assertUpdate("DROP TABLE " + timestampVersionedSinkTableName);
    }

    @Test
    public void testReadingFromSpecificSnapshot()
    {
        String tableName = "test_reading_snapshot" + randomTableSuffix();
        assertUpdate(format("CREATE TABLE %s (a bigint, b bigint)", tableName));
        assertUpdate(format("INSERT INTO %s VALUES(1, 1)", tableName), 1);
        List<Long> ids = getSnapshotsIdsByCreationOrder(tableName);

        assertQuery(sessionWithLegacySyntaxSupport(), format("SELECT count(*) FROM \"%s@%d\"", tableName, ids.get(0)), "VALUES(0)");
        assertQuery(sessionWithLegacySyntaxSupport(), format("SELECT * FROM \"%s@%d\"", tableName, ids.get(1)), "VALUES(1,1)");
        assertUpdate(format("DROP TABLE %s", tableName));
    }

    @Test
    public void testLegacySnapshotSyntaxSupport()
    {
        String tableName = "test_legacy_snapshot_access" + randomTableSuffix();
        assertUpdate(format("CREATE TABLE %s (a BIGINT, b BIGINT)", tableName));
        assertUpdate(format("INSERT INTO %s VALUES(1, 1)", tableName), 1);
        List<Long> ids = getSnapshotsIdsByCreationOrder(tableName);
        // come up with a timestamp value in future that is not an already existing id
        long futureTimeStamp = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (ids.contains(futureTimeStamp)) {
            futureTimeStamp += TimeUnit.MINUTES.toMillis(5);
        }

        String selectAllFromFutureTimeStamp = format("SELECT * FROM \"%s@%d\"", tableName, futureTimeStamp);
        String selectAllFromLatestId = format("SELECT * FROM \"%s@%d\"", tableName, getLast(ids));
        String selectFromPartitionsTable = format("SELECT record_count FROM \"%s$partitions@%d\"", tableName, getLast(ids));

        assertQuery(sessionWithLegacySyntaxSupport(), selectAllFromFutureTimeStamp, "VALUES(1, 1)");
        assertQuery(sessionWithLegacySyntaxSupport(), selectAllFromLatestId, "VALUES(1, 1)");
        assertQuery(sessionWithLegacySyntaxSupport(), selectFromPartitionsTable, "VALUES(1)");

        // DISABLED
        String errorMessage = "Failed to access snapshot .* for table .*. This syntax for accessing Iceberg tables is not "
                + "supported. Use the AS OF syntax OR set the catalog session property "
                + "allow_legacy_snapshot_syntax=true for temporarily restoring previous behavior.";
        assertThatThrownBy(() -> query(getSession(), selectAllFromFutureTimeStamp))
                .hasMessageMatching(errorMessage);
        assertThatThrownBy(() -> query(getSession(), selectAllFromLatestId))
                .hasMessageMatching(errorMessage);
        assertThatThrownBy(() -> query(getSession(), selectFromPartitionsTable))
                .hasMessageMatching(errorMessage);

        assertUpdate(format("DROP TABLE %s", tableName));
    }

    @Test
    public void testSelectWithMoreThanOneSnapshotOfTheSameTable()
    {
        String tableName = "test_reading_snapshot" + randomTableSuffix();
        assertUpdate(format("CREATE TABLE %s (a bigint, b bigint)", tableName));
        assertUpdate(format("INSERT INTO %s VALUES(1, 1)", tableName), 1);
        assertUpdate(format("INSERT INTO %s VALUES(2, 2)", tableName), 1);
        assertUpdate(format("INSERT INTO %s VALUES(3, 3)", tableName), 1);
        List<Long> ids = getSnapshotsIdsByCreationOrder(tableName);

        assertQuery(format("SELECT * FROM %s", tableName), "SELECT * FROM (VALUES(1,1), (2,2), (3,3))");
        assertQuery(
                sessionWithLegacySyntaxSupport(),
                format("SELECT * FROM %1$s EXCEPT (SELECT * FROM \"%1$s@%2$d\" EXCEPT SELECT * FROM \"%1$s@%3$d\")", tableName, ids.get(2), ids.get(1)),
                "SELECT * FROM (VALUES(1,1), (3,3))");
        assertUpdate(format("DROP TABLE %s", tableName));
    }

    private Session prepareCleanUpSession()
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("iceberg", "expire_snapshots_min_retention", "0s")
                .setCatalogSessionProperty("iceberg", "remove_orphan_files_min_retention", "0s")
                .build();
    }

    private List<String> getAllMetadataFilesFromTableDirectoryForTable(String tableName)
            throws IOException
    {
        String schema = getSession().getSchema().orElseThrow();
        Path tableDataDir = getDistributedQueryRunner().getCoordinator().getBaseDataDir().resolve("iceberg_data").resolve(schema).resolve(tableName).resolve("metadata");
        return listAllTableFilesInDirectory(tableDataDir);
    }

    private List<String> getAllMetadataFilesFromTableDirectory(String tableDataDir)
            throws IOException
    {
        return listAllTableFilesInDirectory(Path.of(URI.create(tableDataDir).getPath()));
    }

    private List<String> listAllTableFilesInDirectory(Path tableDataPath)
            throws IOException
    {
        try (Stream<Path> walk = Files.walk(tableDataPath)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().matches("\\..*\\.crc"))
                    .map(Path::toString)
                    .collect(toImmutableList());
        }
    }

    private List<Long> getSnapshotIds(String tableName)
    {
        return getQueryRunner().execute(format("SELECT snapshot_id FROM \"%s$snapshots\"", tableName))
                .getOnlyColumn()
                .map(Long.class::cast)
                .collect(toUnmodifiableList());
    }

    private long getCurrentSnapshotId(String tableName)
    {
        return (long) computeScalar("SELECT snapshot_id FROM \"" + tableName + "$snapshots\" ORDER BY committed_at DESC LIMIT 1");
    }

    private Path getIcebergTableDataPath(String tableName)
    {
        return getIcebergTablePath(tableName, "data");
    }

    private Path getIcebergTableMetadataPath(String tableName)
    {
        return getIcebergTablePath(tableName, "metadata");
    }

    private Path getIcebergTablePath(String tableName, String suffix)
    {
        String schema = getSession().getSchema().orElseThrow();
        return getDistributedQueryRunner().getCoordinator().getBaseDataDir().resolve("iceberg_data").resolve(schema).resolve(tableName).resolve(suffix);
    }

    private long getCommittedAtInEpochMilliseconds(String tableName, long snapshotId)
    {
        return ((ZonedDateTime) computeActual(format("SELECT committed_at FROM \"%s$snapshots\" WHERE snapshot_id=%s LIMIT 1", tableName, snapshotId)).getOnlyValue())
                .toInstant().toEpochMilli();
    }

    private static String timestampLiteral(long epochMilliSeconds, int precision)
    {
        return DateTimeFormatter.ofPattern("'TIMESTAMP '''uuuu-MM-dd HH:mm:ss." + "S".repeat(precision) + " VV''")
                .format(Instant.ofEpochMilli(epochMilliSeconds).atZone(UTC));
    }

    private List<Long> getSnapshotsIdsByCreationOrder(String tableName)
    {
        int idField = 0;
        return getQueryRunner().execute(
                format("SELECT snapshot_id FROM \"%s$snapshots\" ORDER BY committed_at", tableName))
                .getMaterializedRows().stream()
                .map(row -> (Long) row.getField(idField))
                .collect(toList());
    }

    private Session sessionWithLegacySyntaxSupport()
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("iceberg", "allow_legacy_snapshot_syntax", "true")
                .build();
    }
}
