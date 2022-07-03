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

import io.trino.operator.scalar.AbstractTestFunctions;
import org.testng.annotations.Test;

import static io.trino.spi.function.OperatorType.INDETERMINATE;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static java.lang.String.format;

public class TestBigintOperators
        extends AbstractTestFunctions
{
    @Test
    public void testLiteral()
    {
        assertFunction("100000000037", BIGINT, 100000000037L);
        assertFunction("100000000017", BIGINT, 100000000017L);
    }

    @Test
    public void testTypeConstructor()
    {
        assertFunction("BIGINT '9223372036854775807'", BIGINT, 9223372036854775807L);
        assertFunction("BIGINT '-9223372036854775807'", BIGINT, -9223372036854775807L);
        assertFunction("BIGINT '+754'", BIGINT, 754L);
    }

    @Test
    public void testUnaryPlus()
    {
        assertFunction("+100000000037", BIGINT, 100000000037L);
        assertFunction("+100000000017", BIGINT, 100000000017L);
    }

    @Test
    public void testUnaryMinus()
    {
        assertFunction("-100000000037", BIGINT, -100000000037L);
        assertFunction("-100000000017", BIGINT, -100000000017L);
    }

    @Test
    public void testAdd()
    {
        assertFunction("37 + 100000000037", BIGINT, 37 + 100000000037L);
        assertFunction("37 + 100000000017", BIGINT, 37 + 100000000017L);
        assertFunction("100000000017 + 37", BIGINT, 100000000017L + 37L);
        assertFunction("100000000017 + 100000000017", BIGINT, 100000000017L + 100000000017L);
    }

    @Test
    public void testSubtract()
    {
        assertFunction("100000000037 - 37", BIGINT, 100000000037L - 37L);
        assertFunction("37 - 100000000017", BIGINT, 37 - 100000000017L);
        assertFunction("100000000017 - 37", BIGINT, 100000000017L - 37L);
        assertFunction("100000000017 - 100000000017", BIGINT, 0L);
    }

    @Test
    public void testMultiply()
    {
        assertFunction("100000000037 * 37", BIGINT, 100000000037L * 37L);
        assertFunction("37 * 100000000017", BIGINT, 37 * 100000000017L);
        assertFunction("100000000017 * 37", BIGINT, 100000000017L * 37L);
        assertFunction("100000000017 * 10000017", BIGINT, 100000000017L * 10000017L);
    }

    @Test
    public void testDivide()
    {
        assertFunction("100000000037 / 37", BIGINT, 100000000037L / 37L);
        assertFunction("37 / 100000000017", BIGINT, 37 / 100000000017L);
        assertFunction("100000000017 / 37", BIGINT, 100000000017L / 37L);
        assertFunction("100000000017 / 100000000017", BIGINT, 1L);
    }

    @Test
    public void testModulus()
    {
        assertFunction("100000000037 % 37", BIGINT, 100000000037L % 37L);
        assertFunction("37 % 100000000017", BIGINT, 37 % 100000000017L);
        assertFunction("100000000017 % 37", BIGINT, 100000000017L % 37L);
        assertFunction("100000000017 % 100000000017", BIGINT, 0L);
    }

    @Test
    public void testNegation()
    {
        assertFunction("-(100000000037)", BIGINT, -100000000037L);
        assertFunction("-(100000000017)", BIGINT, -100000000017L);
    }

    @Test
    public void testEqual()
    {
        assertFunction("100000000037 = 100000000037", BOOLEAN, true);
        assertFunction("37 = 100000000017", BOOLEAN, false);
        assertFunction("100000000017 = 37", BOOLEAN, false);
        assertFunction("100000000017 = 100000000017", BOOLEAN, true);
    }

    @Test
    public void testNotEqual()
    {
        assertFunction("100000000037 <> 100000000037", BOOLEAN, false);
        assertFunction("37 <> 100000000017", BOOLEAN, true);
        assertFunction("100000000017 <> 37", BOOLEAN, true);
        assertFunction("100000000017 <> 100000000017", BOOLEAN, false);
    }

    @Test
    public void testLessThan()
    {
        assertFunction("100000000037 < 100000000037", BOOLEAN, false);
        assertFunction("100000000037 < 100000000017", BOOLEAN, false);
        assertFunction("100000000017 < 100000000037", BOOLEAN, true);
        assertFunction("100000000017 < 100000000017", BOOLEAN, false);
    }

    @Test
    public void testLessThanOrEqual()
    {
        assertFunction("100000000037 <= 100000000037", BOOLEAN, true);
        assertFunction("100000000037 <= 100000000017", BOOLEAN, false);
        assertFunction("100000000017 <= 100000000037", BOOLEAN, true);
        assertFunction("100000000017 <= 100000000017", BOOLEAN, true);
    }

    @Test
    public void testGreaterThan()
    {
        assertFunction("100000000037 > 100000000037", BOOLEAN, false);
        assertFunction("100000000037 > 100000000017", BOOLEAN, true);
        assertFunction("100000000017 > 100000000037", BOOLEAN, false);
        assertFunction("100000000017 > 100000000017", BOOLEAN, false);
    }

    @Test
    public void testGreaterThanOrEqual()
    {
        assertFunction("100000000037 >= 100000000037", BOOLEAN, true);
        assertFunction("100000000037 >= 100000000017", BOOLEAN, true);
        assertFunction("100000000017 >= 100000000037", BOOLEAN, false);
        assertFunction("100000000017 >= 100000000017", BOOLEAN, true);
    }

    @Test
    public void testBetween()
    {
        assertFunction("100000000037 BETWEEN 100000000037 AND 100000000037", BOOLEAN, true);
        assertFunction("100000000037 BETWEEN 100000000037 AND 100000000017", BOOLEAN, false);

        assertFunction("100000000037 BETWEEN 100000000017 AND 100000000037", BOOLEAN, true);
        assertFunction("100000000037 BETWEEN 100000000017 AND 100000000017", BOOLEAN, false);

        assertFunction("100000000017 BETWEEN 100000000037 AND 100000000037", BOOLEAN, false);
        assertFunction("100000000017 BETWEEN 100000000037 AND 100000000017", BOOLEAN, false);

        assertFunction("100000000017 BETWEEN 100000000017 AND 100000000037", BOOLEAN, true);
        assertFunction("100000000017 BETWEEN 100000000017 AND 100000000017", BOOLEAN, true);
    }

    @Test
    public void testCastToBigint()
    {
        assertFunction("cast(100000000037 as bigint)", BIGINT, 100000000037L);
        assertFunction("cast(100000000017 as bigint)", BIGINT, 100000000017L);
    }

    @Test
    public void testCastToVarchar()
    {
        assertFunction("cast(BIGINT '37' as varchar)", VARCHAR, "37");
        assertFunction("cast(100000000017 as varchar)", VARCHAR, "100000000017");
        assertFunction("cast(100000000017 as varchar(13))", createVarcharType(13), "100000000017");
        assertFunction("cast(100000000017 as varchar(50))", createVarcharType(50), "100000000017");
        assertInvalidCast("cast(100000000017 as varchar(2))", "Value 100000000017 cannot be represented as varchar(2)");
    }

    @Test
    public void testCastToDouble()
    {
        assertFunction("cast(BIGINT '37' as double)", DOUBLE, 37.0);
        assertFunction("cast(100000000017 as double)", DOUBLE, 100000000017.0);
    }

    @Test
    public void testCastToFloat()
    {
        assertFunction("cast(BIGINT '37' as real)", REAL, 37.0f);
        assertFunction("cast(-100000000017 as real)", REAL, -100000000017.0f);
        assertFunction("cast(BIGINT '0' as real)", REAL, 0.0f);
    }

    @Test
    public void testCastToBoolean()
    {
        assertFunction("cast(BIGINT '37' as boolean)", BOOLEAN, true);
        assertFunction("cast(100000000017 as boolean)", BOOLEAN, true);
        assertFunction("cast(BIGINT '0' as boolean)", BOOLEAN, false);
    }

    @Test
    public void testCastFromVarchar()
    {
        assertFunction("cast('100000000037' as bigint)", BIGINT, 100000000037L);
        assertFunction("cast('100000000017' as bigint)", BIGINT, 100000000017L);
    }

    @Test
    public void testIsDistinctFrom()
    {
        assertFunction("CAST(NULL AS BIGINT) IS DISTINCT FROM CAST(NULL AS BIGINT)", BOOLEAN, false);
        assertFunction("100000000037 IS DISTINCT FROM 100000000037", BOOLEAN, false);
        assertFunction("100000000037 IS DISTINCT FROM 100000000038", BOOLEAN, true);
        assertFunction("NULL IS DISTINCT FROM 100000000037", BOOLEAN, true);
        assertFunction("100000000037 IS DISTINCT FROM NULL", BOOLEAN, true);
    }

    @Test
    public void testOverflowAdd()
    {
        assertNumericOverflow(format("%s + BIGINT '1'", Long.MAX_VALUE), "bigint addition overflow: 9223372036854775807 + 1");
    }

    @Test
    public void testUnderflowSubtract()
    {
        assertNumericOverflow(format("%s - 1", Long.MIN_VALUE), "bigint subtraction overflow: -9223372036854775808 - 1");
    }

    @Test
    public void testOverflowMultiply()
    {
        assertNumericOverflow(format("%s * BIGINT '2'", Long.MAX_VALUE), "bigint multiplication overflow: 9223372036854775807 * 2");
        assertNumericOverflow(format("%s * -1", Long.MIN_VALUE), "bigint multiplication overflow: -9223372036854775808 * -1");
    }

    @Test
    public void testOverflowDivide()
    {
        assertNumericOverflow(format("%s / -1", Long.MIN_VALUE), "bigint division overflow: -9223372036854775808 / -1");
    }

    @Test
    public void testIndeterminate()
    {
        assertOperator(INDETERMINATE, "cast(null as bigint)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "cast(1 as bigint)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "cast(4499999999 as bigint)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "4499999999", BOOLEAN, false);
    }

    @Test
    public void testNegateOverflow()
    {
        assertNumericOverflow(format("-(%s)", Long.MIN_VALUE), "bigint negation overflow: -9223372036854775808");
    }
}
