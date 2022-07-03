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
package io.trino.verifier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.Duration;
import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.CreateTable;
import io.trino.sql.tree.CreateTableAsSelect;
import io.trino.sql.tree.DropTable;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.Insert;
import io.trino.sql.tree.LongLiteral;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.SingleColumn;
import io.trino.sql.tree.Table;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.sql.QueryUtil.identifier;
import static io.trino.sql.QueryUtil.selectList;
import static io.trino.sql.QueryUtil.simpleQuery;
import static io.trino.verifier.QueryType.READ;
import static io.trino.verifier.VerifyCommand.statementToQueryType;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestShadowing
{
    private static final String CATALOG = "TEST_REWRITE";
    private static final String SCHEMA = "PUBLIC";
    private static final String URL = "jdbc:h2:mem:" + CATALOG;
    private static final ParsingOptions PARSING_OPTIONS = new ParsingOptions();

    private final Handle handle;

    public TestShadowing()
    {
        handle = Jdbi.open(URL);
    }

    @AfterClass(alwaysRun = true)
    public void close()
    {
        handle.close();
    }

    @Test
    public void testCreateTableAsSelect()
            throws Exception
    {
        handle.execute("CREATE TABLE \"my_test_table\" (column1 BIGINT, column2 DOUBLE)");
        SqlParser parser = new SqlParser();
        Query query = new Query(CATALOG, SCHEMA, ImmutableList.of(), "CREATE TABLE my_test_table AS SELECT 1 column1, CAST('2.0' AS DOUBLE) column2 LIMIT 1", ImmutableList.of(), null, null, ImmutableMap.of());
        QueryRewriter rewriter = new QueryRewriter(parser, URL, QualifiedName.of("tmp_"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 1, new Duration(10, SECONDS));
        Query rewrittenQuery = rewriter.shadowQuery(query);
        assertEquals(rewrittenQuery.getPreQueries().size(), 1);
        assertEquals(rewrittenQuery.getPostQueries().size(), 1);

        CreateTableAsSelect createTableAs = (CreateTableAsSelect) parser.createStatement(rewrittenQuery.getPreQueries().get(0), PARSING_OPTIONS);
        assertEquals(createTableAs.getName().getParts().size(), 1);
        assertTrue(createTableAs.getName().getSuffix().startsWith("tmp_"));
        assertFalse(createTableAs.getName().getSuffix().contains("my_test_table"));

        assertEquals(statementToQueryType(parser, rewrittenQuery.getQuery()), READ);

        Table table = new Table(createTableAs.getName());
        SingleColumn column1 = new SingleColumn(new FunctionCall(QualifiedName.of("checksum"), ImmutableList.of(new Identifier("COLUMN1"))));
        SingleColumn column2 = new SingleColumn(new FunctionCall(QualifiedName.of("checksum"), ImmutableList.of(new FunctionCall(QualifiedName.of("round"), ImmutableList.of(new Identifier("COLUMN2"), new LongLiteral("1"))))));
        assertEquals(parser.createStatement(rewrittenQuery.getQuery(), PARSING_OPTIONS), simpleQuery(selectList(column1, column2), table));

        assertEquals(parser.createStatement(rewrittenQuery.getPostQueries().get(0), PARSING_OPTIONS), new DropTable(createTableAs.getName(), true));
    }

    @Test
    public void testCreateTableAsSelectDifferentCatalog()
            throws Exception
    {
        handle.execute("CREATE TABLE \"my_test_table2\" (column1 BIGINT, column2 DOUBLE)");
        SqlParser parser = new SqlParser();
        Query query = new Query(CATALOG, SCHEMA, ImmutableList.of(), "CREATE TABLE public.my_test_table2 AS SELECT 1 column1, 2E0 column2", ImmutableList.of(), null, null, ImmutableMap.of());
        QueryRewriter rewriter = new QueryRewriter(parser, URL, QualifiedName.of("other_catalog", "other_schema", "tmp_"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 1, new Duration(10, SECONDS));
        Query rewrittenQuery = rewriter.shadowQuery(query);
        assertEquals(rewrittenQuery.getPreQueries().size(), 1);
        CreateTableAsSelect createTableAs = (CreateTableAsSelect) parser.createStatement(rewrittenQuery.getPreQueries().get(0), PARSING_OPTIONS);
        assertEquals(createTableAs.getName().getParts().size(), 3);
        assertEquals(createTableAs.getName().getPrefix().get(), QualifiedName.of("other_catalog", "other_schema"));
        assertTrue(createTableAs.getName().getSuffix().startsWith("tmp_"));
        assertFalse(createTableAs.getName().getSuffix().contains("my_test_table"));
    }

    @Test
    public void testInsert()
            throws Exception
    {
        handle.execute("CREATE TABLE \"test_insert_table\" (a BIGINT, b DOUBLE, c VARCHAR)");
        SqlParser parser = new SqlParser();
        Query query = new Query(CATALOG, SCHEMA, ImmutableList.of(), "INSERT INTO test_insert_table (b, a, c) values (1.1, 1, 'a'), (2.0, 2, 'b'), (3.1, 3, 'c')", ImmutableList.of(), null, null, ImmutableMap.of());
        QueryRewriter rewriter = new QueryRewriter(parser, URL, QualifiedName.of("other_catalog", "other_schema", "tmp_"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 1, new Duration(10, SECONDS));
        Query rewrittenQuery = rewriter.shadowQuery(query);

        assertEquals(rewrittenQuery.getPreQueries().size(), 2);
        CreateTable createTable = (CreateTable) parser.createStatement(rewrittenQuery.getPreQueries().get(0), PARSING_OPTIONS);
        assertEquals(createTable.getName().getParts().size(), 3);
        assertEquals(createTable.getName().getPrefix().get(), QualifiedName.of("other_catalog", "other_schema"));
        assertTrue(createTable.getName().getSuffix().startsWith("tmp_"));
        assertFalse(createTable.getName().getSuffix().contains("test_insert_table"));

        Insert insert = (Insert) parser.createStatement(rewrittenQuery.getPreQueries().get(1), PARSING_OPTIONS);
        assertEquals(insert.getTarget(), createTable.getName());
        assertEquals(insert.getColumns(), Optional.of(ImmutableList.of(identifier("b"), identifier("a"), identifier("c"))));

        Table table = new Table(createTable.getName());
        SingleColumn columnA = new SingleColumn(new FunctionCall(QualifiedName.of("checksum"), ImmutableList.of(new Identifier("A"))));
        SingleColumn columnB = new SingleColumn(new FunctionCall(QualifiedName.of("checksum"), ImmutableList.of(new FunctionCall(QualifiedName.of("round"), ImmutableList.of(new Identifier("B"), new LongLiteral("1"))))));
        SingleColumn columnC = new SingleColumn(new FunctionCall(QualifiedName.of("checksum"), ImmutableList.of(new Identifier("C"))));
        assertEquals(parser.createStatement(rewrittenQuery.getQuery(), PARSING_OPTIONS), simpleQuery(selectList(columnA, columnB, columnC), table));

        assertEquals(rewrittenQuery.getPostQueries().size(), 1);
        assertEquals(parser.createStatement(rewrittenQuery.getPostQueries().get(0), PARSING_OPTIONS), new DropTable(createTable.getName(), true));
    }
}
