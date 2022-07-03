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
package io.trino.operator.scalar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.metadata.InternalFunctionBundle;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.RowType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.trino.operator.scalar.ApplyFunction.APPLY_FUNCTION;
import static io.trino.operator.scalar.InvokeFunction.INVOKE_FUNCTION;
import static io.trino.spi.StandardErrorCode.FUNCTION_NOT_FOUND;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.util.StructuralTestUtil.mapType;

public class TestLambdaExpression
        extends AbstractTestFunctions
{
    public TestLambdaExpression()
    {
        super(testSessionBuilder().setTimeZoneKey(getTimeZoneKey("Pacific/Kiritimati")).build());
    }

    @BeforeClass
    public void setUp()
    {
        functionAssertions.addFunctions(new InternalFunctionBundle(APPLY_FUNCTION, INVOKE_FUNCTION));
    }

    @Test
    public void testBasic()
    {
        assertFunction("apply(5, x -> x + 1)", INTEGER, 6);
        assertFunction("apply(5 + RANDOM(1), x -> x + 1)", INTEGER, 6);
    }

    @Test
    public void testParameterName()
    {
        // parameter which is not valid identifier in Java
        String nonLetters = "a.b c; d ' \n \\n \"";
        assertFunction("apply(5, " + quote(nonLetters) + " -> " + quote(nonLetters) + " * 2)", INTEGER, 10);
    }

    @Test
    public void testNull()
    {
        assertFunction("apply(3, x -> x + 1)", INTEGER, 4);
        assertFunction("apply(NULL, x -> x + 1)", INTEGER, null);
        assertFunction("apply(CAST (NULL AS INTEGER), x -> x + 1)", INTEGER, null);

        assertFunction("apply(3, x -> x IS NULL)", BOOLEAN, false);
        assertFunction("apply(NULL, x -> x IS NULL)", BOOLEAN, true);
        assertFunction("apply(CAST (NULL AS INTEGER), x -> x IS NULL)", BOOLEAN, true);
    }

    @Test
    public void testUnreferencedLambdaArgument()
    {
        assertFunction("apply(5, x -> 6)", INTEGER, 6);
    }

    @Test
    public void testLambdaWithoutArgument()
    {
        assertFunction("invoke(() -> 42)", INTEGER, 42);
    }

    @Test
    public void testSessionDependent()
    {
        assertFunction("apply('timezone: ', x -> x || current_timezone())", VARCHAR, "timezone: Pacific/Kiritimati");
    }

    @Test
    public void testInstanceFunction()
    {
        assertFunction("apply(ARRAY[2], x -> concat(ARRAY [1], x))", new ArrayType(INTEGER), ImmutableList.of(1, 2));
    }

    @Test
    public void testNestedLambda()
    {
        assertFunction("apply(11, x -> apply(x + 7, y -> apply(y * 3, z -> z * 5) + 1) * 2)", INTEGER, 542);
        assertFunction("apply(11, x -> apply(x + 7, x -> apply(x * 3, x -> x * 5) + 1) * 2)", INTEGER, 542);
    }

    @Test
    public void testRowAccess()
    {
        assertFunction("apply(CAST(ROW(1, 'a') AS ROW(x INTEGER, y VARCHAR)), r -> r[1])", INTEGER, 1);
        assertFunction("apply(CAST(ROW(1, 'a') AS ROW(x INTEGER, y VARCHAR)), r -> r[2])", VARCHAR, "a");
    }

    @Test
    public void testBind()
    {
        assertFunction("apply(90, \"$internal$bind\"(9, (x, y) -> x + y))", INTEGER, 99);
        assertFunction("invoke(\"$internal$bind\"(8, x -> x + 1))", INTEGER, 9);
        assertFunction("apply(900, \"$internal$bind\"(90, 9, (x, y, z) -> x + y + z))", INTEGER, 999);
        assertFunction("invoke(\"$internal$bind\"(90, 9, (x, y) -> x + y))", INTEGER, 99);
    }

    @Test
    public void testCoercion()
    {
        assertFunction("apply(90, x -> x + 9.0E0)", DOUBLE, 99.0);

        assertFunction("apply(90, \"$internal$bind\"(9.0E0, (x, y) -> x + y))", DOUBLE, 99.0);
        assertFunction("invoke(\"$internal$bind\"(8, x -> x + 1.0E0))", DOUBLE, 9.0);
    }

    @Test
    public void testTypeCombinations()
    {
        assertFunction("apply(25, x -> x + 1)", INTEGER, 26);
        assertFunction("apply(25, x -> x + 1.0E0)", DOUBLE, 26.0);
        assertFunction("apply(25, x -> x = 25)", BOOLEAN, true);
        assertFunction("apply(25, x -> to_base(x, 16))", createVarcharType(64), "19");
        assertFunction("apply(25, x -> ARRAY[x + 1])", new ArrayType(INTEGER), ImmutableList.of(26));

        assertFunction("apply(25.6E0, x -> CAST(x AS BIGINT))", BIGINT, 26L);
        assertFunction("apply(25.6E0, x -> x + 1.0E0)", DOUBLE, 26.6);
        assertFunction("apply(25.6E0, x -> x = 25.6E0)", BOOLEAN, true);
        assertFunction("apply(25.6E0, x -> CAST(x AS VARCHAR))", createUnboundedVarcharType(), "2.56E1");
        assertFunction("apply(25.6E0, x -> MAP(ARRAY[x + 1], ARRAY[true]))", mapType(DOUBLE, BOOLEAN), ImmutableMap.of(26.6, true));

        assertFunction("apply(true, x -> if(x, 25, 26))", INTEGER, 25);
        assertFunction("apply(false, x -> if(x, 25.6E0, 28.9E0))", DOUBLE, 28.9);
        assertFunction("apply(true, x -> not x)", BOOLEAN, false);
        assertFunction("apply(false, x -> CAST(x AS VARCHAR))", createUnboundedVarcharType(), "false");
        assertFunction("apply(true, x -> ARRAY[x])", new ArrayType(BOOLEAN), ImmutableList.of(true));

        assertFunction("apply('41', x -> from_base(x, 16))", BIGINT, 65L);
        assertFunction("apply('25.6E0', x -> CAST(x AS DOUBLE))", DOUBLE, 25.6);
        assertFunction("apply('abc', x -> 'abc' = x)", BOOLEAN, true);
        assertFunction("apply('abc', x -> x || x)", createUnboundedVarcharType(), "abcabc");
        assertFunction(
                "apply('123', x -> ROW(x, CAST(x AS INTEGER), x > '0'))",
                RowType.anonymous(ImmutableList.of(createVarcharType(3), INTEGER, BOOLEAN)),
                ImmutableList.of("123", 123, true));

        assertFunction("apply(ARRAY['abc', NULL, '123'], x -> from_base(x[3], 10))", BIGINT, 123L);
        assertFunction("apply(ARRAY['abc', NULL, '123'], x -> CAST(x[3] AS DOUBLE))", DOUBLE, 123.0);
        assertFunction("apply(ARRAY['abc', NULL, '123'], x -> x[2] IS NULL)", BOOLEAN, true);
        assertFunction("apply(ARRAY['abc', NULL, '123'], x -> x[2])", createVarcharType(3), null);
        assertFunction("apply(MAP(ARRAY['abc', 'def'], ARRAY[123, 456]), x -> map_keys(x))", new ArrayType(createVarcharType(3)), ImmutableList.of("abc", "def"));
    }

    @Test
    public void testFunctionParameter()
    {
        assertInvalidFunction("count(x -> x)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (<function>) for function count. Expected: count(), count(t) T");
        assertInvalidFunction("max(x -> x)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (<function>) for function max. Expected: max(t) T:orderable, max(e, bigint) E:orderable");
        assertInvalidFunction("sqrt(x -> x)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (<function>) for function sqrt. Expected: sqrt(double)");
        assertInvalidFunction("sqrt(x -> x, 123, x -> x)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (<function>, integer, <function>) for function sqrt. Expected: sqrt(double)");
        assertInvalidFunction("pow(x -> x, 123)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (<function>, integer) for function pow. Expected: pow(double, double)");
        assertInvalidFunction("pow(123, x -> x)", FUNCTION_NOT_FOUND, "line 1:1: Unexpected parameters (integer, <function>) for function pow. Expected: pow(double, double)");
    }

    private static String quote(String identifier)
    {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
