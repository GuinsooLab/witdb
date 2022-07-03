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

import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.SqlDecimal.decimal;

public class TestDecimalToDecimalCasts
        extends AbstractTestFunctions
{
    @Test
    public void testShortDecimalToShortDecimalCasts()
    {
        assertDecimalFunction("CAST(DECIMAL '0' AS DECIMAL(1, 0))", decimal("0", createDecimalType(1)));
        assertDecimalFunction("CAST(DECIMAL '0' AS DECIMAL(2, 0))", decimal("00", createDecimalType(2)));
        assertDecimalFunction("CAST(DECIMAL '0' AS DECIMAL(3, 2))", decimal("0.00", createDecimalType(3, 2)));

        assertDecimalFunction("CAST(DECIMAL '2' AS DECIMAL(1, 0))", decimal("2", createDecimalType(1)));
        assertDecimalFunction("CAST(DECIMAL '-2' AS DECIMAL(1, 0))", decimal("-2", createDecimalType(1)));
        assertDecimalFunction("CAST(DECIMAL '2.0' AS DECIMAL(2, 1))", decimal("2.0", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(DECIMAL '-2.0' AS DECIMAL(2, 1))", decimal("-2.0", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(DECIMAL '2.0' AS DECIMAL(2, 0))", decimal("02", createDecimalType(2)));
        assertDecimalFunction("CAST(DECIMAL '-2.0' AS DECIMAL(2, 0))", decimal("-02", createDecimalType(2)));
        assertDecimalFunction("CAST(DECIMAL '2.0' AS DECIMAL(3, 2))", decimal("2.00", createDecimalType(3, 2)));
        assertDecimalFunction("CAST(DECIMAL '-2.0' AS DECIMAL(3, 2))", decimal("-2.00", createDecimalType(3, 2)));

        assertDecimalFunction("CAST(DECIMAL '1.449' AS DECIMAL(2, 1))", decimal("1.4", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(DECIMAL '1.459' AS DECIMAL(2, 1))", decimal("1.5", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(DECIMAL '-1.449' AS DECIMAL(2, 1))", decimal("-1.4", createDecimalType(2, 1)));
        assertDecimalFunction("CAST(DECIMAL '-1.459' AS DECIMAL(2, 1))", decimal("-1.5", createDecimalType(2, 1)));

        assertInvalidCast("CAST(DECIMAL '12345.6' AS DECIMAL(4,0))", "Cannot cast DECIMAL(6, 1) '12345.6' to DECIMAL(4, 0)");
        assertInvalidCast("CAST(DECIMAL '-12345.6' AS DECIMAL(4,0))", "Cannot cast DECIMAL(6, 1) '-12345.6' to DECIMAL(4, 0)");
        assertInvalidCast("CAST(DECIMAL '12345.6' AS DECIMAL(4,2))", "Cannot cast DECIMAL(6, 1) '12345.6' to DECIMAL(4, 2)");
        assertInvalidCast("CAST(DECIMAL '-12345.6' AS DECIMAL(4,2))", "Cannot cast DECIMAL(6, 1) '-12345.6' to DECIMAL(4, 2)");
    }

    @Test
    public void testShortDecimalToLongDecimalCasts()
    {
        assertDecimalFunction("CAST(DECIMAL '1.2345' AS DECIMAL(21, 20))", decimal("1.23450000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '-1.2345' AS DECIMAL(21, 20))", decimal("-1.23450000000000000000", createDecimalType(21, 20)));
    }

    @Test
    public void testLongDecimalToShortDecimalCasts()
    {
        assertDecimalFunction("CAST(DECIMAL '1.23450000000000000000' AS DECIMAL(5, 4))", decimal("1.2345", createDecimalType(5, 4)));
        assertDecimalFunction("CAST(DECIMAL '-1.23450000000000000000' AS DECIMAL(5, 4))", decimal("-1.2345", createDecimalType(5, 4)));
    }

    @Test
    public void testLongDecimalToLongDecimalCasts()
    {
        assertDecimalFunction("CAST(DECIMAL '0.00000000000000000000' AS DECIMAL(21, 20))", decimal("0.00000000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '0.00000000000000000000' AS DECIMAL(22, 20))", decimal("00.00000000000000000000", createDecimalType(22, 20)));
        assertDecimalFunction("CAST(DECIMAL '0.00000000000000000000' AS DECIMAL(23, 20))", decimal("000.00000000000000000000", createDecimalType(23, 20)));

        assertDecimalFunction("CAST(DECIMAL '2.00000000000000000000' AS DECIMAL(20, 19))", decimal("2.0000000000000000000", createDecimalType(20, 19)));
        assertDecimalFunction("CAST(DECIMAL '-2.00000000000000000000' AS DECIMAL(20, 19))", decimal("-2.0000000000000000000", createDecimalType(20, 19)));
        assertDecimalFunction("CAST(DECIMAL '2.00000000000000000000' AS DECIMAL(21, 20))", decimal("2.00000000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '-2.00000000000000000000' AS DECIMAL(21, 20))", decimal("-2.00000000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '2.00000000000000000000' AS DECIMAL(22, 20))", decimal("02.00000000000000000000", createDecimalType(22, 20)));
        assertDecimalFunction("CAST(DECIMAL '-2.00000000000000000000' AS DECIMAL(22, 20))", decimal("-02.00000000000000000000", createDecimalType(22, 20)));
        assertDecimalFunction("CAST(DECIMAL '2.00000000000000000000' AS DECIMAL(22, 21))", decimal("2.000000000000000000000", createDecimalType(22, 21)));
        assertDecimalFunction("CAST(DECIMAL '-2.00000000000000000000' AS DECIMAL(22, 21))", decimal("-2.000000000000000000000", createDecimalType(22, 21)));

        assertDecimalFunction("CAST(DECIMAL '1.000000000000000000004' AS DECIMAL(21, 20))", decimal("1.00000000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '1.000000000000000000005' AS DECIMAL(21, 20))", decimal("1.00000000000000000001", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '-1.000000000000000000004' AS DECIMAL(21, 20))", decimal("-1.00000000000000000000", createDecimalType(21, 20)));
        assertDecimalFunction("CAST(DECIMAL '-1.000000000000000000005' AS DECIMAL(21, 20))", decimal("-1.00000000000000000001", createDecimalType(21, 20)));

        assertInvalidCast("CAST(DECIMAL '1234500000000000000000000.6' AS DECIMAL(20,0))", "Cannot cast DECIMAL(26, 1) '1234500000000000000000000.6' to DECIMAL(20, 0)");
        assertInvalidCast("CAST(DECIMAL '-1234500000000000000000000.6' AS DECIMAL(20,0))", "Cannot cast DECIMAL(26, 1) '-1234500000000000000000000.6' to DECIMAL(20, 0)");
        assertInvalidCast("CAST(DECIMAL '1234500000000000000000000.6' AS DECIMAL(22,2))", "Cannot cast DECIMAL(26, 1) '1234500000000000000000000.6' to DECIMAL(22, 2)");
        assertInvalidCast("CAST(DECIMAL '-1234500000000000000000000.6' AS DECIMAL(22,2))", "Cannot cast DECIMAL(26, 1) '-1234500000000000000000000.6' to DECIMAL(22, 2)");
    }
}
