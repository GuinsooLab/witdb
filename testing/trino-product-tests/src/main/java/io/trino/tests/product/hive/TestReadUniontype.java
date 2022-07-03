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
package io.trino.tests.product.hive;

import io.trino.jdbc.Row;
import io.trino.tempto.AfterTestWithContext;
import io.trino.tempto.BeforeTestWithContext;
import io.trino.tempto.query.QueryResult;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static io.trino.tests.product.TestGroups.SMOKE;
import static io.trino.tests.product.utils.QueryExecutors.onHive;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;

public class TestReadUniontype
        extends HiveProductTest
{
    private static final String TABLE_NAME = "test_read_uniontype";
    private static final String TABLE_NAME_SCHEMA_EVOLUTION = "test_read_uniontype_with_schema_evolution";

    @BeforeTestWithContext
    @AfterTestWithContext
    public void cleanup()
    {
        onHive().executeQuery(format("DROP TABLE IF EXISTS %s", TABLE_NAME));
        onHive().executeQuery(format("DROP TABLE IF EXISTS %s", TABLE_NAME_SCHEMA_EVOLUTION));
    }

    @DataProvider(name = "storage_formats")
    public static Object[][] storageFormats()
    {
        return new String[][] {{"ORC"}, {"AVRO"}};
    }

    @Test(dataProvider = "storage_formats", groups = SMOKE)
    public void testReadUniontype(String storageFormat)
    {
        // According to testing results, the Hive INSERT queries here only work in Hive 1.2
        if (getHiveVersionMajor() != 1 || getHiveVersionMinor() != 2) {
            throw new SkipException("This test can only be run with Hive 1.2 (default config)");
        }

        onHive().executeQuery(format(
                "CREATE TABLE %s (id INT,foo UNIONTYPE<" +
                        "INT," +
                        "DOUBLE," +
                        "ARRAY<STRING>>)" +
                        "STORED AS %s",
                TABLE_NAME,
                storageFormat));

        // Generate a file with rows:
        //   0, {0: 36}
        //   1, {1: 7.2}
        //   2, {2: ['foo', 'bar']}
        //   3, {1: 10.8}
        //   4, {0: 144}
        //   5, {2: ['hello']
        onHive().executeQuery(format(
                "INSERT INTO TABLE %s " +
                        "SELECT 0, create_union(0, CAST(36 AS INT), CAST(NULL AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 1, create_union(1, CAST(NULL AS INT), CAST(7.2 AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 2, create_union(2, CAST(NULL AS INT), CAST(NULL AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 3, create_union(1, CAST(NULL AS INT), CAST(10.8 AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 4, create_union(0, CAST(144 AS INT), CAST(NULL AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 5, create_union(2, CAST(NULL AS INT), CAST(NULL AS DOUBLE), ARRAY('hello', 'world'))",
                TABLE_NAME));
        // Generate a file with rows:
        //    6, {0: 180}
        //    7, {1: 21.6}
        //    8, {0: 252}
        onHive().executeQuery(format(
                "INSERT INTO TABLE %s " +
                        "SELECT 6, create_union(0, CAST(180 AS INT), CAST(NULL AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 7, create_union(1, CAST(NULL AS INT), CAST(21.6 AS DOUBLE), ARRAY('foo','bar')) " +
                        "UNION ALL " +
                        "SELECT 8, create_union(0, CAST(252 AS INT), CAST(NULL AS DOUBLE), ARRAY('foo','bar'))",
                TABLE_NAME));
        QueryResult selectAllResult = onTrino().executeQuery(format("SELECT * FROM %s", TABLE_NAME));
        assertEquals(selectAllResult.rows().size(), 9);
        for (List<?> row : selectAllResult.rows()) {
            int id = (Integer) row.get(0);
            switch (id) {
                case 0:
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, 36, null, null});
                    break;
                case 1:
                    assertStructEquals(row.get(1), new Object[] {(byte) 1, null, 7.2D, null});
                    break;
                case 2:
                    assertStructEquals(row.get(1), new Object[] {(byte) 2, null, null, Arrays.asList("foo", "bar")});
                    break;
                case 3:
                    assertStructEquals(row.get(1), new Object[] {(byte) 1, null, 10.8D, null});
                    break;
                case 4:
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, 144, null, null});
                    break;
                case 5:
                    assertStructEquals(row.get(1), new Object[] {(byte) 2, null, null, Arrays.asList("hello", "world")});
                    break;
                case 6:
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, 180, null, null});
                    break;
                case 7:
                    assertStructEquals(row.get(1), new Object[] {(byte) 1, null, 21.6, null});
                    break;
                case 8:
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, 252, null, null});
                    break;
            }
        }
    }

    @Test(dataProvider = "storage_formats", groups = SMOKE)
    public void testUnionTypeSchemaEvolution(String storageFormat)
    {
        // According to testing results, the Hive INSERT queries here only work in Hive 1.2
        if (getHiveVersionMajor() != 1 || getHiveVersionMinor() != 2) {
            throw new SkipException("This test can only be run with Hive 1.2 (default config)");
        }

        onHive().executeQuery(format(
                "CREATE TABLE %s ("
                        + "c0 INT,"
                        + "c1 UNIONTYPE<"
                        + "     STRUCT<a:STRING, b:STRING>, "
                        + "     STRUCT<c:STRING>>) "
                        + "PARTITIONED BY (c2 INT) "
                        + "STORED AS %s",
                TABLE_NAME_SCHEMA_EVOLUTION,
                storageFormat));
        switch (storageFormat) {
            case "AVRO":
                testAvroSchemaEvolution();
                break;
            case "ORC":
                testORCSchemaEvolution();
                break;
            default:
                throw new UnsupportedOperationException("Unsupported table format.");
        }
    }

    private void testORCSchemaEvolution()
    {
        // Generate a file with rows:
        //   0, {0: <a="a1",b="b1">}
        //   1, {1: <c="c1">}
        onHive().executeQuery(format("INSERT INTO TABLE %s PARTITION (c2 = 5) "
                        + "SELECT 0, create_union(0, named_struct('a', 'a1', 'b', 'b1'), named_struct('c', 'ignore')) "
                        + "UNION ALL "
                        + "SELECT 1, create_union(1, named_struct('a', 'ignore', 'b', 'ignore'), named_struct('c', 'c1'))",
                TABLE_NAME_SCHEMA_EVOLUTION));

        // Add a coercible change inside union type column.
        onHive().executeQuery(format("ALTER TABLE %S CHANGE COLUMN c1 c1 UNIONTYPE<STRUCT<a:STRING, b:STRING>, STRUCT<c:STRING, d:STRING>>",
                TABLE_NAME_SCHEMA_EVOLUTION));

        QueryResult selectAllResult = onTrino().executeQuery(format("SELECT c0, c1 FROM %s", TABLE_NAME_SCHEMA_EVOLUTION));
        assertEquals(selectAllResult.rows().size(), 2);
        for (List<?> row : selectAllResult.rows()) {
            int id = (Integer) row.get(0);
            switch (id) {
                case 0:
                    Row rowValueFirst = rowBuilder().addField("a", "a1").addField("b", "b1").build();
                    assertStructEquals(row.get(1), new Object[]{(byte) 0, rowValueFirst, null});
                    break;
                case 1:
                    Row rowValueSecond = rowBuilder().addField("c", "c1").addField("d", null).build();
                    assertStructEquals(row.get(1), new Object[]{(byte) 1, null, rowValueSecond});
                    break;
            }
        }
    }

    private void testAvroSchemaEvolution()
    {
        /**
         * The following insertion fails on avro.
         *
         * hive (default)> INSERT INTO TABLE  u_username.test_ut_avro partition (c2 = 5)
         *               > SELECT 1, create_union(1, named_struct('a', 'ignore', 'b', 'ignore'), named_struct('c', 'c1'));
         *
         * Error: java.lang.RuntimeException: org.apache.hadoop.hive.ql.metadata.HiveException: Hive Runtime Error while processing writable (null)
         * at org.apache.hadoop.hive.ql.exec.mr.ExecMapper.map(ExecMapper.java:179)
         *  at org.apache.hadoop.mapred.MapRunner.run(MapRunner.java:54)
         *  at org.apache.hadoop.mapred.MapTask.runOldMapper(MapTask.java:459)
         *  at org.apache.hadoop.mapred.MapTask.run(MapTask.java:343)
         *  at org.apache.hadoop.mapred.YarnChild$2.run(YarnChild.java:177)
         *  at java.security.AccessController.doPrivileged(Native Method)
         *  at javax.security.auth.Subject.doAs(Subject.java:422)
         *  at org.apache.hadoop.security.UserGroupInformation.doAs(UserGroupInformation.java:1893)
         *  at org.apache.hadoop.mapred.YarnChild.main(YarnChild.java:171)
         * Caused by: org.apache.hadoop.hive.ql.metadata.HiveException: Hive Runtime Error while processing writable (null)
         *  at org.apache.hadoop.hive.ql.exec.MapOperator.process(MapOperator.java:505)
         *  at org.apache.hadoop.hive.ql.exec.mr.ExecMapper.map(ExecMapper.java:170)
         *  ... 8 more
         * Caused by: java.lang.ArrayIndexOutOfBoundsException: 1
         *  at org.apache.avro.generic.GenericData$Record.get(GenericData.java:135)
         *  at org.apache.avro.generic.GenericData.getField(GenericData.java:580)
         *  at org.apache.avro.generic.GenericData.validate(GenericData.java:373)
         *  at org.apache.avro.generic.GenericData.validate(GenericData.java:395)
         *  at org.apache.avro.generic.GenericData.validate(GenericData.java:373)
         *  at org.apache.hadoop.hive.serde2.avro.AvroSerializer.serialize(AvroSerializer.java:96)
         *
         * So we try coercion logic on the first struct field inside the union (i.e. only for <a,b> struct) only.
         *
         */
        // Generate a file with rows:
        //   0, {0: <a="a1",b="b1">}
        //   1, {0: <a="a2",b="b2">}
        onHive().executeQuery(format(
                "INSERT INTO TABLE %s PARTITION (c2 = 5) "
                        + "SELECT 0, create_union(0, named_struct('a', 'a1', 'b', 'b1'), named_struct('c', 'ignore')) "
                        + "UNION ALL "
                        + "SELECT 1, create_union(0, named_struct('a', 'a2', 'b', 'b2'), named_struct('c', 'ignore'))",
                TABLE_NAME_SCHEMA_EVOLUTION));

        // Add a coercible change inside union type column.
        onHive().executeQuery(format("ALTER TABLE %S CHANGE COLUMN c1 c1 UNIONTYPE<STRUCT<a:STRING, b:STRING, d:STRING>, STRUCT<c:STRING>>", TABLE_NAME_SCHEMA_EVOLUTION));

        QueryResult selectAllResult = onTrino().executeQuery(format("SELECT c0, c1 FROM %s", TABLE_NAME_SCHEMA_EVOLUTION));
        assertEquals(selectAllResult.rows().size(), 2);
        for (List<?> row : selectAllResult.rows()) {
            int id = (Integer) row.get(0);
            switch (id) {
                case 0:
                    Row rowValueFirst = rowBuilder()
                            .addField("a", "a1")
                            .addField("b", "b1")
                            .addField("d", null)
                            .build();
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, rowValueFirst, null});
                    break;
                case 1:
                    Row rowValueSecond = rowBuilder()
                            .addField("a", "a2")
                            .addField("b", "b2")
                            .addField("d", null)
                            .build();
                    assertStructEquals(row.get(1), new Object[] {(byte) 0, rowValueSecond, null});
                    break;
            }
        }
    }

    // TODO use Row as expected too, and use tempto QueryAssert
    private static void assertStructEquals(Object actual, Object[] expected)
    {
        assertThat(actual).isInstanceOf(Row.class);
        Row actualRow = (Row) actual;
        assertEquals(actualRow.getFields().size(), expected.length);
        for (int i = 0; i < actualRow.getFields().size(); i++) {
            assertEquals(actualRow.getFields().get(i).getValue(), expected[i]);
        }
    }

    private static io.trino.jdbc.Row.Builder rowBuilder()
    {
        return io.trino.jdbc.Row.builder();
    }
}
