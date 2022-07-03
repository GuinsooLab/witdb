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
package io.trino.operator.scalar.time;

import io.trino.Session;
import io.trino.spi.type.SqlTime;
import io.trino.spi.type.TimeZoneKey;
import io.trino.sql.query.QueryAssertions;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.BiFunction;

import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.type.DateTimes.PICOSECONDS_PER_SECOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTime
{
    protected QueryAssertions assertions;

    @BeforeClass
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
    {
        assertions.close();
        assertions = null;
    }

    @Test
    public void testLiterals()
    {
        assertThat(assertions.expression("TIME '12:34:56'"))
                .hasType(createTimeType(0))
                .isEqualTo(time(0, 12, 34, 56, 0));

        assertThat(assertions.expression("TIME '12:34:56.1'"))
                .hasType(createTimeType(1))
                .isEqualTo(time(1, 12, 34, 56, 100_000_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.12'"))
                .hasType(createTimeType(2))
                .isEqualTo(time(2, 12, 34, 56, 120_000_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.123'"))
                .hasType(createTimeType(3))
                .isEqualTo(time(3, 12, 34, 56, 123_000_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.1234'"))
                .hasType(createTimeType(4))
                .isEqualTo(time(4, 12, 34, 56, 123_400_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.12345'"))
                .hasType(createTimeType(5))
                .isEqualTo(time(5, 12, 34, 56, 123_450_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.123456'"))
                .hasType(createTimeType(6))
                .isEqualTo(time(6, 12, 34, 56, 123_456_000_000L));

        assertThat(assertions.expression("TIME '12:34:56.1234567'"))
                .hasType(createTimeType(7))
                .isEqualTo(time(7, 12, 34, 56, 123_456_700_000L));

        assertThat(assertions.expression("TIME '12:34:56.12345678'"))
                .hasType(createTimeType(8))
                .isEqualTo(time(8, 12, 34, 56, 123_456_780_000L));

        assertThat(assertions.expression("TIME '12:34:56.123456789'"))
                .hasType(createTimeType(9))
                .isEqualTo(time(9, 12, 34, 56, 123_456_789_000L));

        assertThat(assertions.expression("TIME '12:34:56.1234567891'"))
                .hasType(createTimeType(10))
                .isEqualTo(time(10, 12, 34, 56, 123_456_789_100L));

        assertThat(assertions.expression("TIME '12:34:56.12345678912'"))
                .hasType(createTimeType(11))
                .isEqualTo(time(11, 12, 34, 56, 123_456_789_120L));

        assertThat(assertions.expression("TIME '12:34:56.123456789123'"))
                .hasType(createTimeType(12))
                .isEqualTo(time(12, 12, 34, 56, 123_456_789_123L));

        assertThatThrownBy(() -> assertions.expression("TIME '12:34:56.1234567891234'"))
                .hasMessage("line 1:8: TIME precision must be in range [0, 12]: 13");

        assertThatThrownBy(() -> assertions.expression("TIME '25:00:00'"))
                .hasMessage("line 1:8: '25:00:00' is not a valid time literal");

        assertThatThrownBy(() -> assertions.expression("TIME '12:65:00'"))
                .hasMessage("line 1:8: '12:65:00' is not a valid time literal");

        assertThatThrownBy(() -> assertions.expression("TIME '12:00:65'"))
                .hasMessage("line 1:8: '12:00:65' is not a valid time literal");

        assertThatThrownBy(() -> assertions.expression("TIME 'xxx'"))
                .hasMessage("line 1:8: 'xxx' is not a valid time literal");
    }

    @Test
    public void testCastToTime()
    {
        // source = target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(2))")).matches("TIME '12:34:56.12'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(3))")).matches("TIME '12:34:56.123'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(4))")).matches("TIME '12:34:56.1234'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(5))")).matches("TIME '12:34:56.12345'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(6))")).matches("TIME '12:34:56.123456'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(7))")).matches("TIME '12:34:56.1234567'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(8))")).matches("TIME '12:34:56.12345678'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(9))")).matches("TIME '12:34:56.123456789'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(10))")).matches("TIME '12:34:56.1234567891'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIME(11))")).matches("TIME '12:34:56.12345678912'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789123' AS TIME(12))")).matches("TIME '12:34:56.123456789123'");

        // source < target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(1))")).matches("TIME '12:34:56.0'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(2))")).matches("TIME '12:34:56.00'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(3))")).matches("TIME '12:34:56.000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(4))")).matches("TIME '12:34:56.0000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(5))")).matches("TIME '12:34:56.00000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(6))")).matches("TIME '12:34:56.000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(7))")).matches("TIME '12:34:56.0000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(8))")).matches("TIME '12:34:56.00000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(9))")).matches("TIME '12:34:56.000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(10))")).matches("TIME '12:34:56.0000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(11))")).matches("TIME '12:34:56.00000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(12))")).matches("TIME '12:34:56.000000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(2))")).matches("TIME '12:34:56.10'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(3))")).matches("TIME '12:34:56.100'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(4))")).matches("TIME '12:34:56.1000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(5))")).matches("TIME '12:34:56.10000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(6))")).matches("TIME '12:34:56.100000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(7))")).matches("TIME '12:34:56.1000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(8))")).matches("TIME '12:34:56.10000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(9))")).matches("TIME '12:34:56.100000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(10))")).matches("TIME '12:34:56.1000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(11))")).matches("TIME '12:34:56.10000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(12))")).matches("TIME '12:34:56.100000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(3))")).matches("TIME '12:34:56.120'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(4))")).matches("TIME '12:34:56.1200'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(5))")).matches("TIME '12:34:56.12000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(6))")).matches("TIME '12:34:56.120000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(7))")).matches("TIME '12:34:56.1200000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(8))")).matches("TIME '12:34:56.12000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(9))")).matches("TIME '12:34:56.120000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(10))")).matches("TIME '12:34:56.1200000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(11))")).matches("TIME '12:34:56.12000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(12))")).matches("TIME '12:34:56.120000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(4))")).matches("TIME '12:34:56.1230'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(5))")).matches("TIME '12:34:56.12300'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(6))")).matches("TIME '12:34:56.123000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(7))")).matches("TIME '12:34:56.1230000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(8))")).matches("TIME '12:34:56.12300000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(9))")).matches("TIME '12:34:56.123000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(10))")).matches("TIME '12:34:56.1230000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(11))")).matches("TIME '12:34:56.12300000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(12))")).matches("TIME '12:34:56.123000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(5))")).matches("TIME '12:34:56.12340'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(6))")).matches("TIME '12:34:56.123400'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(7))")).matches("TIME '12:34:56.1234000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(8))")).matches("TIME '12:34:56.12340000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(9))")).matches("TIME '12:34:56.123400000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(10))")).matches("TIME '12:34:56.1234000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(11))")).matches("TIME '12:34:56.12340000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(12))")).matches("TIME '12:34:56.123400000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(6))")).matches("TIME '12:34:56.123450'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(7))")).matches("TIME '12:34:56.1234500'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(8))")).matches("TIME '12:34:56.12345000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(9))")).matches("TIME '12:34:56.123450000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(10))")).matches("TIME '12:34:56.1234500000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(11))")).matches("TIME '12:34:56.12345000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(12))")).matches("TIME '12:34:56.123450000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(7))")).matches("TIME '12:34:56.1234560'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(8))")).matches("TIME '12:34:56.12345600'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(9))")).matches("TIME '12:34:56.123456000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(10))")).matches("TIME '12:34:56.1234560000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(11))")).matches("TIME '12:34:56.12345600000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(12))")).matches("TIME '12:34:56.123456000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(8))")).matches("TIME '12:34:56.12345670'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(9))")).matches("TIME '12:34:56.123456700'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(10))")).matches("TIME '12:34:56.1234567000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(11))")).matches("TIME '12:34:56.12345670000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(12))")).matches("TIME '12:34:56.123456700000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(9))")).matches("TIME '12:34:56.123456780'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(10))")).matches("TIME '12:34:56.1234567800'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(11))")).matches("TIME '12:34:56.12345678000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(12))")).matches("TIME '12:34:56.123456780000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(10))")).matches("TIME '12:34:56.1234567890'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(11))")).matches("TIME '12:34:56.12345678900'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(12))")).matches("TIME '12:34:56.123456789000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(11))")).matches("TIME '12:34:56.12345678910'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(12))")).matches("TIME '12:34:56.123456789100'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIME(12))")).matches("TIME '12:34:56.123456789120'");

        // source > target, round down
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(0))")).matches("TIME '12:34:56'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(10))")).matches("TIME '12:34:56.1111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(10))")).matches("TIME '12:34:56.1111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(11))")).matches("TIME '12:34:56.11111111111'");

        // source > target, round up
        assertThat(assertions.expression("CAST(TIME '12:34:56.5' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(0))")).matches("TIME '12:34:57'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(10))")).matches("TIME '12:34:56.5555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(10))")).matches("TIME '12:34:56.5555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(11))")).matches("TIME '12:34:56.55555555556'");

        // wrap-around
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(0))")).matches("TIME '00:00:00'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(1))")).matches("TIME '00:00:00.0'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(2))")).matches("TIME '00:00:00.00'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(3))")).matches("TIME '00:00:00.000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(4))")).matches("TIME '00:00:00.0000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(5))")).matches("TIME '00:00:00.00000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(6))")).matches("TIME '00:00:00.000000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(7))")).matches("TIME '00:00:00.0000000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(8))")).matches("TIME '00:00:00.00000000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(9))")).matches("TIME '00:00:00.000000000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(10))")).matches("TIME '00:00:00.0000000000'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(11))")).matches("TIME '00:00:00.00000000000'");
    }

    @Test
    public void testLocalTime()
    {
        // round down
        Session session = assertions.sessionBuilder()
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 12, 34, 56, 111111111, assertions.getDefaultSession().getTimeZoneKey().getZoneId())))
                .build();

        assertThat(assertions.expression("localtime(0)", session)).matches("TIME '12:34:56'");
        assertThat(assertions.expression("localtime(1)", session)).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("localtime(2)", session)).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("localtime(3)", session)).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("localtime(4)", session)).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("localtime(5)", session)).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("localtime(6)", session)).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("localtime(7)", session)).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("localtime(8)", session)).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("localtime(9)", session)).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("localtime(10)", session)).matches("TIME '12:34:56.1111111110'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(11)", session)).matches("TIME '12:34:56.11111111100'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(12)", session)).matches("TIME '12:34:56.111111111000'"); // Java instant provides p = 9 precision

        // round up
        session = assertions.sessionBuilder()
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 12, 34, 56, 555555555, assertions.getDefaultSession().getTimeZoneKey().getZoneId())))
                .build();

        assertThat(assertions.expression("localtime(0)", session)).matches("TIME '12:34:57'");
        assertThat(assertions.expression("localtime(1)", session)).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("localtime(2)", session)).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("localtime(3)", session)).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("localtime(4)", session)).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("localtime(5)", session)).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("localtime(6)", session)).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("localtime(7)", session)).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("localtime(8)", session)).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("localtime(9)", session)).matches("TIME '12:34:56.555555555'");
        assertThat(assertions.expression("localtime(10)", session)).matches("TIME '12:34:56.5555555550'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(11)", session)).matches("TIME '12:34:56.55555555500'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(12)", session)).matches("TIME '12:34:56.555555555000'"); // Java instant provides p = 9 precision

        // round up at the boundary
        session = assertions.sessionBuilder()
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 23, 59, 59, 999999999, assertions.getDefaultSession().getTimeZoneKey().getZoneId())))
                .build();

        assertThat(assertions.expression("localtime(0)", session)).matches("TIME '00:00:00'");
        assertThat(assertions.expression("localtime(1)", session)).matches("TIME '00:00:00.0'");
        assertThat(assertions.expression("localtime(2)", session)).matches("TIME '00:00:00.00'");
        assertThat(assertions.expression("localtime(3)", session)).matches("TIME '00:00:00.000'");
        assertThat(assertions.expression("localtime(4)", session)).matches("TIME '00:00:00.0000'");
        assertThat(assertions.expression("localtime(5)", session)).matches("TIME '00:00:00.00000'");
        assertThat(assertions.expression("localtime(6)", session)).matches("TIME '00:00:00.000000'");
        assertThat(assertions.expression("localtime(7)", session)).matches("TIME '00:00:00.0000000'");
        assertThat(assertions.expression("localtime(8)", session)).matches("TIME '00:00:00.00000000'");
        assertThat(assertions.expression("localtime(9)", session)).matches("TIME '23:59:59.999999999'");
        assertThat(assertions.expression("localtime(10)", session)).matches("TIME '23:59:59.9999999990'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(11)", session)).matches("TIME '23:59:59.99999999900'"); // Java instant provides p = 9 precision
        assertThat(assertions.expression("localtime(12)", session)).matches("TIME '23:59:59.999999999000'"); // Java instant provides p = 9 precision
    }

    @Test
    public void testCastToTimeWithTimeZone()
    {
        Session session = assertions.sessionBuilder()
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey("+08:35"))
                .build();

        // source = target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234567+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345678+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456789+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234567891+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345678912+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789123' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456789123+08:35'");

        // source < target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.0+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.00+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.0000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.00000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.0000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.00000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.0000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.00000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.000000000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.10+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.100+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.10000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.100000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.10000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.100000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.10000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.100000000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.120+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1200+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.120000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1200000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.120000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1200000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.120000000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1230+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12300+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1230000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12300000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1230000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12300000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123000000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12340+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123400+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12340000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123400000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12340000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123400000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123450+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234500+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123450000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234500000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123450000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234560+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345600+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234560000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345600000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456000000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345670+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456700+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234567000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345670000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456700000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456780+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234567800+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345678000+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456780000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1234567890+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345678900+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456789000+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.12345678910+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456789100+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIME(12) WITH TIME ZONE)", session)).matches("TIME '12:34:56.123456789120+08:35'");

        // source > target, round down
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:56+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.111111111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111111+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.1111111111+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.11111111111+08:35'");

        // source > target, round up
        assertThat(assertions.expression("CAST(TIME '12:34:56.5' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '12:34:57+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '12:34:56.6+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '12:34:56.56+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '12:34:56.556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55555556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '12:34:56.555555556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555555556+08:35'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '12:34:56.5555555556+08:35'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '12:34:56.55555555556+08:35'");

        // wrap-around
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(0) WITH TIME ZONE)", session)).matches("TIME '00:00:00+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(1) WITH TIME ZONE)", session)).matches("TIME '00:00:00.0+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(2) WITH TIME ZONE)", session)).matches("TIME '00:00:00.00+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(3) WITH TIME ZONE)", session)).matches("TIME '00:00:00.000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(4) WITH TIME ZONE)", session)).matches("TIME '00:00:00.0000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(5) WITH TIME ZONE)", session)).matches("TIME '00:00:00.00000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(6) WITH TIME ZONE)", session)).matches("TIME '00:00:00.000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(7) WITH TIME ZONE)", session)).matches("TIME '00:00:00.0000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(8) WITH TIME ZONE)", session)).matches("TIME '00:00:00.00000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(9) WITH TIME ZONE)", session)).matches("TIME '00:00:00.000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(10) WITH TIME ZONE)", session)).matches("TIME '00:00:00.0000000000+08:35'");
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(11) WITH TIME ZONE)", session)).matches("TIME '00:00:00.00000000000+08:35'");
    }

    @Test
    public void testCastToTimestamp()
    {
        Session session = assertions.sessionBuilder()
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 12, 34, 56, 111111111, assertions.getDefaultSession().getTimeZoneKey().getZoneId())))
                .build();

        // source = target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567891'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678912'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789123' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789123'");

        // source < target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234500'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234500000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345000000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234560'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345600'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234560000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345600000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456000000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345670'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456700'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345670000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456700000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456780'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567800'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678000'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456780000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567890'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678900'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789000'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678910'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789100'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIMESTAMP(12))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789120'");

        // source > target, round down
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:56'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111111'");

        // source > target, round up
        assertThat(assertions.expression("CAST(TIME '12:34:56.5' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(0))", session)).matches("TIMESTAMP '2020-05-01 12:34:57'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(1))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(2))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(3))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(4))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(5))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(6))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(7))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(8))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(9))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(10))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(11))", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555555556'");
    }

    @Test
    public void testCastToTimestampWithTimeZone()
    {
        Session session = assertions.sessionBuilder()
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 12, 34, 56, 111111111, assertions.getDefaultSession().getTimeZoneKey().getZoneId())))
                .build();

        // source = target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567891 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678912 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789123' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789123 Pacific/Apia'");

        // source < target
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.0000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.00000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.000000000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.10000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.100000000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1200000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12000000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.120000000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1230000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12300000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123000000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12340000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123400000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234500 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234500000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345000000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123450000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234560 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345600 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234560000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345600000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456000000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345670 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456700 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345670000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456700000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456780 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567800 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678000 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456780000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1234567890 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678900 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789000 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.12345678910 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567891' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789100 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678912' AS TIMESTAMP(12) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.123456789120 Pacific/Apia'");

        // source > target, round down
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.111111111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111111 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.1111111111 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.11111111111 Pacific/Apia'");

        // source > target, round up
        assertThat(assertions.expression("CAST(TIME '12:34:56.5' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:57 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(1) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.6 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(2) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.56 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(3) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(4) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(5) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(6) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(7) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(8) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(9) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.555555556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555555556 Pacific/Apia'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(10) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.5555555556 Pacific/Apia'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIMESTAMP(11) WITH TIME ZONE)", session)).matches("TIMESTAMP '2020-05-01 12:34:56.55555555556 Pacific/Apia'");

        // 5-digit year in the future
        assertThat(assertions.expression("CAST(TIMESTAMP '12001-05-01 12:34:56' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '12001-05-01 12:34:56 Pacific/Apia'");

        // 5-digit year in the past
        assertThat(assertions.expression("CAST(TIMESTAMP '-12001-05-01 12:34:56' AS TIMESTAMP(0) WITH TIME ZONE)", session)).matches("TIMESTAMP '-12001-05-01 12:34:56 Pacific/Apia'");
    }

    @Test
    public void testCastToVarchar()
    {
        assertThat(assertions.expression("CAST(TIME '12:34:56' AS VARCHAR)")).isEqualTo("12:34:56");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS VARCHAR)")).isEqualTo("12:34:56.1");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12' AS VARCHAR)")).isEqualTo("12:34:56.12");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123' AS VARCHAR)")).isEqualTo("12:34:56.123");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234' AS VARCHAR)")).isEqualTo("12:34:56.1234");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345' AS VARCHAR)")).isEqualTo("12:34:56.12345");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456' AS VARCHAR)")).isEqualTo("12:34:56.123456");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567' AS VARCHAR)")).isEqualTo("12:34:56.1234567");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678' AS VARCHAR)")).isEqualTo("12:34:56.12345678");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789' AS VARCHAR)")).isEqualTo("12:34:56.123456789");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1234567890' AS VARCHAR)")).isEqualTo("12:34:56.1234567890");
        assertThat(assertions.expression("CAST(TIME '12:34:56.12345678901' AS VARCHAR)")).isEqualTo("12:34:56.12345678901");
        assertThat(assertions.expression("CAST(TIME '12:34:56.123456789012' AS VARCHAR)")).isEqualTo("12:34:56.123456789012");
    }

    @Test
    public void testCastFromVarchar()
    {
        // round down
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(10))")).matches("TIME '12:34:56.1111111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(11))")).matches("TIME '12:34:56.11111111111'");
        assertThat(assertions.expression("CAST('12:34:56.111111111111' AS TIME(12))")).matches("TIME '12:34:56.111111111111'");

        // round up
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(10))")).matches("TIME '12:34:56.5555555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(11))")).matches("TIME '12:34:56.55555555556'");
        assertThat(assertions.expression("CAST('12:34:56.555555555555' AS TIME(12))")).matches("TIME '12:34:56.555555555555'");

        // round up, wrap-around
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(0))")).matches("TIME '00:00:00'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(1))")).matches("TIME '00:00:00.0'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(2))")).matches("TIME '00:00:00.00'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(3))")).matches("TIME '00:00:00.000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(4))")).matches("TIME '00:00:00.0000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(5))")).matches("TIME '00:00:00.00000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(6))")).matches("TIME '00:00:00.000000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(7))")).matches("TIME '00:00:00.0000000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(8))")).matches("TIME '00:00:00.00000000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(9))")).matches("TIME '00:00:00.000000000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(10))")).matches("TIME '00:00:00.0000000000'");
        assertThat(assertions.expression("CAST('23:59:59.999999999999' AS TIME(11))")).matches("TIME '00:00:00.00000000000'");

        // > 12 digits of precision
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(0))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(1))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(2))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(3))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(4))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(5))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(6))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(7))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(8))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(9))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(10))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(11))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
        assertThatThrownBy(() -> assertions.expression("CAST('12:34:56.1111111111111' AS TIME(12))"))
                .hasMessage("Value cannot be cast to time: 12:34:56.1111111111111");
    }

    @Test
    public void testLowerDigitsZeroed()
    {
        // round down
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111111111' AS TIME(0)) AS TIME(12))")).matches("TIME '12:34:56.000000000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111111111' AS TIME(3)) AS TIME(12))")).matches("TIME '12:34:56.111000000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111111111' AS TIME(6)) AS TIME(12))")).matches("TIME '12:34:56.111111000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111111111' AS TIME(9)) AS TIME(12))")).matches("TIME '12:34:56.111111111000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111' AS TIME(0)) AS TIME(6))")).matches("TIME '12:34:56.000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111' AS TIME(3)) AS TIME(6))")).matches("TIME '12:34:56.111000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111111' AS TIME(6)) AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.111' AS TIME(0)) AS TIME(3))")).matches("TIME '12:34:56.000'");

        // round up
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555555555' AS TIME(0)) AS TIME(12))")).matches("TIME '12:34:57.000000000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555555555' AS TIME(3)) AS TIME(12))")).matches("TIME '12:34:56.556000000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555555555' AS TIME(6)) AS TIME(12))")).matches("TIME '12:34:56.555556000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555555555' AS TIME(9)) AS TIME(12))")).matches("TIME '12:34:56.555555556000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555' AS TIME(0)) AS TIME(6))")).matches("TIME '12:34:57.000000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555' AS TIME(3)) AS TIME(6))")).matches("TIME '12:34:56.556000'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555555' AS TIME(6)) AS TIME(6))")).matches("TIME '12:34:56.555555'");
        assertThat(assertions.expression("CAST(CAST(TIME '12:34:56.555' AS TIME(0)) AS TIME(3))")).matches("TIME '12:34:57.000'");
    }

    @Test
    public void testRoundDown()
    {
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(10))")).matches("TIME '12:34:56.1111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111111' AS TIME(11))")).matches("TIME '12:34:56.11111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111111' AS TIME(10))")).matches("TIME '12:34:56.1111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111111' AS TIME(9))")).matches("TIME '12:34:56.111111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111111' AS TIME(8))")).matches("TIME '12:34:56.11111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111111' AS TIME(7))")).matches("TIME '12:34:56.1111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111111' AS TIME(6))")).matches("TIME '12:34:56.111111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(4))")).matches("TIME '12:34:56.1111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111111' AS TIME(5))")).matches("TIME '12:34:56.11111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(3))")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11111' AS TIME(4))")).matches("TIME '12:34:56.1111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(2))")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.1111' AS TIME(3))")).matches("TIME '12:34:56.111'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(1))")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.111' AS TIME(2))")).matches("TIME '12:34:56.11'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(0))")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.11' AS TIME(1))")).matches("TIME '12:34:56.1'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.1' AS TIME(0))")).matches("TIME '12:34:56'");
    }

    @Test
    public void testRoundUp()
    {
        assertThat(assertions.expression("CAST(TIME '23:59:59.999999999999' AS TIME(0))")).matches("TIME '00:00:00'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(10))")).matches("TIME '12:34:56.5555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555555' AS TIME(11))")).matches("TIME '12:34:56.55555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555555' AS TIME(10))")).matches("TIME '12:34:56.5555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555555' AS TIME(9))")).matches("TIME '12:34:56.555555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555555' AS TIME(8))")).matches("TIME '12:34:56.55555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555555' AS TIME(7))")).matches("TIME '12:34:56.5555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555555' AS TIME(6))")).matches("TIME '12:34:56.555556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(4))")).matches("TIME '12:34:56.5556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555555' AS TIME(5))")).matches("TIME '12:34:56.55556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(3))")).matches("TIME '12:34:56.556'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55555' AS TIME(4))")).matches("TIME '12:34:56.5556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(2))")).matches("TIME '12:34:56.56'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.5555' AS TIME(3))")).matches("TIME '12:34:56.556'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(1))")).matches("TIME '12:34:56.6'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.555' AS TIME(2))")).matches("TIME '12:34:56.56'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(0))")).matches("TIME '12:34:57'");
        assertThat(assertions.expression("CAST(TIME '12:34:56.55' AS TIME(1))")).matches("TIME '12:34:56.6'");

        assertThat(assertions.expression("CAST(TIME '12:34:56.5' AS TIME(0))")).matches("TIME '12:34:57'");
    }

    @Test
    public void testFormat()
    {
        // round down
        assertThat(assertions.expression("format('%s', TIME '12:34:56')")).isEqualTo("12:34:56");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.1')")).isEqualTo("12:34:56.100");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.11')")).isEqualTo("12:34:56.110");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.111')")).isEqualTo("12:34:56.111");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.1111')")).isEqualTo("12:34:56.111100");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.11111')")).isEqualTo("12:34:56.111110");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.111111')")).isEqualTo("12:34:56.111111");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.1111111')")).isEqualTo("12:34:56.111111100");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.11111111')")).isEqualTo("12:34:56.111111110");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.111111111')")).isEqualTo("12:34:56.111111111");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.1111111111')")).isEqualTo("12:34:56.111111111");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.11111111111')")).isEqualTo("12:34:56.111111111");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.111111111111')")).isEqualTo("12:34:56.111111111");

        // round up
        assertThat(assertions.expression("format('%s', TIME '12:34:56')")).isEqualTo("12:34:56");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.5')")).isEqualTo("12:34:56.500");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.55')")).isEqualTo("12:34:56.550");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.555')")).isEqualTo("12:34:56.555");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.5555')")).isEqualTo("12:34:56.555500");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.55555')")).isEqualTo("12:34:56.555550");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.555555')")).isEqualTo("12:34:56.555555");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.5555555')")).isEqualTo("12:34:56.555555500");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.55555555')")).isEqualTo("12:34:56.555555550");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.555555555')")).isEqualTo("12:34:56.555555555");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.5555555555')")).isEqualTo("12:34:56.555555556");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.55555555555')")).isEqualTo("12:34:56.555555556");
        assertThat(assertions.expression("format('%s', TIME '12:34:56.555555555555')")).isEqualTo("12:34:56.555555556");
    }

    @Test
    public void testDateDiff()
    {
        // date_diff truncates the fractional part

        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55', TIME '12:34:56')")).matches("BIGINT '1000'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1', TIME '12:34:56.2')")).matches("BIGINT '1100'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11', TIME '12:34:56.22')")).matches("BIGINT '1110'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111', TIME '12:34:56.222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111', TIME '12:34:56.2222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111', TIME '12:34:56.22222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111', TIME '12:34:56.222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111111', TIME '12:34:56.2222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111111', TIME '12:34:56.22222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111111', TIME '12:34:56.222222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111111111', TIME '12:34:56.2222222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111111111', TIME '12:34:56.22222222222')")).matches("BIGINT '1111'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111111111', TIME '12:34:56.222222222222')")).matches("BIGINT '1111'");

        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55', TIME '12:34:56')")).matches("BIGINT '1000'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1', TIME '12:34:56.9')")).matches("BIGINT '1800'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11', TIME '12:34:56.99')")).matches("BIGINT '1880'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111', TIME '12:34:56.999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111', TIME '12:34:56.9999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111', TIME '12:34:56.99999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111', TIME '12:34:56.999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111111', TIME '12:34:56.9999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111111', TIME '12:34:56.99999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111111', TIME '12:34:56.999999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.1111111111', TIME '12:34:56.9999999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.11111111111', TIME '12:34:56.99999999999')")).matches("BIGINT '1888'");
        assertThat(assertions.expression("date_diff('millisecond', TIME '12:34:55.111111111111', TIME '12:34:56.999999999999')")).matches("BIGINT '1888'");

        // coarser unit
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55', TIME '12:34:56')")).matches("BIGINT '1'");

        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1', TIME '12:34:56.2')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11', TIME '12:34:56.22')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111', TIME '12:34:56.222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111', TIME '12:34:56.2222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111', TIME '12:34:56.22222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111', TIME '12:34:56.222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111111', TIME '12:34:56.2222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111111', TIME '12:34:56.22222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111111', TIME '12:34:56.222222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111111111', TIME '12:34:56.2222222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111111111', TIME '12:34:56.22222222222')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111111111', TIME '12:34:56.222222222222')")).matches("BIGINT '1'");

        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1', TIME '12:34:56.9')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11', TIME '12:34:56.99')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111', TIME '12:34:56.999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111', TIME '12:34:56.9999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111', TIME '12:34:56.99999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111', TIME '12:34:56.999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111111', TIME '12:34:56.9999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111111', TIME '12:34:56.99999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111111', TIME '12:34:56.999999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.1111111111', TIME '12:34:56.9999999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.11111111111', TIME '12:34:56.99999999999')")).matches("BIGINT '1'");
        assertThat(assertions.expression("date_diff('hour', TIME '11:34:55.111111111111', TIME '12:34:56.999999999999')")).matches("BIGINT '1'");
    }

    @Test
    public void testDateAdd()
    {
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56')")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.1')")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.10')")).matches("TIME '12:34:56.10'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.100')")).matches("TIME '12:34:56.101'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.1000')")).matches("TIME '12:34:56.1010'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.10000')")).matches("TIME '12:34:56.10100'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.100000')")).matches("TIME '12:34:56.101000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.1000000')")).matches("TIME '12:34:56.1010000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.10000000')")).matches("TIME '12:34:56.10100000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.100000000')")).matches("TIME '12:34:56.101000000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.1000000000')")).matches("TIME '12:34:56.1010000000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.10000000000')")).matches("TIME '12:34:56.10100000000'");
        assertThat(assertions.expression("date_add('millisecond', 1, TIME '12:34:56.100000000000')")).matches("TIME '12:34:56.101000000000'");

        assertThat(assertions.expression("date_add('millisecond', 1000, TIME '12:34:56')")).matches("TIME '12:34:57'");

        assertThat(assertions.expression("date_add('millisecond', 1, TIME '23:59:59.999')")).matches("TIME '00:00:00.000'");
        assertThat(assertions.expression("date_add('millisecond', -1, TIME '00:00:00.000')")).matches("TIME '23:59:59.999'");

        // test possible overflow
        assertThat(assertions.expression("date_add('hour', 365 * 24, TIME '12:34:56')")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("date_add('hour', 365 * 24 + 1, TIME '12:34:56')")).matches("TIME '13:34:56'");

        assertThat(assertions.expression("date_add('minute', 365 * 24 * 60, TIME '12:34:56')")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("date_add('minute', 365 * 24 * 60 + 1, TIME '12:34:56')")).matches("TIME '12:35:56'");

        assertThat(assertions.expression("date_add('second', 365 * 24 * 60 * 60, TIME '12:34:56')")).matches("TIME '12:34:56'");
        assertThat(assertions.expression("date_add('second', 365 * 24 * 60 * 60 + 1, TIME '12:34:56')")).matches("TIME '12:34:57'");

        assertThat(assertions.expression("date_add('millisecond', BIGINT '365' * 24 * 60 * 60 * 1000, TIME '12:34:56.000')")).matches("TIME '12:34:56.000'");
        assertThat(assertions.expression("date_add('millisecond', BIGINT '365' * 24 * 60 * 60 * 1000 + 1, TIME '12:34:56.000')")).matches("TIME '12:34:56.001'");
    }

    @Test
    public void testDateTrunc()
    {
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56')")).matches("TIME '12:34:56'");

        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.1')")).matches("TIME '12:34:56.1'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.11')")).matches("TIME '12:34:56.11'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.111')")).matches("TIME '12:34:56.111'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.1111')")).matches("TIME '12:34:56.1110'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.11111')")).matches("TIME '12:34:56.11100'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.111111')")).matches("TIME '12:34:56.111000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.1111111')")).matches("TIME '12:34:56.1110000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.11111111')")).matches("TIME '12:34:56.11100000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.111111111')")).matches("TIME '12:34:56.111000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.1111111111')")).matches("TIME '12:34:56.1110000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.11111111111')")).matches("TIME '12:34:56.11100000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.111111111111')")).matches("TIME '12:34:56.111000000000'");

        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.5')")).matches("TIME '12:34:56.5'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.55')")).matches("TIME '12:34:56.55'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.555')")).matches("TIME '12:34:56.555'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.5555')")).matches("TIME '12:34:56.5550'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.55555')")).matches("TIME '12:34:56.55500'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.555555')")).matches("TIME '12:34:56.555000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.5555555')")).matches("TIME '12:34:56.5550000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.55555555')")).matches("TIME '12:34:56.55500000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.555555555')")).matches("TIME '12:34:56.555000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.5555555555')")).matches("TIME '12:34:56.5550000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.55555555555')")).matches("TIME '12:34:56.55500000000'");
        assertThat(assertions.expression("date_trunc('millisecond', TIME '12:34:56.555555555555')")).matches("TIME '12:34:56.555000000000'");

        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56')")).matches("TIME '12:34:56'");

        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.1')")).matches("TIME '12:34:56.0'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.11')")).matches("TIME '12:34:56.00'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.111')")).matches("TIME '12:34:56.000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.1111')")).matches("TIME '12:34:56.0000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.11111')")).matches("TIME '12:34:56.00000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.111111')")).matches("TIME '12:34:56.000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.1111111')")).matches("TIME '12:34:56.0000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.11111111')")).matches("TIME '12:34:56.00000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.111111111')")).matches("TIME '12:34:56.000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.1111111111')")).matches("TIME '12:34:56.0000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.11111111111')")).matches("TIME '12:34:56.00000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.111111111111')")).matches("TIME '12:34:56.000000000000'");

        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.5')")).matches("TIME '12:34:56.0'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.55')")).matches("TIME '12:34:56.00'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.555')")).matches("TIME '12:34:56.000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.5555')")).matches("TIME '12:34:56.0000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.55555')")).matches("TIME '12:34:56.00000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.555555')")).matches("TIME '12:34:56.000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.5555555')")).matches("TIME '12:34:56.0000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.55555555')")).matches("TIME '12:34:56.00000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.555555555')")).matches("TIME '12:34:56.000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.5555555555')")).matches("TIME '12:34:56.0000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.55555555555')")).matches("TIME '12:34:56.00000000000'");
        assertThat(assertions.expression("date_trunc('second', TIME '12:34:56.555555555555')")).matches("TIME '12:34:56.000000000000'");

        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56')")).matches("TIME '12:34:00'");

        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.1')")).matches("TIME '12:34:00.0'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.11')")).matches("TIME '12:34:00.00'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.111')")).matches("TIME '12:34:00.000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.1111')")).matches("TIME '12:34:00.0000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.11111')")).matches("TIME '12:34:00.00000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.111111')")).matches("TIME '12:34:00.000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.1111111')")).matches("TIME '12:34:00.0000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.11111111')")).matches("TIME '12:34:00.00000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.111111111')")).matches("TIME '12:34:00.000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.1111111111')")).matches("TIME '12:34:00.0000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.11111111111')")).matches("TIME '12:34:00.00000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.111111111111')")).matches("TIME '12:34:00.000000000000'");

        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.5')")).matches("TIME '12:34:00.0'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.55')")).matches("TIME '12:34:00.00'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.555')")).matches("TIME '12:34:00.000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.5555')")).matches("TIME '12:34:00.0000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.55555')")).matches("TIME '12:34:00.00000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.555555')")).matches("TIME '12:34:00.000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.5555555')")).matches("TIME '12:34:00.0000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.55555555')")).matches("TIME '12:34:00.00000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.555555555')")).matches("TIME '12:34:00.000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.5555555555')")).matches("TIME '12:34:00.0000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.55555555555')")).matches("TIME '12:34:00.00000000000'");
        assertThat(assertions.expression("date_trunc('minute', TIME '12:34:56.555555555555')")).matches("TIME '12:34:00.000000000000'");

        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56')")).matches("TIME '12:00:00'");

        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.1')")).matches("TIME '12:00:00.0'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.11')")).matches("TIME '12:00:00.00'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.111')")).matches("TIME '12:00:00.000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.1111')")).matches("TIME '12:00:00.0000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.11111')")).matches("TIME '12:00:00.00000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.111111')")).matches("TIME '12:00:00.000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.1111111')")).matches("TIME '12:00:00.0000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.11111111')")).matches("TIME '12:00:00.00000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.111111111')")).matches("TIME '12:00:00.000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.1111111111')")).matches("TIME '12:00:00.0000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.11111111111')")).matches("TIME '12:00:00.00000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.111111111111')")).matches("TIME '12:00:00.000000000000'");

        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.5')")).matches("TIME '12:00:00.0'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.55')")).matches("TIME '12:00:00.00'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.555')")).matches("TIME '12:00:00.000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.5555')")).matches("TIME '12:00:00.0000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.55555')")).matches("TIME '12:00:00.00000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.555555')")).matches("TIME '12:00:00.000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.5555555')")).matches("TIME '12:00:00.0000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.55555555')")).matches("TIME '12:00:00.00000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.555555555')")).matches("TIME '12:00:00.000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.5555555555')")).matches("TIME '12:00:00.0000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.55555555555')")).matches("TIME '12:00:00.00000000000'");
        assertThat(assertions.expression("date_trunc('hour', TIME '12:34:56.555555555555')")).matches("TIME '12:00:00.000000000000'");
    }

    @Test
    public void testAtTimeZone()
    {
        Session session = assertions.sessionBuilder()
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey("Pacific/Apia"))
                .setStart(Instant.from(ZonedDateTime.of(2020, 5, 1, 12, 0, 0, 0, ZoneId.of("Pacific/Apia"))))
                .build();

        assertThat(assertions.expression("TIME '12:34:56' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.1' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.1+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.12' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.12+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.123' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.123+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.1234' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.1234+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.12345' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.12345+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.123456' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.123456+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.1234567' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.1234567+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.12345678' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.12345678+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.123456789' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.123456789+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.1234567891' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.1234567891+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.12345678912' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.12345678912+08:35'");
        assertThat(assertions.expression("TIME '12:34:56.123456789123' AT TIME ZONE '+08:35'", session)).matches("TIME '08:09:56.123456789123+08:35'");

        assertThat(assertions.expression("TIME '12:34:56' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.1' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.1+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.12' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.12+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.123' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.123+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.1234' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.1234+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.12345' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.12345+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.123456' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.123456+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.1234567' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.1234567+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.12345678' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.12345678+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.123456789' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.123456789+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.1234567891' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.1234567891+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.12345678912' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.12345678912+10:00'");
        assertThat(assertions.expression("TIME '12:34:56.123456789123' AT TIME ZONE INTERVAL '10' HOUR", session)).matches("TIME '09:34:56.123456789123+10:00'");
    }

    private static BiFunction<Session, QueryRunner, Object> time(int precision, int hour, int minute, int second, long picoOfSecond)
    {
        return (session, queryRunner) -> {
            long picos = (hour * 3600 + minute * 60 + second) * PICOSECONDS_PER_SECOND + picoOfSecond;
            return SqlTime.newInstance(precision, picos);
        };
    }
}
