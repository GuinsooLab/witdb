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
package io.trino.plugin.bigquery;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.testing.BaseConnectorTest;
import io.trino.testing.MaterializedResult;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.TestTable;
import io.trino.testing.sql.TestView;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.base.Strings.nullToEmpty;
import static io.trino.plugin.bigquery.BigQueryQueryRunner.BigQuerySqlExecutor;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.assertions.Assert.assertEquals;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBigQueryConnectorTest
        extends BaseConnectorTest
{
    protected BigQuerySqlExecutor bigQuerySqlExecutor;

    @BeforeClass(alwaysRun = true)
    public void initBigQueryExecutor()
    {
        this.bigQuerySqlExecutor = new BigQuerySqlExecutor();
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return BigQueryQueryRunner.createQueryRunner(
                ImmutableMap.of(),
                ImmutableMap.of());
    }

    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_TOPN_PUSHDOWN:
            case SUPPORTS_RENAME_SCHEMA:
            case SUPPORTS_RENAME_TABLE:
            case SUPPORTS_NOT_NULL_CONSTRAINT:
            case SUPPORTS_CREATE_TABLE_WITH_DATA:
            case SUPPORTS_CREATE_TABLE_WITH_TABLE_COMMENT:
            case SUPPORTS_CREATE_TABLE_WITH_COLUMN_COMMENT:
            case SUPPORTS_DELETE:
            case SUPPORTS_INSERT:
            case SUPPORTS_ADD_COLUMN:
            case SUPPORTS_DROP_COLUMN:
            case SUPPORTS_RENAME_COLUMN:
            case SUPPORTS_COMMENT_ON_TABLE:
            case SUPPORTS_COMMENT_ON_COLUMN:
                return false;
            default:
                return super.hasBehavior(connectorBehavior);
        }
    }

    @Test
    @Override
    public void testShowColumns()
    {
        // shippriority column is bigint (not integer) in BigQuery connector
        MaterializedResult actual = computeActual("SHOW COLUMNS FROM orders");

        MaterializedResult expectedParametrizedVarchar = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "date", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "bigint", "", "")
                .row("comment", "varchar", "", "")
                .build();

        assertEquals(actual, expectedParametrizedVarchar);
    }

    @Override
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
                .row("shippriority", "bigint", "", "")
                .row("comment", "varchar", "", "")
                .build();
        MaterializedResult actualColumns = computeActual("DESCRIBE orders");
        assertEquals(actualColumns, expectedColumns);
    }

    @Test(dataProvider = "createTableSupportedTypes")
    public void testCreateTableSupportedType(String createType, String expectedType)
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_create_table_supported_type_" + createType.replaceAll("[^a-zA-Z0-9]", ""), format("(col1 %s)", createType))) {
            assertEquals(
                    computeScalar("SELECT data_type FROM information_schema.columns WHERE table_name = '" + table.getName() + "' AND column_name = 'col1'"),
                    expectedType);
        }
    }

    @DataProvider
    public Object[][] createTableSupportedTypes()
    {
        return new Object[][] {
                {"boolean", "boolean"},
                {"tinyint", "bigint"},
                {"smallint", "bigint"},
                {"integer", "bigint"},
                {"bigint", "bigint"},
                {"double", "double"},
                {"decimal", "decimal(38,9)"},
                {"date", "date"},
                {"time with time zone", "time(6)"},
                {"timestamp(6)", "timestamp(6)"},
                {"timestamp(6) with time zone", "timestamp(6) with time zone"},
                {"char", "varchar"},
                {"char(65535)", "varchar"},
                {"varchar", "varchar"},
                {"varchar(65535)", "varchar"},
                {"varbinary", "varbinary"},
                {"array(bigint)", "array(bigint)"},
                {"row(x bigint, y double)", "row(x bigint, y double)"},
                {"row(x array(bigint))", "row(x array(bigint))"},
        };
    }

    @Test(dataProvider = "createTableUnsupportedTypes")
    public void testCreateTableUnsupportedType(String createType)
    {
        String tableName = format("test_create_table_unsupported_type_%s_%s", createType.replaceAll("[^a-zA-Z0-9]", ""), randomTableSuffix());
        assertQueryFails(format("CREATE TABLE %s (col1 %s)", tableName, createType), "Unsupported column type: " + createType);
        assertUpdate("DROP TABLE IF EXISTS " + tableName);
    }

    @DataProvider
    public Object[][] createTableUnsupportedTypes()
    {
        return new Object[][] {
                {"json"},
                {"uuid"},
                {"ipaddress"},
        };
    }

    @Test
    public void testCreateTableWithRowTypeWithoutField()
    {
        String tableName = "test_row_type_table_" + randomTableSuffix();
        assertQueryFails(
                "CREATE TABLE " + tableName + "(col1 row(int))",
                "\\QROW type does not have field names declared: row(integer)\\E");
    }

    @Test
    public void testCreateTableAlreadyExists()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_create_table_already_exists", "(col1 int)")) {
            assertQueryFails(
                    "CREATE TABLE " + table.getName() + "(col1 int)",
                    "\\Qline 1:1: Table 'bigquery.tpch." + table.getName() + "' already exists\\E");
        }
    }

    @Test
    public void testCreateTableIfNotExists()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_create_table_if_not_exists", "(col1 int)")) {
            assertUpdate("CREATE TABLE IF NOT EXISTS " + table.getName() + "(col1 int)");
        }
    }

    @Test
    @Override
    public void testCreateTableAsSelect()
    {
        assertThatThrownBy(super::testCreateTableAsSelect)
                .hasStackTraceContaining("This connector does not support creating tables with data");
    }

    @Test
    @Override
    public void testCreateTableAsSelectWithUnicode()
    {
        assertThatThrownBy(super::testCreateTableAsSelectWithUnicode)
                .hasStackTraceContaining("This connector does not support creating tables with data");
    }

    @Test
    @Override
    public void testRenameTable()
    {
        // Use CREATE TABLE instead of CREATE TABLE AS statement
        String tableName = "test_rename_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (x int)");

        String renamedTable = "test_rename_new_" + randomTableSuffix();
        assertQueryFails("ALTER TABLE " + tableName + " RENAME TO " + renamedTable, "This connector does not support renaming tables");
    }

    @Test(dataProvider = "testDataMappingSmokeTestDataProvider")
    @Override
    public void testDataMappingSmokeTest(DataMappingTestSetup dataMappingTestSetup)
    {
        assertThatThrownBy(() -> super.testDataMappingSmokeTest(dataMappingTestSetup))
                .hasMessageContaining("This connector does not support creating tables with data");
    }

    @Test(dataProvider = "testCaseSensitiveDataMappingProvider")
    @Override
    public void testCaseSensitiveDataMapping(DataMappingTestSetup dataMappingTestSetup)
    {
        assertThatThrownBy(() -> super.testCaseSensitiveDataMapping(dataMappingTestSetup))
                .hasMessageContaining("This connector does not support creating tables with data");
    }

    @Override
    protected void testColumnName(String columnName, boolean delimited)
    {
        // Override because BigQuery connector doesn't support INSERT statement
        String nameInSql = columnName;
        if (delimited) {
            nameInSql = "\"" + columnName.replace("\"", "\"\"") + "\"";
        }
        String tableName = "test.tcn_" + nameInSql.toLowerCase(ENGLISH).replaceAll("[^a-z0-9]", "") + randomTableSuffix();

        try {
            // TODO test with both CTAS *and* CREATE TABLE + INSERT, since they use different connector API methods.
            assertUpdate("CREATE TABLE " + tableName + "(key varchar(50), " + nameInSql + " varchar(50))");
        }
        catch (RuntimeException e) {
            if (isColumnNameRejected(e, columnName, delimited)) {
                // It is OK if give column name is not allowed and is clearly rejected by the connector.
                return;
            }
            throw e;
        }
        try {
            // Execute INSERT statement in BigQuery
            onBigQuery("INSERT INTO " + tableName + " VALUES ('null value', NULL), ('sample value', 'abc'), ('other value', 'xyz')");

            // SELECT *
            assertQuery("SELECT * FROM " + tableName, "VALUES ('null value', NULL), ('sample value', 'abc'), ('other value', 'xyz')");

            // projection
            assertQuery("SELECT " + nameInSql + " FROM " + tableName, "VALUES (NULL), ('abc'), ('xyz')");

            // predicate
            assertQuery("SELECT key FROM " + tableName + " WHERE " + nameInSql + " IS NULL", "VALUES ('null value')");
            assertQuery("SELECT key FROM " + tableName + " WHERE " + nameInSql + " = 'abc'", "VALUES ('sample value')");
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    @Override
    protected boolean isColumnNameRejected(Exception exception, String columnName, boolean delimited)
    {
        return nullToEmpty(exception.getMessage()).matches(".*(Fields must contain only letters, numbers, and underscores, start with a letter or underscore, and be at most 300 characters long).*");
    }

    @Test
    public void testPartitionDateColumn()
    {
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.partition_date_column", "(value INT64) PARTITION BY _PARTITIONDATE")) {
            // BigQuery doesn't allow omitting column list for ingestion-time partitioned table
            // Using _PARTITIONTIME special column because _PARTITIONDATE is unsupported in INSERT statement
            onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('1960-01-01', 1)", table.getName()));
            onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('2159-12-31', 2)", table.getName()));

            assertThat(query("SELECT value, \"$partition_date\" FROM " + table.getName()))
                    .matches("VALUES (BIGINT '1', DATE '1960-01-01'), (BIGINT '2', DATE '2159-12-31')");

            assertQuery(format("SELECT value FROM %s WHERE \"$partition_date\" = DATE '1960-01-01'", table.getName()), "VALUES 1");
            assertQuery(format("SELECT value FROM %s WHERE \"$partition_date\" = DATE '2159-12-31'", table.getName()), "VALUES 2");

            // Verify DESCRIBE result doesn't have hidden columns
            assertThat(query("DESCRIBE " + table.getName())).projected(0).skippingTypesCheck().matches("VALUES 'value'");
        }
    }

    @Test
    public void testPartitionTimeColumn()
    {
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.partition_time_column", "(value INT64) PARTITION BY DATE_TRUNC(_PARTITIONTIME, HOUR)")) {
            // BigQuery doesn't allow omitting column list for ingestion-time partitioned table
            onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('1960-01-01 00:00:00', 1)", table.getName()));
            onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('2159-12-31 23:00:00', 2)", table.getName())); // Hour and minute must be zero

            assertThat(query("SELECT value, \"$partition_time\" FROM " + table.getName()))
                    .matches("VALUES (BIGINT '1', CAST('1960-01-01 00:00:00 UTC' AS TIMESTAMP(6) WITH TIME ZONE)), (BIGINT '2', CAST('2159-12-31 23:00:00 UTC' AS TIMESTAMP(6) WITH TIME ZONE))");

            assertQuery(format("SELECT value FROM %s WHERE \"$partition_time\" = CAST('1960-01-01 00:00:00 UTC' AS TIMESTAMP(6) WITH TIME ZONE)", table.getName()), "VALUES 1");
            assertQuery(format("SELECT value FROM %s WHERE \"$partition_time\" = CAST('2159-12-31 23:00:00 UTC' AS TIMESTAMP(6) WITH TIME ZONE)", table.getName()), "VALUES 2");

            // Verify DESCRIBE result doesn't have hidden columns
            assertThat(query("DESCRIBE " + table.getName())).projected(0).skippingTypesCheck().matches("VALUES 'value'");
        }
    }

    @Test
    public void testIngestionTimePartitionedTableInvalidValue()
    {
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.invalid_ingestion_time", "(value INT64) PARTITION BY _PARTITIONDATE")) {
            assertThatThrownBy(() -> onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('0001-01-01', 1)", table.getName())))
                    .hasMessageMatching("Cannot set pseudo column for automatic partitioned table.* Supported values are in the range \\[1960-01-01, 2159-12-31]");

            assertThatThrownBy(() -> onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('1959-12-31', 1)", table.getName())))
                    .hasMessageMatching("Cannot set pseudo column for automatic partitioned table.* Supported values are in the range \\[1960-01-01, 2159-12-31]");

            assertThatThrownBy(() -> onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('2160-01-01', 1)", table.getName())))
                    .hasMessageMatching("Cannot set pseudo column for automatic partitioned table.* Supported values are in the range \\[1960-01-01, 2159-12-31]");

            assertThatThrownBy(() -> onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES ('9999-12-31', 1)", table.getName())))
                    .hasMessageMatching("Cannot set pseudo column for automatic partitioned table.* Supported values are in the range \\[1960-01-01, 2159-12-31]");

            assertThatThrownBy(() -> onBigQuery(format("INSERT INTO %s (_PARTITIONTIME, value) VALUES (NULL, 1)", table.getName())))
                    .hasMessageContaining("Cannot set timestamp pseudo column for automatic partitioned table to NULL");
        }
    }

    @Test
    public void testPseudoColumnNotExist()
    {
        // Normal table without partitions
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.non_partitioned_table", "(value INT64, ts TIMESTAMP)")) {
            assertQueryFails("SELECT \"$partition_date\" FROM " + table.getName(), ".* Column '\\$partition_date' cannot be resolved");
            assertQueryFails("SELECT \"$partition_time\" FROM " + table.getName(), ".* Column '\\$partition_time' cannot be resolved");
        }

        // Time-unit partitioned table
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.time_unit_partition", "(value INT64, dt DATE) PARTITION BY dt")) {
            assertQueryFails("SELECT \"$partition_date\" FROM " + table.getName(), ".* Column '\\$partition_date' cannot be resolved");
            assertQueryFails("SELECT \"$partition_time\" FROM " + table.getName(), ".* Column '\\$partition_time' cannot be resolved");
        }

        // Integer-range partitioned table
        try (TestTable table = new TestTable(bigQuerySqlExecutor, "test.integer_range_partition", "(value INT64, dt DATE) PARTITION BY RANGE_BUCKET(value, GENERATE_ARRAY(0, 100, 10))")) {
            assertQueryFails("SELECT \"$partition_date\" FROM " + table.getName(), ".* Column '\\$partition_date' cannot be resolved");
            assertQueryFails("SELECT \"$partition_time\" FROM " + table.getName(), ".* Column '\\$partition_time' cannot be resolved");
        }
    }

    @Test
    public void testSelectFromHourlyPartitionedTable()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.hourly_partitioned",
                "(value INT64, ts TIMESTAMP) PARTITION BY TIMESTAMP_TRUNC(ts, HOUR)",
                List.of("1000, '2018-01-01 10:00:00'"))) {
            assertQuery("SELECT COUNT(1) FROM " + table.getName(), "VALUES 1");
        }
    }

    @Test
    public void testSelectFromYearlyPartitionedTable()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.yearly_partitioned",
                "(value INT64, ts TIMESTAMP) PARTITION BY TIMESTAMP_TRUNC(ts, YEAR)",
                List.of("1000, '2018-01-01 10:00:00'"))) {
            assertQuery("SELECT COUNT(1) FROM " + table.getName(), "VALUES 1");
        }
    }

    @Test(description = "regression test for https://github.com/trinodb/trino/issues/7784")
    public void testSelectWithSingleQuoteInWhereClause()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.select_with_single_quote",
                "(col INT64, val STRING)",
                List.of("1, 'escape\\'single quote'"))) {
            assertQuery("SELECT val FROM " + table.getName() + " WHERE val = 'escape''single quote'", "VALUES 'escape''single quote'");
        }
    }

    @Test(description = "regression test for https://github.com/trinodb/trino/issues/5618")
    public void testPredicatePushdownPrunnedColumns()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.predicate_pushdown_prunned_columns",
                "(a INT64, b INT64, c INT64)",
                List.of("1, 2, 3"))) {
            assertQuery(
                    "SELECT 1 FROM " + table.getName() + " WHERE " +
                            "    ((NULL IS NULL) OR a = 100) AND " +
                            "    b = 2",
                    "VALUES (1)");
        }
    }

    @Test(description = "regression test for https://github.com/trinodb/trino/issues/5635")
    public void testCountAggregationView()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.count_aggregation_table",
                "(a INT64, b INT64, c INT64)",
                List.of("1, 2, 3", "4, 5, 6"));
                TestView view = new TestView(bigQuerySqlExecutor, "test.count_aggregation_view", "SELECT * FROM " + table.getName())) {
            assertQuery("SELECT count(*) FROM " + view.getName(), "VALUES (2)");
            assertQuery("SELECT count(*) FROM " + view.getName() + " WHERE a = 1", "VALUES (1)");
            assertQuery("SELECT count(a) FROM " + view.getName() + " WHERE b = 2", "VALUES (1)");
        }
    }

    /**
     * regression test for https://github.com/trinodb/trino/issues/6696
     */
    @Test
    public void testRepeatCountAggregationView()
    {
        try (TestView view = new TestView(bigQuerySqlExecutor, "test.repeat_count_aggregation_view", "SELECT 1 AS col1")) {
            assertQuery("SELECT count(*) FROM " + view.getName(), "VALUES (1)");
            assertQuery("SELECT count(*) FROM " + view.getName(), "VALUES (1)");
        }
    }

    /**
     * https://github.com/trinodb/trino/issues/8183
     */
    @Test
    public void testColumnPositionMismatch()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test.test_column_position_mismatch", "(c_varchar VARCHAR, c_int INT, c_date DATE)")) {
            onBigQuery("INSERT INTO " + table.getName() + " VALUES ('a', 1, '2021-01-01')");
            // Adding a CAST makes BigQuery return columns in a different order
            assertQuery("SELECT c_varchar, CAST(c_int AS SMALLINT), c_date FROM " + table.getName(), "VALUES ('a', 1, '2021-01-01')");
        }
    }

    @Test
    public void testViewDefinitionSystemTable()
    {
        String schemaName = "test";
        String tableName = "views_system_table_base_" + randomTableSuffix();
        String viewName = "views_system_table_view_" + randomTableSuffix();

        onBigQuery(format("CREATE TABLE %s.%s (a INT64, b INT64, c INT64)", schemaName, tableName));
        onBigQuery(format("CREATE VIEW %s.%s AS SELECT * FROM %s.%s", schemaName, viewName, schemaName, tableName));

        assertEquals(
                computeScalar(format("SELECT * FROM %s.\"%s$view_definition\"", schemaName, viewName)),
                format("SELECT * FROM %s.%s", schemaName, tableName));

        assertQueryFails(
                format("SELECT * FROM %s.\"%s$view_definition\"", schemaName, tableName),
                format("Table '%s.%s\\$view_definition' not found", schemaName, tableName));

        onBigQuery(format("DROP TABLE %s.%s", schemaName, tableName));
        onBigQuery(format("DROP VIEW %s.%s", schemaName, viewName));
    }

    @Override
    public void testShowCreateTable()
    {
        assertThat((String) computeActual("SHOW CREATE TABLE orders").getOnlyValue())
                .isEqualTo("CREATE TABLE bigquery.tpch.orders (\n" +
                        "   orderkey bigint NOT NULL,\n" +
                        "   custkey bigint NOT NULL,\n" +
                        "   orderstatus varchar NOT NULL,\n" +
                        "   totalprice double NOT NULL,\n" +
                        "   orderdate date NOT NULL,\n" +
                        "   orderpriority varchar NOT NULL,\n" +
                        "   clerk varchar NOT NULL,\n" +
                        "   shippriority bigint NOT NULL,\n" +
                        "   comment varchar NOT NULL\n" +
                        ")");
    }

    @Test
    @Override
    public void testCharVarcharComparison()
    {
        // BigQuery doesn't have char type
        assertThatThrownBy(super::testCharVarcharComparison)
                .hasMessage("This connector does not support creating tables with data");
    }

    @Test
    @Override
    public void testVarcharCharComparison()
    {
        // Use BigQuery SQL executor because the connector doesn't support INSERT statement
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.test_varchar_char",
                "(k int, v string(3))",
                ImmutableList.of(
                        "-1, NULL",
                        "0, ''",
                        "1, ' '",
                        "2, '  '",
                        "3, '   '",
                        "4, 'x'",
                        "5, 'x '",
                        "6, 'x  '"))) {
            assertQuery(
                    "SELECT k, v FROM " + table.getName() + " WHERE v = CAST('  ' AS char(2))",
                    // The 3-spaces value is included because both sides of the comparison are coerced to char(3)
                    "VALUES (0, ''), (1, ' '), (2, '  '), (3, '   ')");

            // value that's not all-spaces
            assertQuery(
                    "SELECT k, v FROM " + table.getName() + " WHERE v = CAST('x ' AS char(2))",
                    // The 3-spaces value is included because both sides of the comparison are coerced to char(3)
                    "VALUES (4, 'x'), (5, 'x '), (6, 'x  ')");
        }
    }

    @Test
    public void testSkipUnsupportedType()
    {
        try (TestTable table = new TestTable(
                bigQuerySqlExecutor,
                "test.test_skip_unsupported_type",
                "(a INT64, unsupported BIGNUMERIC, b INT64)",
                List.of("1, 999, 2"))) {
            assertQuery("SELECT * FROM " + table.getName(), "VALUES (1, 2)");
            assertThat((String) computeActual("SHOW CREATE TABLE " + table.getName()).getOnlyValue())
                    .isEqualTo("CREATE TABLE bigquery." + table.getName() + " (\n" +
                            "   a bigint,\n" +
                            "   b bigint\n" +
                            ")");
        }
    }

    @Test
    @Override
    public void testDateYearOfEraPredicate()
    {
        // Override because the connector throws an exception instead of an empty result when the value is out of supported range
        assertQuery("SELECT orderdate FROM orders WHERE orderdate = DATE '1997-09-14'", "VALUES DATE '1997-09-14'");
        assertThatThrownBy(() -> query("SELECT * FROM orders WHERE orderdate = DATE '-1996-09-14'"))
                .hasMessageMatching(".*Row filter for .* is invalid\\. Filter is '\\(`orderdate` = '-1996-09-14'\\)'");
    }

    @Test
    @Override
    public void testSymbolAliasing()
    {
        // Create table in BigQuery because the connector doesn't support CREATE TABLE AS SELECT statement
        String tableName = "test.test_symbol_aliasing" + randomTableSuffix();
        onBigQuery("CREATE TABLE " + tableName + " AS SELECT 1 foo_1, 2 foo_2_4");
        assertQuery("SELECT foo_1, foo_2_4 FROM " + tableName, "SELECT 1, 2");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testBigQueryMaterializedView()
    {
        String materializedView = "test_materialized_view" + randomTableSuffix();
        try {
            onBigQuery("CREATE MATERIALIZED VIEW test." + materializedView + " AS SELECT count(1) AS cnt FROM tpch.region");
            assertQuery("SELECT table_type FROM information_schema.tables WHERE table_schema = 'test' AND table_name = '" + materializedView + "'", "VALUES 'BASE TABLE'");

            assertQuery("DESCRIBE test." + materializedView, "VALUES ('cnt', 'bigint', '', '')");
            assertQuery("SELECT * FROM test." + materializedView, "VALUES 5");

            assertUpdate("DROP TABLE test." + materializedView);
            assertQueryReturnsEmptyResult("SELECT * FROM information_schema.tables WHERE table_schema = 'test' AND table_name = '" + materializedView + "'");
        }
        finally {
            onBigQuery("DROP MATERIALIZED VIEW IF EXISTS test." + materializedView);
        }
    }

    @Test
    public void testBigQuerySnapshotTable()
    {
        String snapshotTable = "test_snapshot" + randomTableSuffix();
        try {
            onBigQuery("CREATE SNAPSHOT TABLE test." + snapshotTable + " CLONE tpch.region");
            assertQuery("SELECT table_type FROM information_schema.tables WHERE table_schema = 'test' AND table_name = '" + snapshotTable + "'", "VALUES 'BASE TABLE'");

            assertThat(query("DESCRIBE test." + snapshotTable)).matches("DESCRIBE tpch.region");
            assertThat(query("SELECT * FROM test." + snapshotTable)).matches("SELECT * FROM tpch.region");

            assertUpdate("DROP TABLE test." + snapshotTable);
            assertQueryReturnsEmptyResult("SELECT * FROM information_schema.tables WHERE table_schema = 'test' AND table_name = '" + snapshotTable + "'");
        }
        finally {
            onBigQuery("DROP SNAPSHOT TABLE IF EXISTS test." + snapshotTable);
        }
    }

    @Test
    public void testQueryCache()
    {
        Session queryResultsCacheSession = Session.builder(getSession())
                .setCatalogSessionProperty("bigquery", "query_results_cache_enabled", "true")
                .build();
        Session createNeverDisposition = Session.builder(getSession())
                .setCatalogSessionProperty("bigquery", "query_results_cache_enabled", "true")
                .setCatalogSessionProperty("bigquery", "create_disposition_type", "create_never")
                .build();

        String materializedView = "test_materialized_view" + randomTableSuffix();
        try {
            onBigQuery("CREATE MATERIALIZED VIEW test." + materializedView + " AS SELECT count(1) AS cnt FROM tpch.region");

            // Verify query cache is empty
            assertThatThrownBy(() -> query(createNeverDisposition, "SELECT * FROM test." + materializedView))
                    .hasMessageContaining("Not found");
            // Populate cache and verify it
            assertQuery(queryResultsCacheSession, "SELECT * FROM test." + materializedView, "VALUES 5");
            assertQuery(createNeverDisposition, "SELECT * FROM test." + materializedView, "VALUES 5");

            assertUpdate("DROP TABLE test." + materializedView);
        }
        finally {
            onBigQuery("DROP MATERIALIZED VIEW IF EXISTS test." + materializedView);
        }
    }

    @Test
    public void testWildcardTable()
    {
        String suffix = randomTableSuffix();
        String firstTable = format("test_wildcard_%s_1", suffix);
        String secondTable = format("test_wildcard_%s_2", suffix);
        String wildcardTable = format("test_wildcard_%s_*", suffix);
        try {
            onBigQuery("CREATE TABLE test." + firstTable + " AS SELECT 1 AS value");
            onBigQuery("CREATE TABLE test." + secondTable + " AS SELECT 2 AS value");

            assertQuery("DESCRIBE test.\"" + wildcardTable + "\"", "VALUES ('value', 'bigint', '', '')");
            assertQuery("SELECT * FROM test.\"" + wildcardTable + "\"", "VALUES (1), (2)");

            // Unsupported operations
            assertQueryFails("DROP TABLE test.\"" + wildcardTable + "\"", "This connector does not support dropping wildcard tables");
            assertQueryFails("INSERT INTO test.\"" + wildcardTable + "\" VALUES (1)", "This connector does not support inserts");
            assertQueryFails("ALTER TABLE test.\"" + wildcardTable + "\" ADD COLUMN new_column INT", "This connector does not support adding columns");
            assertQueryFails("ALTER TABLE test.\"" + wildcardTable + "\" RENAME TO test.new_wildcard_table", "This connector does not support renaming tables");
        }
        finally {
            onBigQuery("DROP TABLE IF EXISTS test." + firstTable);
            onBigQuery("DROP TABLE IF EXISTS test." + secondTable);
        }
    }

    @Test
    public void testWildcardTableWithDifferentColumnDefinition()
    {
        String suffix = randomTableSuffix();
        String firstTable = format("test_invalid_wildcard_%s_1", suffix);
        String secondTable = format("test_invalid_wildcard_%s_2", suffix);
        String wildcardTable = format("test_invalid_wildcard_%s_*", suffix);
        try {
            onBigQuery("CREATE TABLE test." + firstTable + " AS SELECT 1 AS value");
            onBigQuery("CREATE TABLE test." + secondTable + " AS SELECT 'string' AS value");

            assertQuery("DESCRIBE test.\"" + wildcardTable + "\"", "VALUES ('value', 'varchar', '', '')");

            assertThatThrownBy(() -> query("SELECT * FROM test.\"" + wildcardTable + "\""))
                    .hasMessageContaining("Cannot read field of type INT64 as STRING Field: value");
        }
        finally {
            onBigQuery("DROP TABLE IF EXISTS test." + firstTable);
            onBigQuery("DROP TABLE IF EXISTS test." + secondTable);
        }
    }

    @Test
    public void testMissingWildcardTable()
    {
        assertThatThrownBy(() -> query("SELECT * FROM test.\"test_missing_wildcard_table_*\""))
                .hasMessageEndingWith("does not match any table.");
    }

    private void onBigQuery(@Language("SQL") String sql)
    {
        bigQuerySqlExecutor.execute(sql);
    }
}
