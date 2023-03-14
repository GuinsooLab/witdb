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
package io.trino.plugin.ignite;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.plugin.jdbc.BaseJdbcConnectorTest;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.SqlExecutor;
import io.trino.testing.sql.TestTable;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Strings.nullToEmpty;
import static io.trino.plugin.ignite.IgniteQueryRunner.createIgniteQueryRunner;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertFalse;

public class TestIgniteConnectorTest
        extends BaseJdbcConnectorTest
{
    private TestingIgniteServer igniteServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.igniteServer = closeAfterClass(TestingIgniteServer.getInstance()).get();
        return createIgniteQueryRunner(
                igniteServer,
                ImmutableMap.of(),
                ImmutableMap.of(),
                REQUIRED_TPCH_TABLES);
    }

    @Override
    protected SqlExecutor onRemoteDatabase()
    {
        return igniteServer::execute;
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_DELETE:
            case SUPPORTS_TRUNCATE:
                return false;

            case SUPPORTS_CREATE_SCHEMA:
            case SUPPORTS_RENAME_TABLE:
            case SUPPORTS_RENAME_COLUMN:
            case SUPPORTS_COMMENT_ON_TABLE:
            case SUPPORTS_COMMENT_ON_COLUMN:
            case SUPPORTS_CREATE_TABLE_WITH_TABLE_COMMENT:
            case SUPPORTS_CREATE_TABLE_WITH_COLUMN_COMMENT:
                return false;

            case SUPPORTS_DROP_COLUMN:
                return true;

            // https://issues.apache.org/jira/browse/IGNITE-18829
            // Add not null column to non-empty table Ignite doesn't give the default value
            case SUPPORTS_ADD_COLUMN:
            case SUPPORTS_SET_COLUMN_TYPE:
                return false;

            case SUPPORTS_ARRAY:
            case SUPPORTS_ROW_TYPE:
            case SUPPORTS_NEGATIVE_DATE:
                return false;

            case SUPPORTS_PREDICATE_EXPRESSION_PUSHDOWN_WITH_LIKE:
            case SUPPORTS_AGGREGATION_PUSHDOWN_COUNT_DISTINCT:
            case SUPPORTS_TOPN_PUSHDOWN_WITH_VARCHAR:
            case SUPPORTS_NOT_NULL_CONSTRAINT:
                return true;

            default:
                return super.hasBehavior(connectorBehavior);
        }
    }

    @Test
    public void testDatabaseMetadataSearchEscapedWildCardCharacters()
    {
        // wildcard characters on schema name
        assertQuerySucceeds("SHOW TABLES FROM public");
        assertQueryFails("SHOW TABLES FROM \"publi_\"", ".*Schema 'publi_' does not exist");
        assertQueryFails("SHOW TABLES FROM \"pu%lic\"", ".*Schema 'pu%lic' does not exist");

        String tableNameSuffix = randomNameSuffix();
        String normalTableName = "testxsearch" + tableNameSuffix;
        String underscoreTableName = "\"" + "test_search" + tableNameSuffix + "\"";
        String percentTableName = "\"" + "test%search" + tableNameSuffix + "\"";
        try {
            assertUpdate("CREATE TABLE " + normalTableName + "(a int, b int, c int) WITH (primary_key = ARRAY['a'])");
            assertUpdate("CREATE TABLE " + underscoreTableName + "(a int, b int, c int) WITH (primary_key = ARRAY['b'])");
            assertUpdate("CREATE TABLE " + percentTableName + " (a int, b int, c int) WITH (primary_key = ARRAY['c'])");

            // wildcard characters on table name
            assertThat((String) computeScalar("SHOW CREATE TABLE " + normalTableName)).contains("primary_key = ARRAY['a']");
            assertThat((String) computeScalar("SHOW CREATE TABLE " + underscoreTableName)).contains("primary_key = ARRAY['b']");
            assertThat((String) computeScalar("SHOW CREATE TABLE " + percentTableName)).contains("primary_key = ARRAY['c']");
            assertQueryFails("SHOW CREATE TABLE " + "\"test%\"", ".*Table 'ignite.public.test%' does not exist");
            assertQueryFails("SHOW COLUMNS FROM " + "\"test%\"", ".*Table 'ignite.public.test%' does not exist");
        }
        finally {
            assertUpdate("DROP TABLE IF EXISTS " + normalTableName);
            assertUpdate("DROP TABLE IF EXISTS " + underscoreTableName);
            assertUpdate("DROP TABLE IF EXISTS " + percentTableName);
        }
    }

    @Test
    public void testCreateTableSqlInjection()
    {
        assertUpdate("CREATE TABLE a1 (id int, a varchar)");
        assertUpdate("CREATE TABLE x2 (id int, a varchar)");
        assertUpdate("CREATE TABLE x3 (id int, a varchar)");
        assertQuery("SHOW TABLES IN ignite.public LIKE 'a%'", "VALUES ('a1')");

        // injection on table name
        assertUpdate("CREATE TABLE \"test (c1 int not null, c2 int, primary key(c1)); DROP TABLE public.a1;\" (c1 date)");
        assertQuery("SHOW TABLES IN ignite.public LIKE 'a%'", "VALUES ('a1')");

        // injection on column name
        assertUpdate("CREATE TABLE test (\"test (c1 int not null, c2 int, primary key(c1)); DROP TABLE public.a1;\" date)");
        assertQuery("SHOW TABLES IN ignite.public LIKE 'a%'", "VALUES ('a1')");
    }

    @Test
    public void testCreateTableWithCommaPropertyColumn()
    {
        // Test that Ignite not support column name contains quote
        String tableWithQuote = "create_table_with_unsupported_quote_column";
        String tableDefinitionWithQuote = "(`a\"b` bigint primary key, c varchar)";
        assertThatThrownBy(() -> onRemoteDatabase().execute("CREATE TABLE " + tableWithQuote + tableDefinitionWithQuote))
                .getRootCause()
                .hasMessageContaining("Failed to parse query");

        // Test the property column with comma
        String catalog = getSession().getCatalog().orElseThrow();
        String schema = getSession().getSchema().orElseThrow();
        try (TestTable testTable = new TestTable(
                onRemoteDatabase(),
                "create_table_with_comma_column",
                "(`a,b` bigint primary key, `c,d` bigint, `x` varchar(79))",
                List.of("1, 1, 'a'", "2, 2, 'b'", "3, 3, null"))) {
            String pattern = "CREATE TABLE %s.%s.%s (\n" +
                    "   \"a,b\" bigint,\n" +
                    "   \"c,d\" bigint,\n" +
                    "   x varchar(79)\n" +
                    ")\n" +
                    "WITH (\n" +
                    "   primary_key = ARRAY['a,b']\n" +
                    ")";
            String tableName = testTable.getName();
            assertQuery("SELECT \"a,b\" FROM " + tableName + " where \"a,b\" < 2", "values (1)");
            assertQuery("SELECT \"a,b\" FROM " + tableName + " where \"a,b\" > 1", "values (2), (3)");

            assertThat((String) computeActual("SHOW CREATE TABLE " + tableName).getOnlyValue())
                    .isEqualTo(format(pattern, catalog, schema, tableName));
        }
    }

    @Test
    public void testCreateTableWithAllProperties()
    {
        String tableWithAllProperties = "test_create_with_all_properties";
        assertUpdate("CREATE TABLE IF NOT EXISTS " + tableWithAllProperties + " (a bigint, b double, c varchar, d date) WITH (primary_key = ARRAY['a', 'b'])");
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        return new TestTable(
                onRemoteDatabase(),
                "public.tbl",
                "(col_required bigint," +
                        "col_nullable bigint," +
                        "col_default bigint DEFAULT 43," +
                        "col_nonnull_default bigint DEFAULT 42," +
                        "col_required2 bigint NOT NULL, " +
                        "dummy_id varchar NOT NULL primary key)");
    }

    @Override
    public void testShowCreateTable()
    {
        assertThat(computeActual("SHOW CREATE TABLE orders").getOnlyValue())
                .isEqualTo("CREATE TABLE ignite.public.orders (\n" +
                        "   orderkey bigint,\n" +
                        "   custkey bigint,\n" +
                        "   orderstatus varchar(1),\n" +
                        "   totalprice double,\n" +
                        "   orderdate date,\n" +
                        "   orderpriority varchar(15),\n" +
                        "   clerk varchar(15),\n" +
                        "   shippriority integer,\n" +
                        "   comment varchar(79)\n" +
                        ")\n" +
                        "WITH (\n" +
                        "   primary_key = ARRAY['dummy_id']\n" +
                        ")");
    }

    @Test
    public void testAvgDecimalExceedingSupportedPrecision()
    {
        try (TestTable testTable = new TestTable(
                getQueryRunner()::execute,
                "test_avg_decimal_exceeding_supported_precision",
                "(a decimal(38, 38), b bigint)",
                List.of(
                        "CAST ('0.12345671234567123456712345671234567121' AS decimal(38, 38)), 1",
                        "CAST ('0.12345671234567123456712345671234567122' AS decimal(38, 38)), 2",
                        "CAST ('0.12345671234567123456712345671234567123' AS decimal(38, 38)), 3",
                        "CAST ('0.12345671234567123456712345671234567124' AS decimal(38, 38)), 4",
                        "CAST ('0.12345671234567123456712345671234567125' AS decimal(38, 38)), 5",
                        "CAST ('0.12345671234567123456712345671234567126' AS decimal(38, 38)), 6",
                        "CAST ('0.12345671234567123456712345671234567127' AS decimal(38, 38)), 7"))) {
            assertThat(query("SELECT avg(a) avg_a  FROM " + testTable.getName()))
                    .matches("SELECT CAST ('0.12345671234567123456712345671234567124' AS decimal(38, 38))");
            assertThat(query(format("SELECT avg(a) avg_a FROM %s WHERE b <= 2", testTable.getName())))
                    .matches("SELECT CAST ('0.123456712345671234567123456712345671215' AS decimal(38, 38))");
        }
    }

    @Override
    protected TestTable createAggregationTestTable(String name, List<String> rows)
    {
        return new TestTable(
                onRemoteDatabase(),
                name,
                "(short_decimal decimal(9, 3), long_decimal decimal(30, 10), t_double double, a_bigint bigint primary key)",
                rows);
    }

    @Override
    protected TestTable createTableWithDoubleAndRealColumns(String name, List<String> rows)
    {
        return new TestTable(
                onRemoteDatabase(),
                name,
                "(t_double double, u_double double, v_real real, w_real real primary key)",
                rows);
    }

    @Override
    protected Optional<String> filterColumnNameTestData(String columnName)
    {
        // https://issues.apache.org/jira/browse/IGNITE-18102
        if ("a.dot".equals(columnName)) {
            return Optional.empty();
        }

        return Optional.of(columnName);
    }

    @Override
    protected boolean isColumnNameRejected(Exception exception, String columnName, boolean delimited)
    {
        String errorMessage = nullToEmpty(exception.getMessage());
        if (columnName.equals("a\"quote")) {
            return errorMessage.contains("Failed to parse query.");
        }

        return errorMessage.contains("Failed to complete exchange process");
    }

    @Override
    public void testNativeQuerySimple()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        assertQueryFails("SELECT * FROM TABLE(system.query(query => 'SELECT 1'))", "line 1:21: Table function system.query not registered");
    }

    @Override
    public void testNativeQueryParameters()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        Session session = Session.builder(getSession())
                .addPreparedStatement("my_query_simple", "SELECT * FROM TABLE(system.query(query => ?))")
                .addPreparedStatement("my_query", "SELECT * FROM TABLE(system.query(query => format('SELECT %s FROM %s', ?, ?)))")
                .build();
        assertQueryFails(session, "EXECUTE my_query_simple USING 'SELECT 1 a'", "line 1:21: Table function system.query not registered");
        assertQueryFails(session, "EXECUTE my_query USING 'a', '(SELECT 2 a) t'", "line 1:21: Table function system.query not registered");
    }

    @Override
    public void testNativeQuerySelectFromNation()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        assertQueryFails(
                format("SELECT * FROM TABLE(system.query(query => 'SELECT name FROM %s.nation WHERE nationkey = 0'))", getSession().getSchema().orElseThrow()),
                "line 1:21: Table function system.query not registered");
    }

    @Override
    public void testNativeQuerySelectFromTestTable()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        try (TestTable testTable = simpleTable()) {
            assertQueryFails(
                    format("SELECT * FROM TABLE(system.query(query => 'SELECT * FROM %s'))", testTable.getName()),
                    "line 1:21: Table function system.query not registered");
        }
    }

    @Override
    public void testNativeQuerySelectUnsupportedType()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        try (TestTable testTable = createTableWithUnsupportedColumn()) {
            String unqualifiedTableName = testTable.getName().replaceAll("^\\w+\\.", "");
            // Check that column 'two' is not supported.
            assertQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '" + unqualifiedTableName + "'", "VALUES 'one', 'three'");
            assertUpdate("INSERT INTO " + testTable.getName() + " (one, three) VALUES (123, 'test')", 1);
            assertThatThrownBy(() -> query(format("SELECT * FROM TABLE(system.query(query => 'SELECT * FROM %s'))", testTable.getName())))
                    .hasMessage("line 1:21: Table function system.query not registered");
        }
    }

    @Override
    public void testNativeQueryCreateStatement()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        assertFalse(getQueryRunner().tableExists(getSession(), "numbers"));
        assertThatThrownBy(() -> query("SELECT * FROM TABLE(system.query(query => 'CREATE TABLE numbers(n INTEGER)'))"))
                .hasMessage("line 1:21: Table function system.query not registered");
        assertFalse(getQueryRunner().tableExists(getSession(), "numbers"));
    }

    @Override
    public void testNativeQueryInsertStatementTableDoesNotExist()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        assertFalse(getQueryRunner().tableExists(getSession(), "non_existent_table"));
        assertThatThrownBy(() -> query("SELECT * FROM TABLE(system.query(query => 'INSERT INTO non_existent_table VALUES (1)'))"))
                .hasMessage("line 1:21: Table function system.query not registered");
    }

    @Override
    public void testNativeQueryInsertStatementTableExists()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        try (TestTable testTable = simpleTable()) {
            assertThatThrownBy(() -> query(format("SELECT * FROM TABLE(system.query(query => 'INSERT INTO %s VALUES (3, 4)'))", testTable.getName())))
                    .hasMessage("line 1:21: Table function system.query not registered");
            assertQuery("SELECT * FROM " + testTable.getName(), "VALUES (1, 1), (2, 2)");
        }
    }

    @Override
    protected TestTable simpleTable()
    {
        return new TestTable(onRemoteDatabase(), format("%s.simple_table", getSession().getSchema().orElseThrow()), "(col BIGINT, id bigint primary key)", ImmutableList.of("1, 1", "2, 2"));
    }

    @Override
    public void testNativeQueryIncorrectSyntax()
    {
        // table function disabled for Ignite, because it doesn't provide ResultSetMetaData, so the result relation type cannot be determined
        assertThatThrownBy(() -> query("SELECT * FROM TABLE(system.query(query => 'some wrong syntax'))"))
                .hasMessage("line 1:21: Table function system.query not registered");
    }

    @Override
    public void testCharVarcharComparison()
    {
        // Ignite will map char to varchar, skip
        throw new SkipException("Ignite map char to varchar, skip test");
    }

    @Override
    protected String errorMessageForInsertIntoNotNullColumn(String columnName)
    {
        return format("Failed to insert data: Null value is not allowed for column '%s'", columnName.toUpperCase(Locale.ENGLISH));
    }

    @Override
    public void testCharTrailingSpace()
    {
        throw new SkipException("Ignite not support char trailing space");
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        switch (dataMappingTestSetup.getTrinoTypeName()) {
            case "date":
                // Ignite doesn't support these days
                if (dataMappingTestSetup.getSampleValueLiteral().equals("DATE '0001-01-01'") || dataMappingTestSetup.getSampleValueLiteral().equals("DATE '1582-10-05'")) {
                    return Optional.empty();
                }
                break;

            case "time":
            case "time(6)":
            case "timestamp":
            case "timestamp(6)":
            case "timestamp(3) with time zone":
            case "timestamp(6) with time zone":
                return Optional.of(dataMappingTestSetup.asUnsupported());
        }

        return Optional.of(dataMappingTestSetup);
    }

    @Override
    public void testDateYearOfEraPredicate()
    {
        // Override because the connector throws an exception instead of an empty result when the value is out of supported range
        assertQuery("SELECT orderdate FROM orders WHERE orderdate = DATE '1997-09-14'", "VALUES DATE '1997-09-14'");
        assertQueryFails(
                "SELECT * FROM orders WHERE orderdate = DATE '-1996-09-14'",
                errorMessageForDateOutOrRange("-1996-09-14"));
    }

    @Override
    protected String errorMessageForInsertNegativeDate(String date)
    {
        return errorMessageForDateOutOrRange(date);
    }

    @Override
    protected String errorMessageForCreateTableAsSelectNegativeDate(String date)
    {
        return errorMessageForDateOutOrRange(date);
    }

    private String errorMessageForDateOutOrRange(String date)
    {
        return "Date must be between 1970-01-01 and 9999-12-31 in Ignite: " + date;
    }
}
