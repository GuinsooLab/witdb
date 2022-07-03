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

import io.trino.spi.type.Type;
import org.testng.annotations.Test;

import static io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.SqlDecimal.decimal;

public class TestDataSizeFunctions
        extends AbstractTestFunctions
{
    private static final Type DECIMAL = createDecimalType(38, 0);

    @Test
    public void testParseDataSize()
    {
        assertFunction("parse_data_size('0B')", DECIMAL, decimal("0", createDecimalType(38)));
        assertFunction("parse_data_size('1B')", DECIMAL, decimal("1", createDecimalType(38)));
        assertFunction("parse_data_size('1.2B')", DECIMAL, decimal("1", createDecimalType(38)));
        assertFunction("parse_data_size('1.9B')", DECIMAL, decimal("1", createDecimalType(38)));
        assertFunction("parse_data_size('2.2kB')", DECIMAL, decimal("2252", createDecimalType(38)));
        assertFunction("parse_data_size('2.23kB')", DECIMAL, decimal("2283", createDecimalType(38)));
        assertFunction("parse_data_size('2.23kB')", DECIMAL, decimal("2283", createDecimalType(38)));
        assertFunction("parse_data_size('2.234kB')", DECIMAL, decimal("2287", createDecimalType(38)));
        assertFunction("parse_data_size('3MB')", DECIMAL, decimal("3145728", createDecimalType(38)));
        assertFunction("parse_data_size('4GB')", DECIMAL, decimal("4294967296", createDecimalType(38)));
        assertFunction("parse_data_size('4TB')", DECIMAL, decimal("4398046511104", createDecimalType(38)));
        assertFunction("parse_data_size('5PB')", DECIMAL, decimal("5629499534213120", createDecimalType(38)));
        assertFunction("parse_data_size('6EB')", DECIMAL, decimal("6917529027641081856", createDecimalType(38)));
        assertFunction("parse_data_size('7ZB')", DECIMAL, decimal("8264141345021879123968", createDecimalType(38)));
        assertFunction("parse_data_size('8YB')", DECIMAL, decimal("9671406556917033397649408", createDecimalType(38)));
        assertFunction("parse_data_size('6917529027641081856EB')", DECIMAL, decimal("7975367974709495237422842361682067456", createDecimalType(38)));
        assertFunction("parse_data_size('69175290276410818560EB')", DECIMAL, decimal("79753679747094952374228423616820674560", createDecimalType(38)));

        assertInvalidFunction("parse_data_size('')", "Invalid data size: ''");
        assertInvalidFunction("parse_data_size('0')", "Invalid data size: '0'");
        assertInvalidFunction("parse_data_size('10KB')", "Invalid data size: '10KB'");
        assertInvalidFunction("parse_data_size('KB')", "Invalid data size: 'KB'");
        assertInvalidFunction("parse_data_size('-1B')", "Invalid data size: '-1B'");
        assertInvalidFunction("parse_data_size('12345K')", "Invalid data size: '12345K'");
        assertInvalidFunction("parse_data_size('A12345B')", "Invalid data size: 'A12345B'");
        assertInvalidFunction("parse_data_size('99999999999999YB')", NUMERIC_VALUE_OUT_OF_RANGE, "Value out of range: '99999999999999YB' ('120892581961461708544797985370825293824B')");
    }
}
