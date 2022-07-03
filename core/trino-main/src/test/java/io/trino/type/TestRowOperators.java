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
package io.trino.type;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.airlift.slice.Slice;
import io.trino.operator.scalar.AbstractTestFunctions;
import io.trino.operator.scalar.CombineHashFunction;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.spi.StandardErrorCode.TYPE_MISMATCH;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.function.InvocationConvention.simpleConvention;
import static io.trino.spi.function.OperatorType.HASH_CODE;
import static io.trino.spi.function.OperatorType.INDETERMINATE;
import static io.trino.spi.function.OperatorType.XX_HASH_64;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.SqlDecimal.decimal;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static io.trino.type.JsonType.JSON;
import static io.trino.util.StructuralTestUtil.mapType;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.testng.Assert.assertEquals;

public class TestRowOperators
        extends AbstractTestFunctions
{
    public TestRowOperators() {}

    @BeforeClass
    public void setUp()
    {
        registerScalar(getClass());
    }

    @ScalarFunction
    @LiteralParameters("x")
    @SqlType(StandardTypes.JSON)
    public static Slice uncheckedToJson(@SqlType("varchar(x)") Slice slice)
    {
        return slice;
    }

    @Test
    public void testRowTypeLookup()
    {
        TypeSignature signature = RowType.from(ImmutableList.of(field("b", BIGINT))).getTypeSignature();
        Type type = functionAssertions.getPlannerContext().getTypeManager().getType(signature);
        assertEquals(type.getTypeSignature().getParameters().size(), 1);
        assertEquals(type.getTypeSignature().getParameters().get(0).getNamedTypeSignature().getName().get(), "b");
    }

    @Test
    public void testRowToJson()
    {
        assertFunction("cast(cast (null AS ROW(BIGINT, VARCHAR)) AS JSON)", JSON, null);
        assertFunction("cast(ROW(null, null) AS json)", JSON, "{\"\":null,\"\":null}");

        assertFunction("cast(ROW(true, false, null) AS JSON)", JSON, "{\"\":true,\"\":false,\"\":null}");

        assertFunction(
                "cast(cast(ROW(12, 12345, 123456789, 1234567890123456789, null, null, null, null) AS ROW(TINYINT, SMALLINT, INTEGER, BIGINT, TINYINT, SMALLINT, INTEGER, BIGINT)) AS JSON)",
                JSON,
                "{\"\":12,\"\":12345,\"\":123456789,\"\":1234567890123456789,\"\":null,\"\":null,\"\":null,\"\":null}");

        assertFunction(
                "CAST(ROW(CAST(3.14E0 AS REAL), 3.1415E0, 1e308, DECIMAL '3.14', DECIMAL '12345678901234567890.123456789012345678', CAST(null AS REAL), CAST(null AS DOUBLE), CAST(null AS DECIMAL)) AS JSON)",
                JSON,
                "{\"\":3.14,\"\":3.1415,\"\":1.0E308,\"\":3.14,\"\":12345678901234567890.123456789012345678,\"\":null,\"\":null,\"\":null}");

        assertFunction(
                "CAST(ROW('a', 'bb', CAST(null AS VARCHAR), JSON '123', JSON '3.14', JSON 'false', JSON '\"abc\"', JSON '[1, \"a\", null]', JSON '{\"a\": 1, \"b\": \"str\", \"c\": null}', JSON 'null', CAST(null AS JSON)) AS JSON)",
                JSON,
                "{\"\":\"a\",\"\":\"bb\",\"\":null,\"\":123,\"\":3.14,\"\":false,\"\":\"abc\",\"\":[1,\"a\",null],\"\":{\"a\":1,\"b\":\"str\",\"c\":null},\"\":null,\"\":null}");
        assertFunction(
                "CAST(ROW(DATE '2001-08-22', DATE '2001-08-23', null) AS JSON)",
                JSON,
                "{\"\":\"2001-08-22\",\"\":\"2001-08-23\",\"\":null}");

        assertFunction(
                "CAST(ROW(TIMESTAMP '1970-01-01 00:00:01', cast(null AS TIMESTAMP)) AS JSON)",
                JSON,
                format("{\"\":\"%s\",\"\":null}", sqlTimestampOf(0, 1970, 1, 1, 0, 0, 1, 0)));

        assertFunction(
                "cast(ROW(ARRAY[1, 2], ARRAY[3, null], ARRAY[], ARRAY[null, null], CAST(null AS ARRAY(BIGINT))) AS JSON)",
                JSON,
                "{\"\":[1,2],\"\":[3,null],\"\":[],\"\":[null,null],\"\":null}");
        assertFunction(
                "cast(ROW(MAP(ARRAY['b', 'a'], ARRAY[2, 1]), MAP(ARRAY['three', 'none'], ARRAY[3, null]), MAP(), MAP(ARRAY['h2', 'h1'], ARRAY[null, null]), CAST(NULL AS MAP(VARCHAR, BIGINT))) AS JSON)",
                JSON,
                "{\"\":{\"a\":1,\"b\":2},\"\":{\"none\":null,\"three\":3},\"\":{},\"\":{\"h1\":null,\"h2\":null},\"\":null}");
        assertFunction(
                "cast(ROW(ROW(1, 2), ROW(3, CAST(null AS INTEGER)), CAST(ROW(null, null) AS ROW(INTEGER, INTEGER)), null) AS JSON)",
                JSON,
                "{\"\":{\"\":1,\"\":2},\"\":{\"\":3,\"\":null},\"\":{\"\":null,\"\":null},\"\":null}");

        // other miscellaneous tests
        assertFunction("CAST(ROW(1, 2) AS JSON)", JSON, "{\"\":1,\"\":2}");
        assertFunction("CAST(CAST(ROW(1, 2) AS ROW(a BIGINT, b BIGINT)) AS JSON)", JSON, "{\"a\":1,\"b\":2}");
        assertFunction("CAST(ROW(1, NULL) AS JSON)", JSON, "{\"\":1,\"\":null}");
        assertFunction("CAST(ROW(1, CAST(NULL AS INTEGER)) AS JSON)", JSON, "{\"\":1,\"\":null}");
        assertFunction("CAST(ROW(1, 2.0E0) AS JSON)", JSON, "{\"\":1,\"\":2.0}");
        assertFunction("CAST(ROW(1.0E0, 2.5E0) AS JSON)", JSON, "{\"\":1.0,\"\":2.5}");
        assertFunction("CAST(ROW(1.0E0, 'kittens') AS JSON)", JSON, "{\"\":1.0,\"\":\"kittens\"}");
        assertFunction("CAST(ROW(TRUE, FALSE) AS JSON)", JSON, "{\"\":true,\"\":false}");
        assertFunction("CAST(ROW(FALSE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0])) AS JSON)", JSON, "{\"\":false,\"\":[1,2],\"\":{\"1\":2.0,\"3\":4.0}}");
        assertFunction("CAST(row(1.0, 123123123456.6549876543) AS JSON)", JSON, "{\"\":1.0,\"\":123123123456.6549876543}");
    }

    @Test
    public void testJsonToRow()
    {
        // special values
        assertFunction("CAST(CAST (null AS JSON) AS ROW(BIGINT))", RowType.anonymous(ImmutableList.of(BIGINT)), null);
        assertFunction("CAST(JSON 'null' AS ROW(BIGINT))", RowType.anonymous(ImmutableList.of(BIGINT)), null);
        assertFunction("CAST(JSON '[null, null]' AS ROW(VARCHAR, BIGINT))", RowType.anonymous(ImmutableList.of(VARCHAR, BIGINT)), Lists.newArrayList(null, null));
        assertFunction(
                "CAST(JSON '{\"k2\": null, \"k1\": null}' AS ROW(k1 VARCHAR, k2 BIGINT))",
                RowType.from(ImmutableList.of(
                        RowType.field("k1", VARCHAR),
                        RowType.field("k2", BIGINT))),
                Lists.newArrayList(null, null));

        // allow json object contains non-exist field names
        assertFunction(
                "CAST(JSON '{\"k1\": [1, 2], \"used\": 3, \"k2\": [4, 5]}' AS ROW(used BIGINT))",
                RowType.from(ImmutableList.of(
                        RowType.field("used", BIGINT))),
                ImmutableList.of(3L));
        assertFunction(
                "CAST(JSON '[{\"k1\": [1, 2], \"used\": 3, \"k2\": [4, 5]}]' AS ARRAY(ROW(used BIGINT)))",
                new ArrayType(RowType.from(ImmutableList.of(
                        RowType.field("used", BIGINT)))),
                ImmutableList.of(ImmutableList.of(3L)));

        // allow non-exist fields in json object
        assertFunction(
                "CAST(JSON '{\"a\":1,\"c\":3}' AS ROW(a BIGINT, b BIGINT, c BIGINT, d BIGINT))",
                RowType.from(ImmutableList.of(
                        RowType.field("a", BIGINT),
                        RowType.field("b", BIGINT),
                        RowType.field("c", BIGINT),
                        RowType.field("d", BIGINT))),
                asList(1L, null, 3L, null));
        assertFunction(
                "CAST(JSON '[{\"a\":1,\"c\":3}]' AS ARRAY<ROW(a BIGINT, b BIGINT, c BIGINT, d BIGINT)>)",
                new ArrayType(
                        RowType.from(ImmutableList.of(
                                RowType.field("a", BIGINT),
                                RowType.field("b", BIGINT),
                                RowType.field("c", BIGINT),
                                RowType.field("d", BIGINT)))),
                ImmutableList.of(asList(1L, null, 3L, null)));

        // fields out of order
        assertFunction(
                "CAST(unchecked_to_json('{\"k4\": 4, \"k2\": 2, \"k3\": 3, \"k1\": 1}') AS ROW(k1 BIGINT, k2 BIGINT, k3 BIGINT, k4 BIGINT))",
                RowType.from(ImmutableList.of(
                        RowType.field("k1", BIGINT),
                        RowType.field("k2", BIGINT),
                        RowType.field("k3", BIGINT),
                        RowType.field("k4", BIGINT))),
                ImmutableList.of(1L, 2L, 3L, 4L));
        assertFunction(
                "CAST(unchecked_to_json('[{\"k4\": 4, \"k2\": 2, \"k3\": 3, \"k1\": 1}]') AS ARRAY(ROW(k1 BIGINT, k2 BIGINT, k3 BIGINT, k4 BIGINT)))",
                new ArrayType(
                        RowType.from(ImmutableList.of(
                                RowType.field("k1", BIGINT),
                                RowType.field("k2", BIGINT),
                                RowType.field("k3", BIGINT),
                                RowType.field("k4", BIGINT)))),
                ImmutableList.of(ImmutableList.of(1L, 2L, 3L, 4L)));

        // boolean
        assertFunction("CAST(JSON '[true, false, 12, 0, 12.3, 0.0, \"true\", \"false\", null]' AS ROW(BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN))",
                RowType.anonymous(ImmutableList.of(BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN)),
                asList(true, false, true, false, true, false, true, false, null));

        assertFunction("CAST(JSON '{\"k1\": true, \"k2\": false, \"k3\": 12, \"k4\": 0, \"k5\": 12.3, \"k6\": 0.0, \"k7\": \"true\", \"k8\": \"false\", \"k9\": null}' AS ROW(k1 BOOLEAN, k2 BOOLEAN, k3 BOOLEAN, k4 BOOLEAN, k5 BOOLEAN, k6 BOOLEAN, k7 BOOLEAN, k8 BOOLEAN, k9 BOOLEAN))",
                RowType.from(ImmutableList.of(
                        RowType.field("k1", BOOLEAN),
                        RowType.field("k2", BOOLEAN),
                        RowType.field("k3", BOOLEAN),
                        RowType.field("k4", BOOLEAN),
                        RowType.field("k5", BOOLEAN),
                        RowType.field("k6", BOOLEAN),
                        RowType.field("k7", BOOLEAN),
                        RowType.field("k8", BOOLEAN),
                        RowType.field("k9", BOOLEAN))),
                asList(true, false, true, false, true, false, true, false, null));

        // tinyint, smallint, integer, bigint
        assertFunction(
                "CAST(JSON '[12,12345,123456789,1234567890123456789,null,null,null,null]' AS ROW(TINYINT, SMALLINT, INTEGER, BIGINT, TINYINT, SMALLINT, INTEGER, BIGINT))",
                RowType.anonymous(ImmutableList.of(TINYINT, SMALLINT, INTEGER, BIGINT, TINYINT, SMALLINT, INTEGER, BIGINT)),
                asList((byte) 12, (short) 12345, 123456789, 1234567890123456789L, null, null, null, null));

        assertFunction(
                "CAST(JSON '{\"tinyint_value\": 12, \"tinyint_null\":null, " +
                        "\"smallint_value\":12345, \"smallint_null\":null, " +
                        " \"integer_value\":123456789, \"integer_null\": null, " +
                        "\"bigint_value\":1234567890123456789, \"bigint_null\": null}' " +
                        "AS ROW(tinyint_value TINYINT, smallint_value SMALLINT, integer_value INTEGER, bigint_value BIGINT, " +
                        "tinyint_null TINYINT, smallint_null SMALLINT, integer_null INTEGER, bigint_null BIGINT))",
                RowType.from(ImmutableList.of(
                        RowType.field("tinyint_value", TINYINT),
                        RowType.field("smallint_value", SMALLINT),
                        RowType.field("integer_value", INTEGER),
                        RowType.field("bigint_value", BIGINT),
                        RowType.field("tinyint_null", TINYINT),
                        RowType.field("smallint_null", SMALLINT),
                        RowType.field("integer_null", INTEGER),
                        RowType.field("bigint_null", BIGINT))),
                asList((byte) 12, (short) 12345, 123456789, 1234567890123456789L, null, null, null, null));

        // real, double, decimal
        assertFunction(
                "CAST(JSON '[12345.67,1234567890.1,123.456,12345678.12345678,null,null,null]' AS ROW(REAL, DOUBLE, DECIMAL(10, 5), DECIMAL(38, 8), REAL, DOUBLE, DECIMAL(7, 7)))",
                RowType.anonymous(ImmutableList.of(REAL, DOUBLE, createDecimalType(10, 5), createDecimalType(38, 8), REAL, DOUBLE, createDecimalType(7, 7))),
                asList(
                        12345.67f,
                        1234567890.1,
                        decimal("123.45600", createDecimalType(10, 5)),
                        decimal("12345678.12345678", createDecimalType(38, 8)),
                        null,
                        null,
                        null));

        assertFunction(
                "CAST(JSON '{" +
                        "\"real_value\": 12345.67, \"real_null\": null, " +
                        "\"double_value\": 1234567890.1, \"double_null\": null, " +
                        "\"decimal_value1\": 123.456, \"decimal_value2\": 12345678.12345678, \"decimal_null\": null}' " +
                        "AS ROW(real_value REAL, double_value DOUBLE, decimal_value1 DECIMAL(10, 5), decimal_value2 DECIMAL(38, 8), " +
                        "real_null REAL, double_null DOUBLE, decimal_null DECIMAL(7, 7)))",
                RowType.from(ImmutableList.of(
                        RowType.field("real_value", REAL),
                        RowType.field("double_value", DOUBLE),
                        RowType.field("decimal_value1", createDecimalType(10, 5)),
                        RowType.field("decimal_value2", createDecimalType(38, 8)),
                        RowType.field("real_null", REAL),
                        RowType.field("double_null", DOUBLE),
                        RowType.field("decimal_null", createDecimalType(7, 7)))),
                asList(
                        12345.67f,
                        1234567890.1,
                        decimal("123.45600", createDecimalType(10, 5)),
                        decimal("12345678.12345678", createDecimalType(38, 8)),
                        null,
                        null,
                        null));

        // varchar, json
        assertFunction(
                "CAST(JSON '[\"puppies\", [1, 2, 3], null, null]' AS ROW(VARCHAR, JSON, VARCHAR, JSON))",
                RowType.anonymous(ImmutableList.of(VARCHAR, JSON, VARCHAR, JSON)),
                asList("puppies", "[1,2,3]", null, "null"));

        assertFunction(
                "CAST(JSON '{\"varchar_value\": \"puppies\", \"json_value_field\": [1, 2, 3], \"varchar_null\": null, \"json_null\": null}' " +
                        "AS ROW(varchar_value VARCHAR, json_value_field JSON, varchar_null VARCHAR, json_null JSON))",
                RowType.from(ImmutableList.of(
                        RowType.field("varchar_value", VARCHAR),
                        RowType.field("json_value_field", JSON),
                        RowType.field("varchar_null", VARCHAR),
                        RowType.field("json_null", JSON))),
                asList("puppies", "[1,2,3]", null, "null"));

        // nested array/map/row
        assertFunction("CAST(JSON '[" +
                        "[1, 2, null, 3], [], null, " +
                        "{\"a\": 1, \"b\": 2, \"none\": null, \"three\": 3}, {}, null, " +
                        "[1, 2, null, 3], null, " +
                        "{\"a\": 1, \"b\": 2, \"none\": null, \"three\": 3}, null]' " +
                        "AS ROW(ARRAY(BIGINT), ARRAY(BIGINT), ARRAY(BIGINT), " +
                        "MAP(VARCHAR, BIGINT), MAP(VARCHAR, BIGINT), MAP(VARCHAR, BIGINT), " +
                        "ROW(BIGINT, BIGINT, BIGINT, BIGINT), ROW(BIGINT)," +
                        "ROW(a BIGINT, b BIGINT, three BIGINT, none BIGINT), ROW(nothing BIGINT)))",
                RowType.anonymous(
                        ImmutableList.of(
                                new ArrayType(BIGINT), new ArrayType(BIGINT), new ArrayType(BIGINT),
                                mapType(VARCHAR, BIGINT), mapType(VARCHAR, BIGINT), mapType(VARCHAR, BIGINT),
                                RowType.anonymous(ImmutableList.of(BIGINT, BIGINT, BIGINT, BIGINT)), RowType.anonymous(ImmutableList.of(BIGINT)),
                                RowType.from(ImmutableList.of(
                                        RowType.field("a", BIGINT),
                                        RowType.field("b", BIGINT),
                                        RowType.field("three", BIGINT),
                                        RowType.field("none", BIGINT))),
                                RowType.from(ImmutableList.of(RowType.field("nothing", BIGINT))))),
                asList(
                        asList(1L, 2L, null, 3L), emptyList(), null,
                        asMap(ImmutableList.of("a", "b", "none", "three"), asList(1L, 2L, null, 3L)), ImmutableMap.of(), null,
                        asList(1L, 2L, null, 3L), null,
                        asList(1L, 2L, 3L, null), null));

        assertFunction("CAST(JSON '{" +
                        "\"array2\": [1, 2, null, 3], " +
                        "\"array1\": [], " +
                        "\"array3\": null, " +
                        "\"map3\": {\"a\": 1, \"b\": 2, \"none\": null, \"three\": 3}, " +
                        "\"map1\": {}, " +
                        "\"map2\": null, " +
                        "\"rowAsJsonArray1\": [1, 2, null, 3], " +
                        "\"rowAsJsonArray2\": null, " +
                        "\"rowAsJsonObject2\": {\"a\": 1, \"b\": 2, \"none\": null, \"three\": 3}, " +
                        "\"rowAsJsonObject1\": null}' " +
                        "AS ROW(array1 ARRAY(BIGINT), array2 ARRAY(BIGINT), array3 ARRAY(BIGINT), " +
                        "map1 MAP(VARCHAR, BIGINT), map2 MAP(VARCHAR, BIGINT), map3 MAP(VARCHAR, BIGINT), " +
                        "rowAsJsonArray1 ROW(BIGINT, BIGINT, BIGINT, BIGINT), rowAsJsonArray2 ROW(BIGINT)," +
                        "rowAsJsonObject1 ROW(nothing BIGINT), rowAsJsonObject2 ROW(a BIGINT, b BIGINT, three BIGINT, none BIGINT)))",
                RowType.from(ImmutableList.of(
                        RowType.field("array1", new ArrayType(BIGINT)),
                        RowType.field("array2", new ArrayType(BIGINT)),
                        RowType.field("array3", new ArrayType(BIGINT)),
                        RowType.field("map1", mapType(VARCHAR, BIGINT)),
                        RowType.field("map2", mapType(VARCHAR, BIGINT)),
                        RowType.field("map3", mapType(VARCHAR, BIGINT)),
                        RowType.field("rowasjsonarray1", RowType.anonymous(ImmutableList.of(BIGINT, BIGINT, BIGINT, BIGINT))),
                        RowType.field("rowasjsonarray2", RowType.anonymous(ImmutableList.of(BIGINT))),
                        RowType.field("rowasjsonobject1", RowType.from(ImmutableList.of(RowType.field("nothing", BIGINT)))),
                        RowType.field("rowasjsonobject2", RowType.from(ImmutableList.of(
                                RowType.field("a", BIGINT),
                                RowType.field("b", BIGINT),
                                RowType.field("three", BIGINT),
                                RowType.field("none", BIGINT)))))),
                asList(
                        emptyList(), asList(1L, 2L, null, 3L), null,
                        ImmutableMap.of(), null, asMap(ImmutableList.of("a", "b", "none", "three"), asList(1L, 2L, null, 3L)),
                        asList(1L, 2L, null, 3L), null,
                        null, asList(1L, 2L, 3L, null)));

        // invalid cast
        assertInvalidCast("CAST(unchecked_to_json('{\"a\":1,\"b\":2,\"a\":3}') AS ROW(a BIGINT, b BIGINT))", "Cannot cast to row(a bigint, b bigint). Duplicate field: a\n{\"a\":1,\"b\":2,\"a\":3}");
        assertInvalidCast("CAST(unchecked_to_json('[{\"a\":1,\"b\":2,\"a\":3}]') AS ARRAY(ROW(a BIGINT, b BIGINT)))", "Cannot cast to array(row(a bigint, b bigint)). Duplicate field: a\n[{\"a\":1,\"b\":2,\"a\":3}]");
    }

    @Test
    public void testFieldAccessor()
    {
        assertFunction("row(1, CAST(NULL AS DOUBLE))[2]", DOUBLE, null);
        assertFunction("row(TRUE, CAST(NULL AS BOOLEAN))[2]", BOOLEAN, null);
        assertFunction("row(TRUE, CAST(NULL AS ARRAY(INTEGER)))[2]", new ArrayType(INTEGER), null);
        assertFunction("row(1.0E0, CAST(NULL AS VARCHAR))[2]", createUnboundedVarcharType(), null);
        assertFunction("row(1, 2)[1]", INTEGER, 1);
        assertFunction("row(1, 'kittens')[2]", createVarcharType(7), "kittens");
        assertFunction("row(1, 2)[2]", INTEGER, 2);
        assertFunction("array[row(1, 2)][1][2]", INTEGER, 2);
        assertFunction("row(FALSE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0]))[2]", new ArrayType(INTEGER), ImmutableList.of(1, 2));
        assertFunction("row(FALSE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0]))[3]", mapType(INTEGER, DOUBLE), ImmutableMap.of(1, 2.0, 3, 4.0));
        assertFunction("row(1.0E0, ARRAY[row(31, 4.1E0), row(32, 4.2E0)], row(3, 4.0E0))[2][2][1]", INTEGER, 32);

        // Using ROW constructor
        assertFunction("CAST(ROW(1, 2) AS ROW(a BIGINT, b DOUBLE))[1]", BIGINT, 1L);
        assertFunction("CAST(ROW(1, 2) AS ROW(a BIGINT, b DOUBLE))[2]", DOUBLE, 2.0);
        assertFunction("ROW(ROW('aa'))[1][1]", createVarcharType(2), "aa");
        assertFunction("ROW(ROW('ab'))[1][1]", createVarcharType(2), "ab");
        assertFunction("CAST(ROW(ARRAY[NULL]) AS ROW(a ARRAY(BIGINT)))[1]", new ArrayType(BIGINT), asList((Integer) null));

        assertDecimalFunction("CAST(row(1.0, 123123123456.6549876543) AS ROW(col0 decimal(2,1), col1 decimal(22,10)))[1]", decimal("1.0", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(row(1.0, 123123123456.6549876543) AS ROW(col0 decimal(2,1), col1 decimal(22,10)))[2]", decimal("123123123456.6549876543", createDecimalType(22, 10)));
    }

    @Test
    public void testRowCast()
    {
        assertFunction("cast(row(2, 3) as row(aa bigint, bb bigint))[1]", BIGINT, 2L);
        assertFunction("cast(row(2, 3) as row(aa bigint, bb bigint))[2]", BIGINT, 3L);
        assertFunction("cast(row(2, 3) as row(aa bigint, bb boolean))[2]", BOOLEAN, true);
        assertFunction("cast(row(2, cast(null as double)) as row(aa bigint, bb double))[2]", DOUBLE, null);
        assertFunction("cast(row(2, 'test_str') as row(aa bigint, bb varchar))[2]", VARCHAR, "test_str");

        // ROW casting with NULLs
        assertFunction("cast(row(1,null,3) as row(aa bigint, bb boolean, cc boolean))[2]", BOOLEAN, null);
        assertFunction("cast(row(1,null,3) as row(aa bigint, bb boolean, cc boolean))[1]", BIGINT, 1L);
        assertFunction("cast(row(null,null,null) as row(aa bigint, bb boolean, cc boolean))[1]", BIGINT, null);

        // there are totally 7 field names
        String longFieldNameCast = "CAST(row(1.2E0, ARRAY[row(233, 6.9E0)], row(1000, 6.3E0)) AS ROW(%s VARCHAR, %s ARRAY(ROW(%s VARCHAR, %s VARCHAR)), %s ROW(%s VARCHAR, %s VARCHAR)))[2][1][1]";
        int fieldCount = 7;
        char[] chars = new char[9333];
        String[] fields = new String[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            Arrays.fill(chars, (char) ('a' + i));
            fields[i] = new String(chars);
        }
        assertFunction(format(longFieldNameCast, fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6]), VARCHAR, "233");

        assertFunction(
                "cast(row(json '2', json '1.5', json 'true', json '\"abc\"', json '[1, 2]') as row(a BIGINT, b DOUBLE, c BOOLEAN, d VARCHAR, e ARRAY(BIGINT)))",
                RowType.from(ImmutableList.of(
                        RowType.field("a", BIGINT),
                        RowType.field("b", DOUBLE),
                        RowType.field("c", BOOLEAN),
                        RowType.field("d", VARCHAR),
                        RowType.field("e", new ArrayType(BIGINT)))),
                asList(2L, 1.5, true, "abc", ImmutableList.of(1L, 2L)));
    }

    @Test
    public void testIsDistinctFrom()
    {
        assertFunction("CAST(NULL AS ROW(UNKNOWN)) IS DISTINCT FROM CAST(NULL AS ROW(UNKNOWN))", BOOLEAN, false);
        assertFunction("row(NULL) IS DISTINCT FROM row(NULL)", BOOLEAN, false);
        assertFunction("row(1, 'cat') IS DISTINCT FROM row(1, 'cat')", BOOLEAN, false);
        assertFunction("row(1, ARRAY [1]) IS DISTINCT FROM row(1, ARRAY [1])", BOOLEAN, false);
        assertFunction("row(1, ARRAY [1, 2]) IS DISTINCT FROM row(1, ARRAY [1, NULL])", BOOLEAN, true);
        assertFunction("row(1, 2.0E0, TRUE, 'cat', from_unixtime(1)) IS DISTINCT FROM row(1, 2.0E0, TRUE, 'cat', from_unixtime(1))", BOOLEAN, false);
        assertFunction("row(1, 2.0E0, TRUE, 'cat', from_unixtime(1)) IS DISTINCT FROM row(1, 2.0E0, TRUE, 'cat', from_unixtime(2))", BOOLEAN, true);
        assertFunction("row(1, 2.0E0, TRUE, 'cat', CAST(NULL AS INTEGER)) IS DISTINCT FROM row(1, 2.0E0, TRUE, 'cat', 2)", BOOLEAN, true);
        assertFunction("row(1, 2.0E0, TRUE, 'cat', CAST(NULL AS INTEGER)) IS DISTINCT FROM row(1, 2.0E0, TRUE, 'cat', CAST(NULL AS INTEGER))", BOOLEAN, false);
        assertFunction("row(1, 2.0E0, TRUE, 'cat') IS DISTINCT FROM row(1, 2.0E0, TRUE, CAST(NULL AS VARCHAR(3)))", BOOLEAN, true);
        assertFunction("row(1, 2.0E0, TRUE, CAST(NULL AS VARCHAR(3))) IS DISTINCT FROM row(1, 2.0E0, TRUE, CAST(NULL AS VARCHAR(3)))", BOOLEAN, false);
        assertFunction("ARRAY[ROW(1)] IS DISTINCT FROM ARRAY[ROW(1)]", BOOLEAN, false);
    }

    @Test
    public void testRowComparison()
    {
        assertFunction("row(TIMESTAMP '2002-01-02 03:04:05.321 +08:10', TIMESTAMP '2002-01-02 03:04:05.321 +08:10') = " +
                "row(TIMESTAMP '2002-01-02 02:04:05.321 +07:10', TIMESTAMP '2002-01-02 03:05:05.321 +08:11')", BOOLEAN, true);
        assertFunction("row(1.0E0, row(TIMESTAMP '2001-01-02 03:04:05.321 +07:09', TIMESTAMP '2001-01-02 03:04:05.321 +07:10')) = " +
                "row(1.0E0, row(TIMESTAMP '2001-01-02 03:04:05.321 +07:09', TIMESTAMP '2001-01-02 03:04:05.321 +07:11'))", BOOLEAN, false);

        assertComparisonCombination("row(TIMESTAMP '2001-01-02 03:04:05.321 +07:09', TIMESTAMP '2001-01-02 03:04:05.321 +07:10')",
                "row(TIMESTAMP '2002-01-02 03:04:05.321 +07:09', TIMESTAMP '2002-01-02 03:04:05.321 +07:09')");
        assertComparisonCombination("row(1.0E0, row(TIMESTAMP '2001-01-02 03:04:05.321 +07:09', TIMESTAMP '2001-01-02 03:04:05.321 +07:10'))",
                "row(2.0E0, row(TIMESTAMP '2001-01-02 03:04:05.321 +07:09', TIMESTAMP '2001-01-02 03:04:05.321 +07:10'))");

        assertComparisonCombination("row(1.0E0, 'kittens')", "row(1.0E0, 'puppies')");
        assertComparisonCombination("row(1, 2.0E0)", "row(5, 2.0E0)");
        assertComparisonCombination("row(TRUE, FALSE, TRUE, FALSE)", "row(TRUE, TRUE, TRUE, FALSE)");
        assertComparisonCombination("row(1, 2.0E0, TRUE, 'kittens', from_unixtime(1))", "row(1, 3.0E0, TRUE, 'kittens', from_unixtime(1))");

        assertInvalidFunction("cast(row(cast(cast ('' as varbinary) as hyperloglog)) as row(col0 hyperloglog)) = cast(row(cast(cast ('' as varbinary) as hyperloglog)) as row(col0 hyperloglog))",
                TYPE_MISMATCH, "line 1:81: Cannot apply operator: row(col0 HyperLogLog) = row(col0 HyperLogLog)");
        assertInvalidFunction("cast(row(cast(cast ('' as varbinary) as hyperloglog)) as row(col0 hyperloglog)) > cast(row(cast(cast ('' as varbinary) as hyperloglog)) as row(col0 hyperloglog))",
                TYPE_MISMATCH, "line 1:81: Cannot apply operator: row(col0 HyperLogLog) < row(col0 HyperLogLog)");

        assertInvalidFunction("cast(row(cast(cast ('' as varbinary) as qdigest(double))) as row(col0 qdigest(double))) = cast(row(cast(cast ('' as varbinary) as qdigest(double))) as row(col0 qdigest(double)))",
                TYPE_MISMATCH, "line 1:89: Cannot apply operator: row(col0 qdigest(double)) = row(col0 qdigest(double))");
        assertInvalidFunction("cast(row(cast(cast ('' as varbinary) as qdigest(double))) as row(col0 qdigest(double))) > cast(row(cast(cast ('' as varbinary) as qdigest(double))) as row(col0 qdigest(double)))",
                TYPE_MISMATCH, "line 1:89: Cannot apply operator: row(col0 qdigest(double)) < row(col0 qdigest(double))");

        assertFunction("row(TRUE, ARRAY [1], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0])) = row(TRUE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0]))", BOOLEAN, false);
        assertFunction("row(TRUE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0])) = row(TRUE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0]))", BOOLEAN, true);

        assertFunction("row(1, CAST(NULL AS INTEGER)) = row(1, 2)", BOOLEAN, null);
        assertFunction("row(1, CAST(NULL AS INTEGER)) != row(1, 2)", BOOLEAN, null);
        assertFunction("row(2, CAST(NULL AS INTEGER)) = row(1, 2)", BOOLEAN, false);
        assertFunction("row(2, CAST(NULL AS INTEGER)) != row(1, 2)", BOOLEAN, true);
        assertInvalidFunction("row(TRUE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0])) > row(TRUE, ARRAY [1, 2], MAP(ARRAY[1, 3], ARRAY[2.0E0, 4.0E0]))",
                TYPE_MISMATCH, "line 1:64: Cannot apply operator: row(boolean, array(integer), map(integer, double)) < row(boolean, array(integer), map(integer, double))");

        assertInvalidFunction("row(1, CAST(NULL AS INTEGER)) < row(1, 2)", StandardErrorCode.NOT_SUPPORTED);

        assertComparisonCombination("row(1.0E0, ARRAY [1,2,3], row(2, 2.0E0))", "row(1.0E0, ARRAY [1,3,3], row(2, 2.0E0))");
        assertComparisonCombination("row(TRUE, ARRAY [1])", "row(TRUE, ARRAY [1, 2])");
        assertComparisonCombination("ROW(1, 2)", "ROW(2, 1)");

        assertFunction("ROW(1, 2) = ROW(1, 2)", BOOLEAN, true);
        assertFunction("ROW(2, 1) != ROW(1, 2)", BOOLEAN, true);
        assertFunction("ROW(1.0, 123123123456.6549876543) = ROW(1.0, 123123123456.6549876543)", BOOLEAN, true);
        assertFunction("ROW(1.0, 123123123456.6549876543) = ROW(1.0, 123123123456.6549876542)", BOOLEAN, false);
        assertFunction("ROW(1.0, 123123123456.6549876543) != ROW(1.0, 123123123456.6549876543)", BOOLEAN, false);
        assertFunction("ROW(1.0, 123123123456.6549876543) != ROW(1.0, 123123123456.6549876542)", BOOLEAN, true);

        for (int i = 1; i < 128; i += 10) {
            assertEqualOperator(i);
        }
    }

    @Test
    public void testRowHashOperator()
    {
        assertRowHashOperator("ROW(1, 2)", ImmutableList.of(INTEGER, INTEGER), ImmutableList.of(1, 2));
        assertRowHashOperator("ROW(true, 2)", ImmutableList.of(BOOLEAN, INTEGER), ImmutableList.of(true, 2));

        for (int i = 1; i < 128; i += 5) {
            assertRowHashOperator(i, false);
            assertRowHashOperator(i, true);
        }
    }

    @Test
    public void testIndeterminate()
    {
        assertOperator(INDETERMINATE, "cast(null as row(col0 bigint))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(1)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(null)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(1,2)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(1,null)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(null,2)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(null,null)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row('111',null)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(null,'222')", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row('111','222')", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(1), row(2), row(3))", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(1), row(null), row(3))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1), row(cast(null as bigint)), row(3))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(row(1)), row(2), row(3))", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(row(null)), row(2), row(3))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(row(cast(null as boolean))), row(2), row(3))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[3,4,5]))", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[row(3,4)]))", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(null,2),row(array[row(3,4)]))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1,null),row(array[row(3,4)]))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[cast(row(3,4) as row(a integer, b integer)), cast(null as row(a integer, b integer))]))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[row(null,4)]))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[row(map(array[8], array[9]),4)]))", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(row(1,2),row(array[row(map(array[8], array[null]),4)]))", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(1E0,2E0)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(1E0,null)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "row(true,false)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "row(true,null)", BOOLEAN, true);
    }

    private void assertEqualOperator(int fieldCount)
    {
        String rowLiteral = toRowLiteral(largeRow(fieldCount, false));
        assertFunction(rowLiteral + " = " + rowLiteral, BOOLEAN, true);
        assertFunction(rowLiteral + " != " + rowLiteral, BOOLEAN, false);

        String alternateRowLiteral = toRowLiteral(largeRow(fieldCount, false, true));
        assertFunction(rowLiteral + " = " + alternateRowLiteral, BOOLEAN, false);
        assertFunction(rowLiteral + " != " + alternateRowLiteral, BOOLEAN, true);

        if (fieldCount > 1) {
            String rowLiteralWithNulls = toRowLiteral(largeRow(fieldCount, true));
            assertFunction(rowLiteralWithNulls + " = " + rowLiteralWithNulls, BOOLEAN, null);
            assertFunction(rowLiteralWithNulls + " != " + rowLiteralWithNulls, BOOLEAN, null);

            String alternateRowLiteralWithNulls = toRowLiteral(largeRow(fieldCount, true, true));
            assertFunction(rowLiteralWithNulls + " = " + alternateRowLiteralWithNulls, BOOLEAN, false);
            assertFunction(rowLiteralWithNulls + " != " + alternateRowLiteralWithNulls, BOOLEAN, true);
        }
    }

    private void assertRowHashOperator(int fieldCount, boolean nulls)
    {
        List<Object> data = largeRow(fieldCount, nulls);
        assertRowHashOperator(toRowLiteral(data), largeRowDataTypes(fieldCount), data);
    }

    private static String toRowLiteral(List<Object> data)
    {
        return "ROW(" + Joiner.on(", ").useForNull("null").join(data) + ")";
    }

    private void assertRowHashOperator(String inputString, List<Type> types, List<Object> elements)
    {
        checkArgument(types.size() == elements.size(), "types and elements must have the same size");
        assertOperator(HASH_CODE, inputString, BIGINT, hashFields(types, elements));
        assertOperator(XX_HASH_64, inputString, BIGINT, hashFields(types, elements));
    }

    private long hashFields(List<Type> types, List<Object> elements)
    {
        checkArgument(types.size() == elements.size(), "types and elements must have the same size");

        long result = 1;
        for (int i = 0; i < types.size(); i++) {
            Object fieldValue = elements.get(i);

            long fieldHashCode = 0;
            if (fieldValue != null) {
                Type fieldType = types.get(i);
                try {
                    fieldHashCode = (long) functionAssertions.getTypeOperators().getHashCodeOperator(fieldType, simpleConvention(FAIL_ON_NULL, NEVER_NULL))
                            .invoke(fieldValue);
                }
                catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }

            result = CombineHashFunction.getHash(result, fieldHashCode);
        }
        return result;
    }

    private static List<Object> largeRow(int fieldCount, boolean nulls)
    {
        return largeRow(fieldCount, nulls, false);
    }

    private static List<Object> largeRow(int fieldCount, boolean nulls, boolean alternateLastElement)
    {
        List<Object> data = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            if (nulls && i % 7 == 0) {
                data.add(null);
            }
            else if (i % 10 == 0) {
                data.add(true);
            }
            else {
                data.add(i);
            }
        }
        if (alternateLastElement) {
            int lastPosition = fieldCount - 1;
            if (nulls && lastPosition % 7 == 0) {
                lastPosition--;
            }
            if (lastPosition % 10 == 0) {
                data.set(lastPosition, false);
            }
            else {
                data.set(lastPosition, -lastPosition);
            }
        }
        return data;
    }

    private static List<Type> largeRowDataTypes(int fieldCount)
    {
        List<Type> types = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            if (i % 10 == 0) {
                types.add(BOOLEAN);
            }
            else {
                types.add(INTEGER);
            }
        }
        return types;
    }

    private void assertComparisonCombination(String base, String greater)
    {
        Set<String> equalOperators = new HashSet<>(ImmutableSet.of("=", ">=", "<="));
        Set<String> greaterOrInequalityOperators = new HashSet<>(ImmutableSet.of(">=", ">", "!="));
        Set<String> lessOrInequalityOperators = new HashSet<>(ImmutableSet.of("<=", "<", "!="));
        for (String operator : ImmutableList.of(">", "=", "<", ">=", "<=", "!=")) {
            assertFunction(base + operator + base, BOOLEAN, equalOperators.contains(operator));
            assertFunction(base + operator + greater, BOOLEAN, lessOrInequalityOperators.contains(operator));
            assertFunction(greater + operator + base, BOOLEAN, greaterOrInequalityOperators.contains(operator));
        }
    }
}
