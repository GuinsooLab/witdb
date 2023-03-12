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
package io.trino.sql.planner;

import io.trino.Session;
import io.trino.spi.type.CharType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.sql.planner.assertions.BasePlanTest;
import org.testng.annotations.Test;

import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.output;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

public class TestUnwrapCastInComparison
        extends BasePlanTest
{
    @Test
    public void testEquals()
    {
        // representable
        testUnwrap("smallint", "a = DOUBLE '1'", "a = SMALLINT '1'");

        testUnwrap("bigint", "a = DOUBLE '1'", "a = BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a = DOUBLE '1.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");
        testUnwrap("smallint", "a = DOUBLE '1.9'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("bigint", "a = DOUBLE '1.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // below top of range
        testUnwrap("smallint", "a = DOUBLE '32766'", "a = SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a = DOUBLE '32766.9'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // top of range
        testUnwrap("smallint", "a = DOUBLE '32767'", "a = SMALLINT '32767'");

        // above range
        testUnwrap("smallint", "a = DOUBLE '32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a = DOUBLE '-32767'", "a = SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a = DOUBLE '-32767.9'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // bottom of range
        testUnwrap("smallint", "a = DOUBLE '-32768'", "a = SMALLINT '-32768'");

        // below range
        testUnwrap("smallint", "a = DOUBLE '-32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // -2^64 constant
        testUnwrap("bigint", "a = DOUBLE '-18446744073709551616'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // shorter varchar and char
        testNoUnwrap("varchar(1)", "= CAST('abc' AS char(3))", "char(3)");
        // varchar and char, same length
        testUnwrap("varchar(3)", "a = CAST('abc' AS char(3))", "a = 'abc'");
        testNoUnwrap("varchar(3)", "= CAST('ab' AS char(3))", "char(3)");
        // longer varchar and char
        testUnwrap("varchar(10)", "a = CAST('abc' AS char(3))", "CAST(a AS char(10)) = CAST('abc' AS char(10))"); // actually unwrapping didn't happen
        // unbounded varchar and char
        testUnwrap("varchar", "a = CAST('abc' AS char(3))", "CAST(a AS char(65536)) = CAST('abc' AS char(65536))"); // actually unwrapping didn't happen
        // unbounded varchar and char of maximum length (could be unwrapped, but currently it is not)
        testNoUnwrap("varchar", format("= CAST('abc' AS char(%s))", CharType.MAX_LENGTH), "char(65536)");
    }

    @Test
    public void testNotEquals()
    {
        // representable
        testUnwrap("smallint", "a <> DOUBLE '1'", "a <> SMALLINT '1'");

        testUnwrap("bigint", "a <> DOUBLE '1'", "a <> BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a <> DOUBLE '1.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");
        testUnwrap("smallint", "a <> DOUBLE '1.9'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        testUnwrap("bigint", "a <> DOUBLE '1.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // below top of range
        testUnwrap("smallint", "a <> DOUBLE '32766'", "a <> SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a <> DOUBLE '32766.9'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // top of range
        testUnwrap("smallint", "a <> DOUBLE '32767'", "a <> SMALLINT '32767'");

        // above range
        testUnwrap("smallint", "a <> DOUBLE '32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // 2^64 constant
        testUnwrap("bigint", "a <> DOUBLE '18446744073709551616'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a <> DOUBLE '-32767'", "a <> SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a <> DOUBLE '-32767.9'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // bottom of range
        testUnwrap("smallint", "a <> DOUBLE '-32768'", "a <> SMALLINT '-32768'");

        // below range
        testUnwrap("smallint", "a <> DOUBLE '-32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");
    }

    @Test
    public void testLessThan()
    {
        // representable
        testUnwrap("smallint", "a < DOUBLE '1'", "a < SMALLINT '1'");

        testUnwrap("bigint", "a < DOUBLE '1'", "a < BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a < DOUBLE '1.1'", "a <= SMALLINT '1'");

        testUnwrap("bigint", "a < DOUBLE '1.1'", "a <= BIGINT '1'");

        testUnwrap("smallint", "a < DOUBLE '1.9'", "a < SMALLINT '2'");

        // below top of range
        testUnwrap("smallint", "a < DOUBLE '32766'", "a < SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a < DOUBLE '32766.9'", "a < SMALLINT '32767'");

        // top of range
        testUnwrap("smallint", "a < DOUBLE '32767'", "a <> SMALLINT '32767'");

        // above range
        testUnwrap("smallint", "a < DOUBLE '32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a < DOUBLE '-32767'", "a < SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a < DOUBLE '-32767.9'", "a = SMALLINT '-32768'");

        // bottom of range
        testUnwrap("smallint", "a < DOUBLE '-32768'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // below range
        testUnwrap("smallint", "a < DOUBLE '-32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // -2^64 constant
        testUnwrap("bigint", "a < DOUBLE '-18446744073709551616'", "a IS NULL AND CAST(NULL AS BOOLEAN)");
    }

    @Test
    public void testLessThanOrEqual()
    {
        // representable
        testUnwrap("smallint", "a <= DOUBLE '1'", "a <= SMALLINT '1'");

        testUnwrap("bigint", "a <= DOUBLE '1'", "a <= BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a <= DOUBLE '1.1'", "a <= SMALLINT '1'");

        testUnwrap("bigint", "a <= DOUBLE '1.1'", "a <= BIGINT '1'");

        testUnwrap("smallint", "a <= DOUBLE '1.9'", "a < SMALLINT '2'");

        // below top of range
        testUnwrap("smallint", "a <= DOUBLE '32766'", "a <= SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a <= DOUBLE '32766.9'", "a < SMALLINT '32767'");

        // top of range
        testUnwrap("smallint", "a <= DOUBLE '32767'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // above range
        testUnwrap("smallint", "a <= DOUBLE '32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // 2^64 constant
        testUnwrap("bigint", "a <= DOUBLE '18446744073709551616'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a <= DOUBLE '-32767'", "a <= SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a <= DOUBLE '-32767.9'", "a = SMALLINT '-32768'");

        // bottom of range
        testUnwrap("smallint", "a <= DOUBLE '-32768'", "a = SMALLINT '-32768'");

        // below range
        testUnwrap("smallint", "a <= DOUBLE '-32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");
    }

    @Test
    public void testGreaterThan()
    {
        // representable
        testUnwrap("smallint", "a > DOUBLE '1'", "a > SMALLINT '1'");

        testUnwrap("bigint", "a > DOUBLE '1'", "a > BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a > DOUBLE '1.1'", "a > SMALLINT '1'");

        testUnwrap("smallint", "a > DOUBLE '1.9'", "a >= SMALLINT '2'");

        testUnwrap("bigint", "a > DOUBLE '1.9'", "a >= BIGINT '2'");

        // below top of range
        testUnwrap("smallint", "a > DOUBLE '32766'", "a > SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a > DOUBLE '32766.9'", "a = SMALLINT '32767'");

        // top of range
        testUnwrap("smallint", "a > DOUBLE '32767'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // above range
        testUnwrap("smallint", "a > DOUBLE '32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // 2^64 constant
        testUnwrap("bigint", "a > DOUBLE '18446744073709551616'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a > DOUBLE '-32767'", "a > SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a > DOUBLE '-32767.9'", "a > SMALLINT '-32768'");

        // bottom of range
        testUnwrap("smallint", "a > DOUBLE '-32768'", "a <> SMALLINT '-32768'");

        // below range
        testUnwrap("smallint", "a > DOUBLE '-32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");
    }

    @Test
    public void testGreaterThanOrEqual()
    {
        // representable
        testUnwrap("smallint", "a >= DOUBLE '1'", "a >= SMALLINT '1'");

        testUnwrap("bigint", "a >= DOUBLE '1'", "a >= BIGINT '1'");

        // non-representable
        testUnwrap("smallint", "a >= DOUBLE '1.1'", "a > SMALLINT '1'");

        testUnwrap("bigint", "a >= DOUBLE '1.1'", "a > BIGINT '1'");

        testUnwrap("smallint", "a >= DOUBLE '1.9'", "a >= SMALLINT '2'");

        // below top of range
        testUnwrap("smallint", "a >= DOUBLE '32766'", "a >= SMALLINT '32766'");

        // round to top of range
        testUnwrap("smallint", "a >= DOUBLE '32766.9'", "a = SMALLINT '32767'");

        // top of range
        testUnwrap("smallint", "a >= DOUBLE '32767'", "a = SMALLINT '32767'");

        // above range
        testUnwrap("smallint", "a >= DOUBLE '32768.1'", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        // above bottom of range
        testUnwrap("smallint", "a >= DOUBLE '-32767'", "a >= SMALLINT '-32767'");

        // round to bottom of range
        testUnwrap("smallint", "a >= DOUBLE '-32767.9'", "a > SMALLINT '-32768' ");

        // bottom of range
        testUnwrap("smallint", "a >= DOUBLE '-32768'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // below range
        testUnwrap("smallint", "a >= DOUBLE '-32768.1'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        // -2^64 constant
        testUnwrap("bigint", "a >= DOUBLE '-18446744073709551616'", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");
    }

    @Test
    public void testDistinctFrom()
    {
        // representable
        testUnwrap("smallint", "a IS DISTINCT FROM DOUBLE '1'", "a IS DISTINCT FROM SMALLINT '1'");

        testUnwrap("bigint", "a IS DISTINCT FROM DOUBLE '1'", "a IS DISTINCT FROM BIGINT '1'");

        // non-representable
        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '1.1'");

        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '1.9'");

        testRemoveFilter("bigint", "a IS DISTINCT FROM DOUBLE '1.9'");

        // below top of range
        testUnwrap("smallint", "a IS DISTINCT FROM DOUBLE '32766'", "a IS DISTINCT FROM SMALLINT '32766'");

        // round to top of range
        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '32766.9'");

        // top of range
        testUnwrap("smallint", "a IS DISTINCT FROM DOUBLE '32767'", "a IS DISTINCT FROM SMALLINT '32767'");

        // above range
        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '32768.1'");

        // 2^64 constant
        testRemoveFilter("bigint", "a IS DISTINCT FROM DOUBLE '18446744073709551616'");

        // above bottom of range
        testUnwrap("smallint", "a IS DISTINCT FROM DOUBLE '-32767'", "a IS DISTINCT FROM SMALLINT '-32767'");

        // round to bottom of range
        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '-32767.9'");

        // bottom of range
        testUnwrap("smallint", "a IS DISTINCT FROM DOUBLE '-32768'", "a IS DISTINCT FROM SMALLINT '-32768'");

        // below range
        testRemoveFilter("smallint", "a IS DISTINCT FROM DOUBLE '-32768.1'");
    }

    @Test
    public void testNull()
    {
        testUnwrap("smallint", "a = CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("bigint", "a = CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a <> CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a > CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a < CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a >= CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a <= CAST(NULL AS DOUBLE)", "CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a IS DISTINCT FROM CAST(NULL AS DOUBLE)", "NOT (CAST(a AS DOUBLE) IS NULL)");

        testUnwrap("bigint", "a IS DISTINCT FROM CAST(NULL AS DOUBLE)", "NOT (CAST(a AS DOUBLE) IS NULL)");
    }

    @Test
    public void testNaN()
    {
        testUnwrap("smallint", "a = nan()", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("bigint", "a = nan()", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a < nan()", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("smallint", "a <> nan()", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        testRemoveFilter("smallint", "a IS DISTINCT FROM nan()");

        testRemoveFilter("bigint", "a IS DISTINCT FROM nan()");

        testUnwrap("real", "a = nan()", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("real", "a < nan()", "a IS NULL AND CAST(NULL AS BOOLEAN)");

        testUnwrap("real", "a <> nan()", "NOT (a IS NULL) OR CAST(NULL AS BOOLEAN)");

        testUnwrap("real", "a IS DISTINCT FROM nan()", "a IS DISTINCT FROM CAST(nan() AS REAL)");
    }

    @Test
    public void smokeTests()
    {
        // smoke tests for various type combinations
        for (String type : asList("SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE")) {
            testUnwrap("tinyint", format("a = %s '1'", type), "a = TINYINT '1'");
        }

        for (String type : asList("INTEGER", "BIGINT", "REAL", "DOUBLE")) {
            testUnwrap("smallint", format("a = %s '1'", type), "a = SMALLINT '1'");
        }

        for (String type : asList("BIGINT", "DOUBLE")) {
            testUnwrap("integer", format("a = %s '1'", type), "a = 1");
        }

        testUnwrap("real", "a = DOUBLE '1'", "a = REAL '1.0'");
    }

    @Test
    public void testTermOrder()
    {
        // ensure the optimization works when the terms of the comparison are reversed
        // vs the canonical <expr> <op> <literal> form
        assertPlan("SELECT * FROM (VALUES REAL '1') t(a) WHERE DOUBLE '1' = a",
                output(
                        filter("A = REAL '1.0'",
                                values("A"))));
    }

    @Test
    public void testCastDateToTimestampWithTimeZone()
    {
        Session session = getQueryRunner().getDefaultSession();

        Session utcSession = withZone(session, TimeZoneKey.UTC_KEY);
        // east of Greenwich
        Session warsawSession = withZone(session, TimeZoneKey.getTimeZoneKey("Europe/Warsaw"));
        // west of Greenwich
        Session losAngelesSession = withZone(session, TimeZoneKey.getTimeZoneKey("America/Los_Angeles"));

        // same zone
        testUnwrap(utcSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > DATE '2020-10-26'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18 Europe/Warsaw'", "a > DATE '2020-10-26'");
        testUnwrap(losAngelesSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18 America/Los_Angeles'", "a > DATE '2020-10-26'");

        // different zone
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > DATE '2020-10-26'");
        testUnwrap(losAngelesSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > DATE '2020-10-26'");

        // maximum precision
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18.123456789321 UTC'", "a > DATE '2020-10-26'");
        testUnwrap(losAngelesSession, "date", "a > TIMESTAMP '2020-10-26 11:02:18.123456789321 UTC'", "a > DATE '2020-10-26'");

        // DST forward -- Warsaw changed clock 1h forward on 2020-03-29T01:00 UTC (2020-03-29T02:00 local time)
        // Note that in given session input TIMESTAMP values  2020-03-29 02:31 and 2020-03-29 03:31 produce the same value 2020-03-29 01:31 UTC (conversion is not monotonic)
        // last before
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59 UTC'", "a > DATE '2020-03-29'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59.999 UTC'", "a > DATE '2020-03-29'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59.13 UTC'", "a > DATE '2020-03-29'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59.999999 UTC'", "a > DATE '2020-03-29'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59.999999999 UTC'", "a > DATE '2020-03-29'");
        testUnwrap(warsawSession, "date", "a > TIMESTAMP '2020-03-29 00:59:59.999999999999 UTC'", "a > DATE '2020-03-29'");

        // equal
        testUnwrap(utcSession, "date", "a = TIMESTAMP '1981-06-22 00:00:00 UTC'", "a = DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a = TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a = DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a = TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a = DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a = TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a = DATE '1981-06-22'");

        // not equal
        testUnwrap(utcSession, "date", "a <> TIMESTAMP '1981-06-22 00:00:00 UTC'", "a <> DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <> TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a <> DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <> TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a <> DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <> TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a <> DATE '1981-06-22'");

        // less than
        testUnwrap(utcSession, "date", "a < TIMESTAMP '1981-06-22 00:00:00 UTC'", "a < DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a < TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a < DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a < TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a < DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a < TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a < DATE '1981-06-22'");

        // less than or equal
        testUnwrap(utcSession, "date", "a <= TIMESTAMP '1981-06-22 00:00:00 UTC'", "a <= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <= TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a <= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <= TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a <= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a <= TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a <= DATE '1981-06-22'");

        // greater than
        testUnwrap(utcSession, "date", "a > TIMESTAMP '1981-06-22 00:00:00 UTC'", "a > DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a > TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a > DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a > TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a > DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a > TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a > DATE '1981-06-22'");

        // greater than or equal
        testUnwrap(utcSession, "date", "a >= TIMESTAMP '1981-06-22 00:00:00 UTC'", "a >= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a >= TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a >= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a >= DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a >= DATE '1981-06-22'");

        // is distinct
        testUnwrap(utcSession, "date", "a IS DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00 UTC'", "a IS DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a IS DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a IS DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a IS DISTINCT FROM DATE '1981-06-22'");

        // is not distinct
        testUnwrap(utcSession, "date", "a IS NOT DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00 UTC'", "a IS NOT DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS NOT DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000 UTC'", "a IS NOT DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS NOT DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000000 UTC'", "a IS NOT DISTINCT FROM DATE '1981-06-22'");
        testUnwrap(utcSession, "date", "a IS NOT DISTINCT FROM TIMESTAMP '1981-06-22 00:00:00.000000000000 UTC'", "a IS NOT DISTINCT FROM DATE '1981-06-22'");

        // null date literal
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) = NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) < NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) <= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) > NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) >= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("date", "CAST(a AS TIMESTAMP WITH TIME ZONE) IS DISTINCT FROM NULL", "NOT(CAST(a AS TIMESTAMP WITH TIME ZONE) IS NULL)");

        // timestamp with time zone value on the left
        testUnwrap(utcSession, "date", "TIMESTAMP '1981-06-22 00:00:00 UTC' = a", "a = DATE '1981-06-22'");
    }

    @Test
    public void testCastTimestampToTimestampWithTimeZone()
    {
        Session session = getQueryRunner().getDefaultSession();

        Session utcSession = withZone(session, TimeZoneKey.UTC_KEY);
        // east of Greenwich
        Session warsawSession = withZone(session, TimeZoneKey.getTimeZoneKey("Europe/Warsaw"));
        // west of Greenwich
        Session losAngelesSession = withZone(session, TimeZoneKey.getTimeZoneKey("America/Los_Angeles"));

        // same zone
        testUnwrap(utcSession, "timestamp(0)", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > TIMESTAMP '2020-10-26 11:02:18'");
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-26 11:02:18 Europe/Warsaw'", "a > TIMESTAMP '2020-10-26 11:02:18'");
        testUnwrap(losAngelesSession, "timestamp(0)", "a > TIMESTAMP '2020-10-26 11:02:18 America/Los_Angeles'", "a > TIMESTAMP '2020-10-26 11:02:18'");

        // different zone
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > TIMESTAMP '2020-10-26 12:02:18'");
        testUnwrap(losAngelesSession, "timestamp(0)", "a > TIMESTAMP '2020-10-26 11:02:18 UTC'", "a > TIMESTAMP '2020-10-26 04:02:18'");

        // short timestamp, short timestamp with time zone being coerced to long timestamp with time zone
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-10-26 11:02:18.12 UTC'", "a > TIMESTAMP '2020-10-26 12:02:18.120000'");
        testUnwrap(losAngelesSession, "timestamp(6)", "a > TIMESTAMP '2020-10-26 11:02:18.12 UTC'", "a > TIMESTAMP '2020-10-26 04:02:18.120000'");

        // long timestamp, short timestamp with time zone being coerced to long timestamp with time zone
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-26 11:02:18.12 UTC'", "a > TIMESTAMP '2020-10-26 12:02:18.120000000'");
        testUnwrap(losAngelesSession, "timestamp(9)", "a > TIMESTAMP '2020-10-26 11:02:18.12 UTC'", "a > TIMESTAMP '2020-10-26 04:02:18.120000000'");

        // long timestamp, long timestamp with time zone
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-26 11:02:18.123456 UTC'", "a > TIMESTAMP '2020-10-26 12:02:18.123456000'");
        testUnwrap(losAngelesSession, "timestamp(9)", "a > TIMESTAMP '2020-10-26 11:02:18.123456 UTC'", "a > TIMESTAMP '2020-10-26 04:02:18.123456000'");

        // maximum precision
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-10-26 11:02:18.123456789321 UTC'", "a > TIMESTAMP '2020-10-26 12:02:18.123456789321'");
        testUnwrap(losAngelesSession, "timestamp(12)", "a > TIMESTAMP '2020-10-26 11:02:18.123456789321 UTC'", "a > TIMESTAMP '2020-10-26 04:02:18.123456789321'");

        // DST forward -- Warsaw changed clock 1h forward on 2020-03-29T01:00 UTC (2020-03-29T02:00 local time)
        // Note that in given session input TIMESTAMP values  2020-03-29 02:31 and 2020-03-29 03:31 produce the same value 2020-03-29 01:31 UTC (conversion is not monotonic)
        // last before
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-03-29 00:59:59 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-03-29 00:59:59.999 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59.999'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-03-29 00:59:59.13 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59.130000'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-03-29 00:59:59.999999 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59.999999'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-03-29 00:59:59.999999999 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59.999999999'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-03-29 00:59:59.999999999999 UTC'", "a > TIMESTAMP '2020-03-29 01:59:59.999999999999'");
        // first within
        testNoUnwrap(warsawSession, "timestamp(0)", "> TIMESTAMP '2020-03-29 01:00:00 UTC'", "timestamp(0) with time zone");
        testNoUnwrap(warsawSession, "timestamp(3)", "> TIMESTAMP '2020-03-29 01:00:00.000 UTC'", "timestamp(3) with time zone");
        testNoUnwrap(warsawSession, "timestamp(6)", "> TIMESTAMP '2020-03-29 01:00:00.000000 UTC'", "timestamp(6) with time zone");
        testNoUnwrap(warsawSession, "timestamp(9)", "> TIMESTAMP '2020-03-29 01:00:00.000000000 UTC'", "timestamp(9) with time zone");
        testNoUnwrap(warsawSession, "timestamp(12)", "> TIMESTAMP '2020-03-29 01:00:00.000000000000 UTC'", "timestamp(12) with time zone");
        // last within
        testNoUnwrap(warsawSession, "timestamp(0)", "> TIMESTAMP '2020-03-29 01:59:59 UTC'", "timestamp(0) with time zone");
        testNoUnwrap(warsawSession, "timestamp(3)", "> TIMESTAMP '2020-03-29 01:59:59.999 UTC'", "timestamp(3) with time zone");
        testNoUnwrap(warsawSession, "timestamp(6)", "> TIMESTAMP '2020-03-29 01:59:59.999999 UTC'", "timestamp(6) with time zone");
        testNoUnwrap(warsawSession, "timestamp(9)", "> TIMESTAMP '2020-03-29 01:59:59.999999999 UTC'", "timestamp(9) with time zone");
        testNoUnwrap(warsawSession, "timestamp(12)", "> TIMESTAMP '2020-03-29 01:59:59.999999999999 UTC'", "timestamp(12) with time zone");
        // first after
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-03-29 02:00:00 UTC'", "a > TIMESTAMP '2020-03-29 04:00:00'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-03-29 02:00:00.000 UTC'", "a > TIMESTAMP '2020-03-29 04:00:00.000'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-03-29 02:00:00.000000 UTC'", "a > TIMESTAMP '2020-03-29 04:00:00.000000'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-03-29 02:00:00.000000000 UTC'", "a > TIMESTAMP '2020-03-29 04:00:00.000000000'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-03-29 02:00:00.000000000000 UTC'", "a > TIMESTAMP '2020-03-29 04:00:00.000000000000'");

        // DST backward -- Warsaw changed clock 1h backward on 2020-10-25T01:00 UTC (2020-03-29T03:00 local time)
        // Note that in given session no input TIMESTAMP value can produce TIMESTAMP WITH TIME ZONE within [2020-10-25 00:00:00 UTC, 2020-10-25 01:00:00 UTC], so '>=' is OK
        // last before
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-25 00:59:59 UTC'", "a >= TIMESTAMP '2020-10-25 02:59:59'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-10-25 00:59:59.999 UTC'", "a >= TIMESTAMP '2020-10-25 02:59:59.999'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-10-25 00:59:59.999999 UTC'", "a >= TIMESTAMP '2020-10-25 02:59:59.999999'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-25 00:59:59.999999999 UTC'", "a >= TIMESTAMP '2020-10-25 02:59:59.999999999'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-10-25 00:59:59.999999999999 UTC'", "a >= TIMESTAMP '2020-10-25 02:59:59.999999999999'");
        // first within
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-25 01:00:00 UTC'", "a > TIMESTAMP '2020-10-25 02:00:00'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-10-25 01:00:00.000 UTC'", "a > TIMESTAMP '2020-10-25 02:00:00.000'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-10-25 01:00:00.000000 UTC'", "a > TIMESTAMP '2020-10-25 02:00:00.000000'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-25 01:00:00.000000000 UTC'", "a > TIMESTAMP '2020-10-25 02:00:00.000000000'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-10-25 01:00:00.000000000000 UTC'", "a > TIMESTAMP '2020-10-25 02:00:00.000000000000'");
        // last within
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-25 01:59:59 UTC'", "a > TIMESTAMP '2020-10-25 02:59:59'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-10-25 01:59:59.999 UTC'", "a > TIMESTAMP '2020-10-25 02:59:59.999'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-10-25 01:59:59.999999 UTC'", "a > TIMESTAMP '2020-10-25 02:59:59.999999'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-25 01:59:59.999999999 UTC'", "a > TIMESTAMP '2020-10-25 02:59:59.999999999'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-10-25 01:59:59.999999999999 UTC'", "a > TIMESTAMP '2020-10-25 02:59:59.999999999999'");
        // first after
        testUnwrap(warsawSession, "timestamp(0)", "a > TIMESTAMP '2020-10-25 02:00:00 UTC'", "a > TIMESTAMP '2020-10-25 03:00:00'");
        testUnwrap(warsawSession, "timestamp(3)", "a > TIMESTAMP '2020-10-25 02:00:00.000 UTC'", "a > TIMESTAMP '2020-10-25 03:00:00.000'");
        testUnwrap(warsawSession, "timestamp(6)", "a > TIMESTAMP '2020-10-25 02:00:00.000000 UTC'", "a > TIMESTAMP '2020-10-25 03:00:00.000000'");
        testUnwrap(warsawSession, "timestamp(9)", "a > TIMESTAMP '2020-10-25 02:00:00.000000000 UTC'", "a > TIMESTAMP '2020-10-25 03:00:00.000000000'");
        testUnwrap(warsawSession, "timestamp(12)", "a > TIMESTAMP '2020-10-25 02:00:00.000000000000 UTC'", "a > TIMESTAMP '2020-10-25 03:00:00.000000000000'");
    }

    @Test
    public void testNoEffect()
    {
        // BIGINT->DOUBLE implicit cast is not injective if the double constant is >= 2^53 and <= double(2^63 - 1)
        testUnwrap("bigint", "a = DOUBLE '9007199254740992'", "CAST(a AS DOUBLE) = 9.007199254740992E15");

        testUnwrap("bigint", "a = DOUBLE '9223372036854775807'", "CAST(a AS DOUBLE) = 9.223372036854776E18");

        // BIGINT->DOUBLE implicit cast is not injective if the double constant is <= -2^53 and >= double(-2^63 + 1)
        testUnwrap("bigint", "a = DOUBLE '-9007199254740992'", "CAST(a AS DOUBLE) = -9.007199254740992E15");

        testUnwrap("bigint", "a = DOUBLE '-9223372036854775807'", "CAST(a AS DOUBLE) = -9.223372036854776E18");

        // BIGINT->REAL implicit cast is not injective if the real constant is >= 2^23 and <= real(2^63 - 1)
        testUnwrap("bigint", "a = REAL '8388608'", "CAST(a AS REAL) = REAL '8388608.0'");

        testUnwrap("bigint", "a = REAL '9223372036854775807'", "CAST(a AS REAL) = REAL '9.223372E18'");

        // BIGINT->REAL implicit cast is not injective if the real constant is <= -2^23 and >= real(-2^63 + 1)
        testUnwrap("bigint", "a = REAL '-8388608'", "CAST(a AS REAL) = REAL '-8388608.0'");

        testUnwrap("bigint", "a = REAL '-9223372036854775807'", "CAST(a AS REAL) = REAL '-9.223372E18'");

        // INTEGER->REAL implicit cast is not injective if the real constant is >= 2^23 and <= 2^31 - 1
        testUnwrap("integer", "a = REAL '8388608'", "CAST(a AS REAL) = REAL '8388608.0'");

        testUnwrap("integer", "a = REAL '2147483647'", Runtime.version().feature() >= 19
                ? "CAST(a AS REAL) = REAL '2.1474836E9'"
                : "CAST(a AS REAL) = REAL '2.14748365E9'");

        // INTEGER->REAL implicit cast is not injective if the real constant is <= -2^23 and >= -2^31 + 1
        testUnwrap("integer", "a = REAL '-8388608'", "CAST(a AS REAL) = REAL '-8388608.0'");

        testUnwrap("integer", "a = REAL '-2147483647'", Runtime.version().feature() >= 19
                ? "CAST(a AS REAL) = REAL '-2.1474836E9'"
                : "CAST(a AS REAL) = REAL '-2.14748365E9'");

        // DECIMAL(p)->DOUBLE not injective for p > 15
        testUnwrap("decimal(16)", "a = DOUBLE '1'", "CAST(a AS DOUBLE) = 1E0");

        // DECIMAL(p)->REAL not injective for p > 7
        testUnwrap("decimal(8)", "a = REAL '1'", "CAST(a AS REAL) = REAL '1.0'");

        // no implicit cast between VARCHAR->INTEGER
        testUnwrap("varchar", "CAST(a AS INTEGER) = INTEGER '1'", "CAST(a AS INTEGER) = 1");

        // no implicit cast between DOUBLE->INTEGER
        testUnwrap("double", "CAST(a AS INTEGER) = INTEGER '1'", "CAST(a AS INTEGER) = 1");
    }

    @Test
    public void testUnwrapCastTimestampAsDate()
    {
        // equal
        testUnwrap("timestamp(3)", "CAST(a AS DATE) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // not equal
        testUnwrap("timestamp(3)", "CAST(a AS DATE) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // less than
        testUnwrap("timestamp(3)", "CAST(a AS DATE) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000000'");

        // less than or equal
        testUnwrap("timestamp(3)", "CAST(a AS DATE) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // greater than
        testUnwrap("timestamp(3)", "CAST(a AS DATE) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // greater than or equal
        testUnwrap("timestamp(3)", "CAST(a AS DATE) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000000'");

        // is distinct
        testUnwrap("timestamp(3)", "CAST(a AS DATE) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // is not distinct
        testUnwrap("timestamp(3)", "CAST(a AS DATE) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "CAST(a AS DATE) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "CAST(a AS DATE) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "CAST(a AS DATE) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // null date literal
        testUnwrap("timestamp(3)", "CAST(a AS DATE) = NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "CAST(a AS DATE) < NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "CAST(a AS DATE) <= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "CAST(a AS DATE) > NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "CAST(a AS DATE) >= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "CAST(a AS DATE) IS DISTINCT FROM NULL", "NOT(CAST(a AS DATE) IS NULL)");

        // non-optimized expression on the right
        testUnwrap("timestamp(3)", "CAST(a AS DATE) = DATE '1981-06-22' + INTERVAL '2' DAY", "a >= TIMESTAMP '1981-06-24 00:00:00.000' AND a < TIMESTAMP '1981-06-25 00:00:00.000'");

        // cast on the right
        testUnwrap("timestamp(3)", "DATE '1981-06-22' = CAST(a AS DATE)", "a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
    }

    @Test
    public void testUnwrapConvertTimestatmpToDate()
    {
        // equal
        testUnwrap("timestamp(3)", "date(a) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) = DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // not equal
        testUnwrap("timestamp(3)", "date(a) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) <> DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // less than
        testUnwrap("timestamp(3)", "date(a) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) < DATE '1981-06-22'", "a < TIMESTAMP '1981-06-22 00:00:00.000000000000'");

        // less than or equal
        testUnwrap("timestamp(3)", "date(a) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) <= DATE '1981-06-22'", "a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // greater than
        testUnwrap("timestamp(3)", "date(a) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) > DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // greater than or equal
        testUnwrap("timestamp(3)", "date(a) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) >= DATE '1981-06-22'", "a >= TIMESTAMP '1981-06-22 00:00:00.000000000000'");

        // is distinct
        testUnwrap("timestamp(3)", "date(a) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) IS DISTINCT FROM DATE '1981-06-22'", "a IS NULL OR a < TIMESTAMP '1981-06-22 00:00:00.000000000000' OR a >= TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // is not distinct
        testUnwrap("timestamp(3)", "date(a) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
        testUnwrap("timestamp(6)", "date(a) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000'");
        testUnwrap("timestamp(9)", "date(a) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000'");
        testUnwrap("timestamp(12)", "date(a) IS NOT DISTINCT FROM DATE '1981-06-22'", "(NOT a IS NULL) AND a >= TIMESTAMP '1981-06-22 00:00:00.000000000000' AND a < TIMESTAMP '1981-06-23 00:00:00.000000000000'");

        // null date literal
        testUnwrap("timestamp(3)", "date(a) = NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "date(a) < NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "date(a) <= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "date(a) > NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "date(a) >= NULL", "CAST(NULL AS BOOLEAN)");
        testUnwrap("timestamp(3)", "date(a) IS DISTINCT FROM NULL", "NOT(CAST(a AS DATE) IS NULL)");

        // non-optimized expression on the right
        testUnwrap("timestamp(3)", "date(a) = DATE '1981-06-22' + INTERVAL '2' DAY", "a >= TIMESTAMP '1981-06-24 00:00:00.000' AND a < TIMESTAMP '1981-06-25 00:00:00.000'");

        // cast on the right
        testUnwrap("timestamp(3)", "DATE '1981-06-22' = date(a)", "a >= TIMESTAMP '1981-06-22 00:00:00.000' AND a < TIMESTAMP '1981-06-23 00:00:00.000'");
    }

    private void testNoUnwrap(String inputType, String inputPredicate, String expectedCastType)
    {
        testNoUnwrap(getQueryRunner().getDefaultSession(), inputType, inputPredicate, expectedCastType);
    }

    private void testNoUnwrap(Session session, String inputType, String inputPredicate, String expectedCastType)
    {
        assertPlan(format("SELECT * FROM (VALUES CAST(NULL AS %s)) t(a) WHERE a %s", inputType, inputPredicate),
                session,
                output(
                        filter(format("CAST(a AS %s) %s", expectedCastType, inputPredicate),
                                values("a"))));
    }

    private void testRemoveFilter(String inputType, String inputPredicate)
    {
        assertPlan(format("SELECT * FROM (VALUES CAST(NULL AS %s)) t(a) WHERE %s AND rand() = 42", inputType, inputPredicate),
                output(
                        filter("rand() = 42e0",
                                values("a"))));
    }

    private void testUnwrap(String inputType, String inputPredicate, String expectedPredicate)
    {
        testUnwrap(getQueryRunner().getDefaultSession(), inputType, inputPredicate, expectedPredicate);
    }

    private void testUnwrap(Session session, String inputType, String inputPredicate, String expectedPredicate)
    {
        assertPlan(format("SELECT * FROM (VALUES CAST(NULL AS %s)) t(a) WHERE %s OR rand() = 42", inputType, inputPredicate),
                session,
                output(
                        filter(format("%s OR rand() = 42e0", expectedPredicate),
                                values("a"))));
    }

    private static Session withZone(Session session, TimeZoneKey timeZoneKey)
    {
        return Session.builder(requireNonNull(session, "session is null"))
                .setTimeZoneKey(requireNonNull(timeZoneKey, "timeZoneKey is null"))
                .build();
    }
}
