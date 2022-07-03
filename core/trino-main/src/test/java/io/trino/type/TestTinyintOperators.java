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

import static io.trino.spi.StandardErrorCode.DIVISION_BY_ZERO;
import static io.trino.spi.StandardErrorCode.INVALID_LITERAL;
import static io.trino.spi.function.OperatorType.INDETERMINATE;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static java.lang.String.format;

public class TestTinyintOperators
        extends AbstractTestFunctions
{
    @Test
    public void testLiteral()
    {
        assertFunction("TINYINT '37'", TINYINT, (byte) 37);
        assertFunction("TINYINT '17'", TINYINT, (byte) 17);
        assertInvalidFunction("TINYINT '" + ((long) Byte.MAX_VALUE + 1L) + "'", INVALID_LITERAL);
    }

    @Test
    public void testUnaryPlus()
    {
        assertFunction("+TINYINT '37'", TINYINT, (byte) 37);
        assertFunction("+TINYINT '17'", TINYINT, (byte) 17);
    }

    @Test
    public void testUnaryMinus()
    {
        assertFunction("TINYINT '-37'", TINYINT, (byte) -37);
        assertFunction("TINYINT '-17'", TINYINT, (byte) -17);
        assertInvalidFunction("TINYINT '-" + Byte.MIN_VALUE + "'", INVALID_LITERAL);
    }

    @Test
    public void testAdd()
    {
        assertFunction("TINYINT '37' + TINYINT '37'", TINYINT, (byte) (37 + 37));
        assertFunction("TINYINT '37' + TINYINT '17'", TINYINT, (byte) (37 + 17));
        assertFunction("TINYINT '17' + TINYINT '37'", TINYINT, (byte) (17 + 37));
        assertFunction("TINYINT '17' + TINYINT '17'", TINYINT, (byte) (17 + 17));
        assertNumericOverflow(format("TINYINT '%s' + TINYINT '1'", Byte.MAX_VALUE), "tinyint addition overflow: 127 + 1");
    }

    @Test
    public void testSubtract()
    {
        assertFunction("TINYINT '37' - TINYINT '37'", TINYINT, (byte) 0);
        assertFunction("TINYINT '37' - TINYINT '17'", TINYINT, (byte) (37 - 17));
        assertFunction("TINYINT '17' - TINYINT '37'", TINYINT, (byte) (17 - 37));
        assertFunction("TINYINT '17' - TINYINT '17'", TINYINT, (byte) 0);
        assertNumericOverflow(format("TINYINT '%s' - TINYINT '1'", Byte.MIN_VALUE), "tinyint subtraction overflow: -128 - 1");
    }

    @Test
    public void testMultiply()
    {
        assertFunction("TINYINT '11' * TINYINT '11'", TINYINT, (byte) (11 * 11));
        assertFunction("TINYINT '11' * TINYINT '9'", TINYINT, (byte) (11 * 9));
        assertFunction("TINYINT '9' * TINYINT '11'", TINYINT, (byte) (9 * 11));
        assertFunction("TINYINT '9' * TINYINT '9'", TINYINT, (byte) (9 * 9));
        assertNumericOverflow(format("TINYINT '%s' * TINYINT '2'", Byte.MAX_VALUE), "tinyint multiplication overflow: 127 * 2");
    }

    @Test
    public void testDivide()
    {
        assertFunction("TINYINT '37' / TINYINT '37'", TINYINT, (byte) 1);
        assertFunction("TINYINT '37' / TINYINT '17'", TINYINT, (byte) (37 / 17));
        assertFunction("TINYINT '17' / TINYINT '37'", TINYINT, (byte) (17 / 37));
        assertFunction("TINYINT '17' / TINYINT '17'", TINYINT, (byte) 1);
        assertInvalidFunction("TINYINT '17' / TINYINT '0'", DIVISION_BY_ZERO);
    }

    @Test
    public void testModulus()
    {
        assertFunction("TINYINT '37' % TINYINT '37'", TINYINT, (byte) 0);
        assertFunction("TINYINT '37' % TINYINT '17'", TINYINT, (byte) (37 % 17));
        assertFunction("TINYINT '17' % TINYINT '37'", TINYINT, (byte) (17 % 37));
        assertFunction("TINYINT '17' % TINYINT '17'", TINYINT, (byte) 0);
        assertInvalidFunction("TINYINT '17' % TINYINT '0'", DIVISION_BY_ZERO);
    }

    @Test
    public void testNegation()
    {
        assertFunction("-(TINYINT '37')", TINYINT, (byte) -37);
        assertFunction("-(TINYINT '17')", TINYINT, (byte) -17);
        assertFunction("-(TINYINT '" + Byte.MAX_VALUE + "')", TINYINT, (byte) (Byte.MIN_VALUE + 1));
        assertNumericOverflow(format("-(TINYINT '%s')", Byte.MIN_VALUE), "tinyint negation overflow: -128");
    }

    @Test
    public void testEqual()
    {
        assertFunction("TINYINT '37' = TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' = TINYINT '17'", BOOLEAN, false);
        assertFunction("TINYINT '17' = TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '17' = TINYINT '17'", BOOLEAN, true);
    }

    @Test
    public void testNotEqual()
    {
        assertFunction("TINYINT '37' <> TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '37' <> TINYINT '17'", BOOLEAN, true);
        assertFunction("TINYINT '17' <> TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '17' <> TINYINT '17'", BOOLEAN, false);
    }

    @Test
    public void testLessThan()
    {
        assertFunction("TINYINT '37' < TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '37' < TINYINT '17'", BOOLEAN, false);
        assertFunction("TINYINT '17' < TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '17' < TINYINT '17'", BOOLEAN, false);
    }

    @Test
    public void testLessThanOrEqual()
    {
        assertFunction("TINYINT '37' <= TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' <= TINYINT '17'", BOOLEAN, false);
        assertFunction("TINYINT '17' <= TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '17' <= TINYINT '17'", BOOLEAN, true);
    }

    @Test
    public void testGreaterThan()
    {
        assertFunction("TINYINT '37' > TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '37' > TINYINT '17'", BOOLEAN, true);
        assertFunction("TINYINT '17' > TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '17' > TINYINT '17'", BOOLEAN, false);
    }

    @Test
    public void testGreaterThanOrEqual()
    {
        assertFunction("TINYINT '37' >= TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' >= TINYINT '17'", BOOLEAN, true);
        assertFunction("TINYINT '17' >= TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '17' >= TINYINT '17'", BOOLEAN, true);
    }

    @Test
    public void testBetween()
    {
        assertFunction("TINYINT '37' BETWEEN TINYINT '37' AND TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' BETWEEN TINYINT '37' AND TINYINT '17'", BOOLEAN, false);

        assertFunction("TINYINT '37' BETWEEN TINYINT '17' AND TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' BETWEEN TINYINT '17' AND TINYINT '17'", BOOLEAN, false);

        assertFunction("TINYINT '17' BETWEEN TINYINT '37' AND TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '17' BETWEEN TINYINT '37' AND TINYINT '17'", BOOLEAN, false);

        assertFunction("TINYINT '17' BETWEEN TINYINT '17' AND TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '17' BETWEEN TINYINT '17' AND TINYINT '17'", BOOLEAN, true);
    }

    @Test
    public void testCastToBigint()
    {
        assertFunction("cast(TINYINT '37' as bigint)", BIGINT, 37L);
        assertFunction("cast(TINYINT '17' as bigint)", BIGINT, 17L);
    }

    @Test
    public void testCastToInteger()
    {
        assertFunction("cast(TINYINT '37' as integer)", INTEGER, 37);
        assertFunction("cast(TINYINT '17' as integer)", INTEGER, 17);
    }

    @Test
    public void testCastToSmallint()
    {
        assertFunction("cast(TINYINT '37' as smallint)", SMALLINT, (short) 37);
        assertFunction("cast(TINYINT '17' as smallint)", SMALLINT, (short) 17);
    }

    @Test
    public void testCastToVarchar()
    {
        assertFunction("cast(TINYINT '37' as varchar)", VARCHAR, "37");
        assertFunction("cast(TINYINT '17' as varchar)", VARCHAR, "17");
        assertFunction("cast(TINYINT '123' as varchar(3))", createVarcharType(3), "123");
        assertFunction("cast(TINYINT '123' as varchar(50))", createVarcharType(50), "123");
        assertInvalidCast("cast(TINYINT '123' as varchar(2))", "Value 123 cannot be represented as varchar(2)");
    }

    @Test
    public void testCastToDouble()
    {
        assertFunction("cast(TINYINT '37' as double)", DOUBLE, 37.0);
        assertFunction("cast(TINYINT '17' as double)", DOUBLE, 17.0);
    }

    @Test
    public void testCastToFloat()
    {
        assertFunction("cast(TINYINT '37' as real)", REAL, 37.0f);
        assertFunction("cast(TINYINT '-128' as real)", REAL, -128.0f);
        assertFunction("cast(TINYINT '0' as real)", REAL, 0.0f);
    }

    @Test
    public void testCastToBoolean()
    {
        assertFunction("cast(TINYINT '37' as boolean)", BOOLEAN, true);
        assertFunction("cast(TINYINT '17' as boolean)", BOOLEAN, true);
        assertFunction("cast(TINYINT '0' as boolean)", BOOLEAN, false);
    }

    @Test
    public void testCastFromVarchar()
    {
        assertFunction("cast('37' as tinyint)", TINYINT, (byte) 37);
        assertFunction("cast('17' as tinyint)", TINYINT, (byte) 17);
    }

    @Test
    public void testIsDistinctFrom()
    {
        assertFunction("CAST(NULL AS TINYINT) IS DISTINCT FROM CAST(NULL AS TINYINT)", BOOLEAN, false);
        assertFunction("TINYINT '37' IS DISTINCT FROM TINYINT '37'", BOOLEAN, false);
        assertFunction("TINYINT '37' IS DISTINCT FROM TINYINT '38'", BOOLEAN, true);
        assertFunction("NULL IS DISTINCT FROM TINYINT '37'", BOOLEAN, true);
        assertFunction("TINYINT '37' IS DISTINCT FROM NULL", BOOLEAN, true);
    }

    @Test
    public void testIndeterminate()
    {
        assertOperator(INDETERMINATE, "cast(null as tinyint)", BOOLEAN, true);
        assertOperator(INDETERMINATE, "cast(12 as tinyint)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "cast(0 as tinyint)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "cast(-23 as tinyint)", BOOLEAN, false);
        assertOperator(INDETERMINATE, "cast(1.4 as tinyint)", BOOLEAN, false);
    }
}
