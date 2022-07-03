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
import io.trino.Session;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlTimestampWithTimeZone;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.Type;
import io.trino.testing.TestingConnectorSession;
import io.trino.testing.TestingSession;
import io.trino.type.SqlIntervalDayTime;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Hours;
import org.joda.time.Minutes;
import org.joda.time.ReadableInstant;
import org.joda.time.Seconds;
import org.joda.time.chrono.ISOChronology;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.trino.operator.scalar.DateTimeFunctions.currentDate;
import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKeyForOffset;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_NANOS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.DateTimeTestingUtils.sqlTimeOf;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.trino.util.DateTimeZoneIndex.getDateTimeZone;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.joda.time.DateTimeUtils.getInstantChronology;
import static org.joda.time.Days.daysBetween;
import static org.joda.time.DurationFieldType.millis;
import static org.joda.time.Months.monthsBetween;
import static org.joda.time.Weeks.weeksBetween;
import static org.joda.time.Years.yearsBetween;
import static org.testng.Assert.assertEquals;

public class TestDateTimeFunctions
        extends AbstractTestFunctions
{
    protected static final TimeZoneKey TIME_ZONE_KEY = TestingSession.DEFAULT_TIME_ZONE_KEY;
    protected static final DateTimeZone DATE_TIME_ZONE = getDateTimeZone(TIME_ZONE_KEY);
    protected static final DateTimeZone UTC_TIME_ZONE = getDateTimeZone(UTC_KEY);
    protected static final DateTimeZone DATE_TIME_ZONE_NUMERICAL = getDateTimeZone(getTimeZoneKey("-11:00"));
    protected static final TimeZoneKey KATHMANDU_ZONE_KEY = getTimeZoneKey("Asia/Kathmandu");
    protected static final DateTimeZone KATHMANDU_ZONE = getDateTimeZone(KATHMANDU_ZONE_KEY);
    protected static final ZoneOffset WEIRD_ZONE = ZoneOffset.ofHoursMinutes(7, 9);
    protected static final DateTimeZone WEIRD_DATE_TIME_ZONE = DateTimeZone.forID(WEIRD_ZONE.getId());

    protected static final DateTime DATE = new DateTime(2001, 8, 22, 0, 0, 0, 0, DateTimeZone.UTC);
    protected static final String DATE_LITERAL = "DATE '2001-08-22'";
    protected static final String DATE_ISO8601_STRING = "2001-08-22";

    protected static final LocalTime TIME = LocalTime.of(3, 4, 5, 321_000_000);
    protected static final String TIME_LITERAL = "TIME '03:04:05.321'";
    protected static final OffsetTime WEIRD_TIME = OffsetTime.of(3, 4, 5, 321_000_000, WEIRD_ZONE);
    protected static final String WEIRD_TIME_LITERAL = "TIME '03:04:05.321 +07:09'";

    protected static final DateTime TIMESTAMP = new DateTime(2001, 8, 22, 3, 4, 5, 321, UTC_TIME_ZONE); // This is TIMESTAMP w/o TZ
    protected static final DateTime TIMESTAMP_WITH_NUMERICAL_ZONE = new DateTime(2001, 8, 22, 3, 4, 5, 321, DATE_TIME_ZONE_NUMERICAL);
    protected static final String TIMESTAMP_LITERAL = "TIMESTAMP '2001-08-22 03:04:05.321'";
    protected static final String TIMESTAMP_ISO8601_STRING = "2001-08-22T03:04:05.321-11:00";
    protected static final String TIMESTAMP_ISO8601_STRING_NO_TIME_ZONE = "2001-08-22T03:04:05.321";
    protected static final DateTime WEIRD_TIMESTAMP = new DateTime(2001, 8, 22, 3, 4, 5, 321, WEIRD_DATE_TIME_ZONE);
    protected static final String WEIRD_TIMESTAMP_LITERAL = "TIMESTAMP '2001-08-22 03:04:05.321 +07:09'";
    protected static final String WEIRD_TIMESTAMP_ISO8601_STRING = "2001-08-22T03:04:05.321+07:09";

    protected static final String INTERVAL_LITERAL = "INTERVAL '90061.234' SECOND";
    protected static final Duration DAY_TO_SECOND_INTERVAL = Duration.ofMillis(90061234);

    public TestDateTimeFunctions()
    {
        super(testSessionBuilder()
                .setTimeZoneKey(TIME_ZONE_KEY)
                .setStart(Instant.ofEpochMilli(new DateTime(2017, 4, 1, 12, 34, 56, 789, UTC_TIME_ZONE).getMillis()))
                .build());
    }

    @Test
    public void testToIso8601ForTimestampWithoutTimeZone()
    {
        assertFunction("to_iso8601(" + TIMESTAMP_LITERAL + ")", createVarcharType(26), TIMESTAMP_ISO8601_STRING_NO_TIME_ZONE);
    }

    @Test
    public void testCurrentDate()
    {
        // current date is the time at midnight in the session time zone
        assertFunction("CURRENT_DATE", DateType.DATE, new SqlDate(toIntExact(epochDaysInZone(TIME_ZONE_KEY, session.getStart()))));
    }

    @Test
    public void testCurrentDateTimezone()
    {
        TimeZoneKey kievTimeZoneKey = getTimeZoneKey("Europe/Kiev");
        TimeZoneKey bahiaBanderasTimeZoneKey = getTimeZoneKey("America/Bahia_Banderas"); // The zone has 'gap' on 1970-01-01
        TimeZoneKey montrealTimeZoneKey = getTimeZoneKey("America/Montreal");
        long timeIncrement = TimeUnit.MINUTES.toMillis(53);
        // We expect UTC millis later on so we have to use UTC chronology
        for (long millis = ISOChronology.getInstanceUTC().getDateTimeMillis(2000, 6, 15, 0, 0, 0, 0);
                millis < ISOChronology.getInstanceUTC().getDateTimeMillis(2016, 6, 15, 0, 0, 0, 0);
                millis += timeIncrement) {
            Instant instant = Instant.ofEpochMilli(millis);
            assertCurrentDateAtInstant(kievTimeZoneKey, instant);
            assertCurrentDateAtInstant(bahiaBanderasTimeZoneKey, instant);
            assertCurrentDateAtInstant(montrealTimeZoneKey, instant);
            assertCurrentDateAtInstant(TIME_ZONE_KEY, instant);
        }
    }

    private void assertCurrentDateAtInstant(TimeZoneKey timeZoneKey, Instant instant)
    {
        long expectedDays = epochDaysInZone(timeZoneKey, instant);
        TestingConnectorSession connectorSession = TestingConnectorSession.builder()
                .setStart(instant)
                .setTimeZoneKey(timeZoneKey)
                .build();
        long dateTimeCalculation = currentDate(connectorSession);
        assertEquals(dateTimeCalculation, expectedDays);
    }

    private static long epochDaysInZone(TimeZoneKey timeZoneKey, Instant instant)
    {
        return LocalDate.from(instant.atZone(ZoneId.of(timeZoneKey.getId()))).toEpochDay();
    }

    @Test
    public void testLocalTime()
    {
        functionAssertions.assertFunctionString("localtime", TimeType.TIME, "02:34:56.789");

        Session localSession = Session.builder(session)
                .setStart(Instant.ofEpochMilli(new DateTime(2017, 3, 1, 14, 30, 0, 0, DATE_TIME_ZONE).getMillis()))
                .build();
        try (FunctionAssertions localAssertion = new FunctionAssertions(localSession)) {
            localAssertion.assertFunctionString("localtime", TimeType.TIME, "14:30:00.000");
        }

        localSession = Session.builder(session)
                // we use Asia/Kathmandu here, as it has different zone offset on 2017-03-01 and on 1970-01-01
                .setTimeZoneKey(KATHMANDU_ZONE_KEY)
                .setStart(Instant.ofEpochMilli(new DateTime(2017, 3, 1, 15, 45, 0, 0, KATHMANDU_ZONE).getMillis()))
                .build();
        try (FunctionAssertions localAssertion = new FunctionAssertions(localSession)) {
            localAssertion.assertFunctionString("localtime", TimeType.TIME, "15:45:00.000");
        }
    }

    @Test
    public void testCurrentTime()
    {
        functionAssertions.assertFunctionString("current_time", TIME_WITH_TIME_ZONE, "02:34:56.789+14:00");

        Session localSession = Session.builder(session)
                // we use Asia/Kathmandu here, as it has different zone offset on 2017-03-01 and on 1970-01-01
                .setTimeZoneKey(KATHMANDU_ZONE_KEY)
                .setStart(Instant.ofEpochMilli(new DateTime(2017, 3, 1, 15, 45, 0, 0, KATHMANDU_ZONE).getMillis()))
                .build();
        try (FunctionAssertions localAssertion = new FunctionAssertions(localSession)) {
            localAssertion.assertFunctionString("current_time", TIME_WITH_TIME_ZONE, "15:45:00.000+05:45");
        }
    }

    @Test
    public void testFromUnixTime()
    {
        DateTime dateTime = new DateTime(2001, 1, 22, 3, 4, 5, 0, DATE_TIME_ZONE);
        double seconds = dateTime.getMillis() / 1000.0;
        assertFunction("from_unixtime(" + seconds + ")", TIMESTAMP_TZ_MILLIS, SqlTimestampWithTimeZone.newInstance(3, dateTime.getMillis(), 0, TIME_ZONE_KEY));

        dateTime = new DateTime(2001, 1, 22, 3, 4, 5, 888, DATE_TIME_ZONE);
        seconds = dateTime.getMillis() / 1000.0;
        assertFunction("from_unixtime(" + seconds + ")", TIMESTAMP_TZ_MILLIS, SqlTimestampWithTimeZone.newInstance(3, dateTime.getMillis(), 0, TIME_ZONE_KEY));
    }

    @Test
    public void testFromUnixTimeNanos()
    {
        // long
        assertFunction("from_unixtime_nanos(1234567890123456789)", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 1234567890_123L, 456789000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(999999999)", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 999, 999999000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(-1234567890123456789)", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1234567890_124L, 543211000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(-999999999)", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1000, 1000, TIME_ZONE_KEY));

        // short decimal
        assertFunction("from_unixtime_nanos(DECIMAL '1234')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 0, 1234000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '1234.0')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 0, 1234000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '1234.499')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 0, 1234000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '1234.500')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 0, 1235000, TIME_ZONE_KEY));

        assertFunction("from_unixtime_nanos(DECIMAL '-1234')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1, 998766000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-1234.0')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1, 998766000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-1234.499')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1, 998766000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-1234.500')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -1, 998765000, TIME_ZONE_KEY));

        // long decimal
        assertFunction("from_unixtime_nanos(DECIMAL '12345678900123456789')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 12345678900_123L, 456789000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '12345678900123456789.000000')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 12345678900_123L, 456789000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '12345678900123456789.499')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 12345678900_123L, 456789000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '12345678900123456789.500')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, 12345678900_123L, 456790000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-12345678900123456789')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -12345678900_124L, 543211000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-12345678900123456789.000000')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -12345678900_124L, 543211000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-12345678900123456789.499')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -12345678900_124L, 543211000, TIME_ZONE_KEY));
        assertFunction("from_unixtime_nanos(DECIMAL '-12345678900123456789.500')", TIMESTAMP_TZ_NANOS, SqlTimestampWithTimeZone.newInstance(9, -12345678900_124L, 543210000, TIME_ZONE_KEY));
    }

    @Test
    public void testFromUnixTimeWithOffset()
    {
        DateTime dateTime = new DateTime(2001, 1, 22, 3, 4, 5, 0, DATE_TIME_ZONE);
        double seconds = dateTime.getMillis() / 1000.0;

        int timeZoneHoursOffset = 1;
        int timezoneMinutesOffset = 10;

        DateTime expected = new DateTime(dateTime, getDateTimeZone(getTimeZoneKeyForOffset((timeZoneHoursOffset * 60L) + timezoneMinutesOffset)));
        assertFunction("from_unixtime(" + seconds + ", " + timeZoneHoursOffset + ", " + timezoneMinutesOffset + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        // test invalid minute offsets
        assertInvalidFunction("from_unixtime(0, 1, 10000)", INVALID_FUNCTION_ARGUMENT);
        assertInvalidFunction("from_unixtime(0, 10000, 0)", INVALID_FUNCTION_ARGUMENT);
        assertInvalidFunction("from_unixtime(0, -100, 100)", INVALID_FUNCTION_ARGUMENT);
    }

    @Test
    public void testFromUnixTimeWithTimeZone()
    {
        String zoneId = "Asia/Shanghai";
        DateTime expected = new DateTime(1970, 1, 1, 10, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        zoneId = "Asia/Tokyo";
        expected = new DateTime(1970, 1, 1, 11, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        zoneId = "Europe/Moscow";
        expected = new DateTime(1970, 1, 1, 5, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        zoneId = "America/New_York";
        expected = new DateTime(1969, 12, 31, 21, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        zoneId = "America/Chicago";
        expected = new DateTime(1969, 12, 31, 20, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));

        zoneId = "America/Los_Angeles";
        expected = new DateTime(1969, 12, 31, 18, 0, 0, DateTimeZone.forID(zoneId));
        assertFunction(format("from_unixtime(7200, '%s')", zoneId), TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(expected));
    }

    @Test
    public void testDate()
    {
        assertFunction("date('" + DATE_ISO8601_STRING + "')", DateType.DATE, toDate(DATE));
        assertFunction("date(" + WEIRD_TIMESTAMP_LITERAL + ")", DateType.DATE, toDate(DATE));
        assertFunction("date(" + TIMESTAMP_LITERAL + ")", DateType.DATE, toDate(DATE));
    }

    @Test
    public void testFromISO8601()
    {
        assertFunction("from_iso8601_timestamp('" + TIMESTAMP_ISO8601_STRING + "')", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(TIMESTAMP_WITH_NUMERICAL_ZONE));
        assertFunction("from_iso8601_timestamp('" + WEIRD_TIMESTAMP_ISO8601_STRING + "')", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP));
        assertFunction("from_iso8601_date('" + DATE_ISO8601_STRING + "')", DateType.DATE, toDate(DATE));
    }

    @Test
    public void testFromIso8601Nanos()
    {
        Instant instant = ZonedDateTime.of(2001, 8, 22, 12, 34, 56, 123456789, ZoneId.of("UTC")).toInstant();

        assertFunction("from_iso8601_timestamp_nanos('2001-08-22T12:34:56.123456789Z')", TIMESTAMP_TZ_NANOS,
                SqlTimestampWithTimeZone.fromInstant(9, instant, ZoneId.of("UTC")));
        assertFunction("from_iso8601_timestamp_nanos('2001-08-22T07:34:56.123456789-05:00')", TIMESTAMP_TZ_NANOS,
                SqlTimestampWithTimeZone.fromInstant(9, instant, ZoneId.of("-0500")));
        assertFunction("from_iso8601_timestamp_nanos('2001-08-22T13:34:56.123456789+01:00')", TIMESTAMP_TZ_NANOS,
                SqlTimestampWithTimeZone.fromInstant(9, instant, ZoneId.of("+0100")));
        assertFunction("from_iso8601_timestamp_nanos('2001-08-22T12:34:56.123Z')", TIMESTAMP_TZ_NANOS,
                SqlTimestampWithTimeZone.fromInstant(9, instant.minusNanos(456789), ZoneId.of("UTC")));

        // make sure that strings without a timezone are parsed in the session local time
        ZoneId nineHoursBehindZone = ZoneId.of("-0900");
        Session localSession = Session.builder(session)
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(nineHoursBehindZone.getId()))
                .build();
        try (FunctionAssertions localAssertion = new FunctionAssertions(localSession)) {
            localAssertion.assertFunction("from_iso8601_timestamp_nanos('2001-08-22T03:34:56.123456789')", TIMESTAMP_TZ_NANOS,
                    SqlTimestampWithTimeZone.fromInstant(9, instant, nineHoursBehindZone));
        }
    }

    @Test
    public void testToIso8601()
    {
        assertFunction("to_iso8601(" + WEIRD_TIMESTAMP_LITERAL + ")", createVarcharType(32), WEIRD_TIMESTAMP_ISO8601_STRING);
        assertFunction("to_iso8601(" + DATE_LITERAL + ")", createVarcharType(16), DATE_ISO8601_STRING);
    }

    @Test
    public void testTimeZone()
    {
        assertFunction("hour(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getHourOfDay());
        assertFunction("minute(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMinuteOfHour());
        assertFunction("hour(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getHourOfDay());
        assertFunction("minute(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMinuteOfHour());
        assertFunction("current_timezone()", VARCHAR, TIME_ZONE_KEY.getId());
    }

    @Test
    public void testPartFunctions()
    {
        assertFunction("millisecond(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMillisOfSecond());
        assertFunction("second(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getSecondOfMinute());
        assertFunction("minute(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMinuteOfHour());
        assertFunction("hour(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getHourOfDay());
        assertFunction("day_of_week(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.dayOfWeek().get());
        assertFunction("dow(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.dayOfWeek().get());
        assertFunction("day(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfMonth());
        assertFunction("day_of_month(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfMonth());
        assertFunction("day_of_year(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.dayOfYear().get());
        assertFunction("doy(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.dayOfYear().get());
        assertFunction("week(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.weekOfWeekyear().get());
        assertFunction("week_of_year(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.weekOfWeekyear().get());
        assertFunction("month(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMonthOfYear());
        assertFunction("quarter(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMonthOfYear() / 4 + 1);
        assertFunction("year(" + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getYear());
        assertFunction("timezone_minute(" + TIMESTAMP_LITERAL + ")", BIGINT, 0L);
        assertFunction("timezone_hour(" + TIMESTAMP_LITERAL + ")", BIGINT, -11L);

        assertFunction("timezone_hour(localtimestamp)", BIGINT, 14L);
        assertFunction("timezone_hour(current_timestamp)", BIGINT, 14L);

        assertFunction("millisecond(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMillisOfSecond());
        assertFunction("second(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getSecondOfMinute());
        assertFunction("minute(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMinuteOfHour());
        assertFunction("hour(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getHourOfDay());
        assertFunction("day_of_week(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.dayOfWeek().get());
        assertFunction("dow(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.dayOfWeek().get());
        assertFunction("day(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfMonth());
        assertFunction("day_of_month(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfMonth());
        assertFunction("day_of_year(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.dayOfYear().get());
        assertFunction("doy(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.dayOfYear().get());
        assertFunction("week(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.weekOfWeekyear().get());
        assertFunction("week_of_year(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.weekOfWeekyear().get());
        assertFunction("month(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMonthOfYear());
        assertFunction("quarter(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMonthOfYear() / 4 + 1);
        assertFunction("year(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getYear());
        assertFunction("timezone_minute(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, 9L);
        assertFunction("timezone_hour(" + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, 7L);

        assertFunction("millisecond(" + TIME_LITERAL + ")", BIGINT, TIME.getLong(MILLI_OF_SECOND));
        assertFunction("second(" + TIME_LITERAL + ")", BIGINT, (long) TIME.getSecond());
        assertFunction("minute(" + TIME_LITERAL + ")", BIGINT, (long) TIME.getMinute());
        assertFunction("hour(" + TIME_LITERAL + ")", BIGINT, (long) TIME.getHour());

        assertFunction("millisecond(" + WEIRD_TIME_LITERAL + ")", BIGINT, WEIRD_TIME.getLong(MILLI_OF_SECOND));
        assertFunction("second(" + WEIRD_TIME_LITERAL + ")", BIGINT, (long) WEIRD_TIME.getSecond());
        assertFunction("minute(" + WEIRD_TIME_LITERAL + ")", BIGINT, (long) WEIRD_TIME.getMinute());
        assertFunction("hour(" + WEIRD_TIME_LITERAL + ")", BIGINT, (long) WEIRD_TIME.getHour());

        assertFunction("millisecond(" + INTERVAL_LITERAL + ")", BIGINT, (long) DAY_TO_SECOND_INTERVAL.getNano() / 1_000_000);
        assertFunction("second(" + INTERVAL_LITERAL + ")", BIGINT, DAY_TO_SECOND_INTERVAL.getSeconds() % 60);
        assertFunction("minute(" + INTERVAL_LITERAL + ")", BIGINT, DAY_TO_SECOND_INTERVAL.getSeconds() / 60 % 60);
        assertFunction("hour(" + INTERVAL_LITERAL + ")", BIGINT, DAY_TO_SECOND_INTERVAL.getSeconds() / 3600 % 24);
    }

    @Test
    public void testYearOfWeek()
    {
        assertFunction("year_of_week(DATE '2001-08-22')", BIGINT, 2001L);
        assertFunction("yow(DATE '2001-08-22')", BIGINT, 2001L);
        assertFunction("year_of_week(DATE '2005-01-02')", BIGINT, 2004L);
        assertFunction("year_of_week(DATE '2008-12-28')", BIGINT, 2008L);
        assertFunction("year_of_week(DATE '2008-12-29')", BIGINT, 2009L);
        assertFunction("year_of_week(DATE '2009-12-31')", BIGINT, 2009L);
        assertFunction("year_of_week(DATE '2010-01-03')", BIGINT, 2009L);
        assertFunction("year_of_week(TIMESTAMP '2001-08-22 03:04:05.321 +07:09')", BIGINT, 2001L);
        assertFunction("year_of_week(TIMESTAMP '2010-01-03 03:04:05.321')", BIGINT, 2009L);
    }

    @Test
    public void testLastDayOfMonth()
    {
        assertFunction("last_day_of_month(" + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.withDayOfMonth(31)));
        assertFunction("last_day_of_month(DATE '2019-08-01')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
        assertFunction("last_day_of_month(DATE '2019-08-31')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));

        assertFunction("last_day_of_month(" + TIMESTAMP_LITERAL + ")", DateType.DATE, toDate(DATE.withDayOfMonth(31)));
        assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 00:00:00.000')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
        assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 17:00:00.000')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
        assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 23:59:59.999')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
        assertFunction("last_day_of_month(TIMESTAMP '2019-08-31 23:59:59.999')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));

        assertFunction("last_day_of_month(" + WEIRD_TIMESTAMP_LITERAL + ")", DateType.DATE, toDate(DATE.withDayOfMonth(31)));
        ImmutableList.of("+05:45", "+00:00", "-05:45", "Asia/Tokyo", "Europe/London", "America/Los_Angeles", "America/Bahia_Banderas").forEach(timeZone -> {
            assertFunction("last_day_of_month(TIMESTAMP '2018-12-31 17:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2018, 12, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2018-12-31 20:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2018, 12, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2018-12-31 23:59:59.999 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2018, 12, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-01-01 00:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 1, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-01-01 00:00:00.001 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 1, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-01-01 03:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 1, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-01-01 06:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 1, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 00:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 17:00:00.000 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-08-01 23:59:59.999 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
            assertFunction("last_day_of_month(TIMESTAMP '2019-08-31 23:59:59.999 " + timeZone + "')", DateType.DATE, toDate(LocalDate.of(2019, 8, 31)));
        });
    }

    @Test
    public void testExtractFromTimestamp()
    {
        assertFunction("extract(second FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getSecondOfMinute());
        assertFunction("extract(minute FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMinuteOfHour());
        assertFunction("extract(hour FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getHourOfDay());
        assertFunction("extract(day_of_week FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfWeek());
        assertFunction("extract(dow FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfWeek());
        assertFunction("extract(day FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfMonth());
        assertFunction("extract(day_of_month FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfMonth());
        assertFunction("extract(day_of_year FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfYear());
        assertFunction("extract(year_of_week FROM " + TIMESTAMP_LITERAL + ")", BIGINT, 2001L);
        assertFunction("extract(doy FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getDayOfYear());
        assertFunction("extract(week FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getWeekOfWeekyear());
        assertFunction("extract(month FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMonthOfYear());
        assertFunction("extract(quarter FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getMonthOfYear() / 4 + 1);
        assertFunction("extract(year FROM " + TIMESTAMP_LITERAL + ")", BIGINT, (long) TIMESTAMP.getYear());

        assertFunction("extract(second FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getSecondOfMinute());
        assertFunction("extract(minute FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMinuteOfHour());
        assertFunction("extract(hour FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getHourOfDay());
        assertFunction("extract(day_of_week FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfWeek());
        assertFunction("extract(dow FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfWeek());
        assertFunction("extract(day FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfMonth());
        assertFunction("extract(day_of_month FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfMonth());
        assertFunction("extract(day_of_year FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfYear());
        assertFunction("extract(doy FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getDayOfYear());
        assertFunction("extract(week FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getWeekOfWeekyear());
        assertFunction("extract(month FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMonthOfYear());
        assertFunction("extract(quarter FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getMonthOfYear() / 4 + 1);
        assertFunction("extract(year FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, (long) WEIRD_TIMESTAMP.getYear());
        assertFunction("extract(timezone_minute FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, 9L);
        assertFunction("extract(timezone_hour FROM " + WEIRD_TIMESTAMP_LITERAL + ")", BIGINT, 7L);
    }

    @Test
    public void testExtractFromTime()
    {
        assertFunction("extract(second FROM " + TIME_LITERAL + ")", BIGINT, 5L);
        assertFunction("extract(minute FROM " + TIME_LITERAL + ")", BIGINT, 4L);
        assertFunction("extract(hour FROM " + TIME_LITERAL + ")", BIGINT, 3L);

        assertFunction("extract(second FROM " + WEIRD_TIME_LITERAL + ")", BIGINT, 5L);
        assertFunction("extract(minute FROM " + WEIRD_TIME_LITERAL + ")", BIGINT, 4L);
        assertFunction("extract(hour FROM " + WEIRD_TIME_LITERAL + ")", BIGINT, 3L);
    }

    @Test
    public void testExtractFromDate()
    {
        assertFunction("extract(day_of_week FROM " + DATE_LITERAL + ")", BIGINT, 3L);
        assertFunction("extract(dow FROM " + DATE_LITERAL + ")", BIGINT, 3L);
        assertFunction("extract(day FROM " + DATE_LITERAL + ")", BIGINT, 22L);
        assertFunction("extract(day_of_month FROM " + DATE_LITERAL + ")", BIGINT, 22L);
        assertFunction("extract(day_of_year FROM " + DATE_LITERAL + ")", BIGINT, 234L);
        assertFunction("extract(doy FROM " + DATE_LITERAL + ")", BIGINT, 234L);
        assertFunction("extract(year_of_week FROM " + DATE_LITERAL + ")", BIGINT, 2001L);
        assertFunction("extract(yow FROM " + DATE_LITERAL + ")", BIGINT, 2001L);
        assertFunction("extract(week FROM " + DATE_LITERAL + ")", BIGINT, 34L);
        assertFunction("extract(month FROM " + DATE_LITERAL + ")", BIGINT, 8L);
        assertFunction("extract(quarter FROM " + DATE_LITERAL + ")", BIGINT, 3L);
        assertFunction("extract(year FROM " + DATE_LITERAL + ")", BIGINT, 2001L);

        assertFunction("extract(quarter FROM DATE '2001-01-01')", BIGINT, 1L);
        assertFunction("extract(quarter FROM DATE '2001-03-31')", BIGINT, 1L);
        assertFunction("extract(quarter FROM DATE '2001-04-01')", BIGINT, 2L);
        assertFunction("extract(quarter FROM DATE '2001-06-30')", BIGINT, 2L);
        assertFunction("extract(quarter FROM DATE '2001-07-01')", BIGINT, 3L);
        assertFunction("extract(quarter FROM DATE '2001-09-30')", BIGINT, 3L);
        assertFunction("extract(quarter FROM DATE '2001-10-01')", BIGINT, 4L);
        assertFunction("extract(quarter FROM DATE '2001-12-31')", BIGINT, 4L);

        assertFunction("extract(quarter FROM TIMESTAMP '2001-01-01 00:00:00.000')", BIGINT, 1L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-03-31 23:59:59.999')", BIGINT, 1L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-04-01 00:00:00.000')", BIGINT, 2L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-06-30 23:59:59.999')", BIGINT, 2L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-07-01 00:00:00.000')", BIGINT, 3L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-09-30 23:59:59.999')", BIGINT, 3L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-10-01 00:00:00.000')", BIGINT, 4L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-12-31 23:59:59.999')", BIGINT, 4L);

        assertFunction("extract(quarter FROM TIMESTAMP '2001-01-01 00:00:00.000 +06:00')", BIGINT, 1L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-03-31 23:59:59.999 +06:00')", BIGINT, 1L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-04-01 00:00:00.000 +06:00')", BIGINT, 2L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-06-30 23:59:59.999 +06:00')", BIGINT, 2L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-07-01 00:00:00.000 +06:00')", BIGINT, 3L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-09-30 23:59:59.999 +06:00')", BIGINT, 3L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-10-01 00:00:00.000 +06:00')", BIGINT, 4L);
        assertFunction("extract(quarter FROM TIMESTAMP '2001-12-31 23:59:59.999 +06:00')", BIGINT, 4L);
    }

    @Test
    public void testExtractFromInterval()
    {
        assertFunction("extract(second FROM INTERVAL '5' SECOND)", BIGINT, 5L);
        assertFunction("extract(second FROM INTERVAL '65' SECOND)", BIGINT, 5L);

        assertFunction("extract(minute FROM INTERVAL '4' MINUTE)", BIGINT, 4L);
        assertFunction("extract(minute FROM INTERVAL '64' MINUTE)", BIGINT, 4L);
        assertFunction("extract(minute FROM INTERVAL '247' SECOND)", BIGINT, 4L);

        assertFunction("extract(hour FROM INTERVAL '3' HOUR)", BIGINT, 3L);
        assertFunction("extract(hour FROM INTERVAL '27' HOUR)", BIGINT, 3L);
        assertFunction("extract(hour FROM INTERVAL '187' MINUTE)", BIGINT, 3L);

        assertFunction("extract(day FROM INTERVAL '2' DAY)", BIGINT, 2L);
        assertFunction("extract(day FROM INTERVAL '55' HOUR)", BIGINT, 2L);

        assertFunction("extract(month FROM INTERVAL '3' MONTH)", BIGINT, 3L);
        assertFunction("extract(month FROM INTERVAL '15' MONTH)", BIGINT, 3L);

        assertFunction("extract(year FROM INTERVAL '2' YEAR)", BIGINT, 2L);
        assertFunction("extract(year FROM INTERVAL '29' MONTH)", BIGINT, 2L);
    }

    @Test
    public void testTruncateTimestamp()
    {
        DateTime result = TIMESTAMP;
        result = result.withMillisOfSecond(0);
        assertFunction("date_trunc('second', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withSecondOfMinute(0);
        assertFunction("date_trunc('minute', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withMinuteOfHour(0);
        assertFunction("date_trunc('hour', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withHourOfDay(0);
        assertFunction("date_trunc('day', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withDayOfMonth(20);
        assertFunction("date_trunc('week', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withDayOfMonth(1);
        assertFunction("date_trunc('month', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withMonthOfYear(7);
        assertFunction("date_trunc('quarter', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = result.withMonthOfYear(1);
        assertFunction("date_trunc('year', " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(result));

        result = WEIRD_TIMESTAMP;
        result = result.withMillisOfSecond(0);
        assertFunction("date_trunc('second', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withSecondOfMinute(0);
        assertFunction("date_trunc('minute', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withMinuteOfHour(0);
        assertFunction("date_trunc('hour', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withHourOfDay(0);
        assertFunction("date_trunc('day', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withDayOfMonth(20);
        assertFunction("date_trunc('week', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withDayOfMonth(1);
        assertFunction("date_trunc('month', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withMonthOfYear(7);
        assertFunction("date_trunc('quarter', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));

        result = result.withMonthOfYear(1);
        assertFunction("date_trunc('year', " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(result));
    }

    @Test
    public void testTruncateTime()
    {
        LocalTime result = TIME;
        result = result.withNano(0);
        assertFunction("date_trunc('second', " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(result));

        result = result.withSecond(0);
        assertFunction("date_trunc('minute', " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(result));

        result = result.withMinute(0);
        assertFunction("date_trunc('hour', " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(result));
    }

    @Test
    public void testTruncateDate()
    {
        DateTime result = DATE;
        assertFunction("date_trunc('day', " + DATE_LITERAL + ")", DateType.DATE, toDate(result));

        result = result.withDayOfMonth(20);
        assertFunction("date_trunc('week', " + DATE_LITERAL + ")", DateType.DATE, toDate(result));

        result = result.withDayOfMonth(1);
        assertFunction("date_trunc('month', " + DATE_LITERAL + ")", DateType.DATE, toDate(result));

        result = result.withMonthOfYear(7);
        assertFunction("date_trunc('quarter', " + DATE_LITERAL + ")", DateType.DATE, toDate(result));

        result = result.withMonthOfYear(1);
        assertFunction("date_trunc('year', " + DATE_LITERAL + ")", DateType.DATE, toDate(result));
    }

    @Test
    public void testAddFieldToTimestamp()
    {
        assertFunction("date_add('millisecond', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusMillis(3)));
        assertFunction("date_add('second', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusSeconds(3)));
        assertFunction("date_add('minute', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusMinutes(3)));
        assertFunction("date_add('hour', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusHours(3)));
        assertFunction("date_add('hour', 23, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusHours(23)));
        assertFunction("date_add('hour', -4, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.minusHours(4)));
        assertFunction("date_add('hour', -23, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.minusHours(23)));
        assertFunction("date_add('day', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusDays(3)));
        assertFunction("date_add('week', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusWeeks(3)));
        assertFunction("date_add('month', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusMonths(3)));
        assertFunction("date_add('quarter', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusMonths(3 * 3)));
        assertFunction("date_add('year', 3, " + TIMESTAMP_LITERAL + ")", TIMESTAMP_MILLIS, sqlTimestampOf(TIMESTAMP.plusYears(3)));

        assertFunction("date_add('millisecond', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusMillis(3)));
        assertFunction("date_add('second', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusSeconds(3)));
        assertFunction("date_add('minute', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusMinutes(3)));
        assertFunction("date_add('hour', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusHours(3)));
        assertFunction("date_add('day', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusDays(3)));
        assertFunction("date_add('week', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusWeeks(3)));
        assertFunction("date_add('month', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusMonths(3)));
        assertFunction("date_add('quarter', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusMonths(3 * 3)));
        assertFunction("date_add('year', 3, " + WEIRD_TIMESTAMP_LITERAL + ")", TIMESTAMP_WITH_TIME_ZONE, toTimestampWithTimeZone(WEIRD_TIMESTAMP.plusYears(3)));
    }

    @Test
    public void testAddFieldToDate()
    {
        assertFunction("date_add('day', 0, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE));
        assertFunction("date_add('day', 3, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.plusDays(3)));
        assertFunction("date_add('week', 3, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.plusWeeks(3)));
        assertFunction("date_add('month', 3, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.plusMonths(3)));
        assertFunction("date_add('quarter', 3, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.plusMonths(3 * 3)));
        assertFunction("date_add('year', 3, " + DATE_LITERAL + ")", DateType.DATE, toDate(DATE.plusYears(3)));
    }

    @Test
    public void testAddFieldToTime()
    {
        assertFunction("date_add('millisecond', 0, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME));
        assertFunction("date_add('millisecond', 3, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.plusNanos(3_000_000)));
        assertFunction("date_add('second', 3, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.plusSeconds(3)));
        assertFunction("date_add('minute', 3, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.plusMinutes(3)));
        assertFunction("date_add('hour', 3, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.plusHours(3)));
        assertFunction("date_add('hour', 23, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.plusHours(23)));
        assertFunction("date_add('hour', -4, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.minusHours(4)));
        assertFunction("date_add('hour', -23, " + TIME_LITERAL + ")", TimeType.TIME, sqlTimeOf(TIME.minusHours(23)));
    }

    @Test
    public void testDateDiffTimestamp()
    {
        DateTime baseDateTime = new DateTime(1960, 5, 3, 7, 2, 9, 678, UTC_TIME_ZONE);
        String baseDateTimeLiteral = "TIMESTAMP '1960-05-03 07:02:09.678'";

        assertFunction("date_diff('millisecond', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, millisBetween(baseDateTime, TIMESTAMP));
        assertFunction("date_diff('second', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) secondsBetween(baseDateTime, TIMESTAMP).getSeconds());
        assertFunction("date_diff('minute', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) minutesBetween(baseDateTime, TIMESTAMP).getMinutes());
        assertFunction("date_diff('hour', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) hoursBetween(baseDateTime, TIMESTAMP).getHours());
        assertFunction("date_diff('day', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) daysBetween(baseDateTime, TIMESTAMP).getDays());
        assertFunction("date_diff('week', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) weeksBetween(baseDateTime, TIMESTAMP).getWeeks());
        assertFunction("date_diff('month', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) monthsBetween(baseDateTime, TIMESTAMP).getMonths());
        assertFunction("date_diff('quarter', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) monthsBetween(baseDateTime, TIMESTAMP).getMonths() / 3);
        assertFunction("date_diff('year', " + baseDateTimeLiteral + ", " + TIMESTAMP_LITERAL + ")", BIGINT, (long) yearsBetween(baseDateTime, TIMESTAMP).getYears());

        DateTime weirdBaseDateTime = new DateTime(1960, 5, 3, 7, 2, 9, 678, WEIRD_DATE_TIME_ZONE);
        String weirdBaseDateTimeLiteral = "TIMESTAMP '1960-05-03 07:02:09.678 +07:09'";

        assertFunction("date_diff('millisecond', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                millisBetween(weirdBaseDateTime, WEIRD_TIMESTAMP));
        assertFunction("date_diff('second', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) secondsBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getSeconds());
        assertFunction("date_diff('minute', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) minutesBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getMinutes());
        assertFunction("date_diff('hour', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) hoursBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getHours());
        assertFunction("date_diff('day', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) daysBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getDays());
        assertFunction("date_diff('week', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) weeksBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getWeeks());
        assertFunction("date_diff('month', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) monthsBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getMonths());
        assertFunction("date_diff('quarter', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) monthsBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getMonths() / 3);
        assertFunction("date_diff('year', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIMESTAMP_LITERAL + ")",
                BIGINT,
                (long) yearsBetween(weirdBaseDateTime, WEIRD_TIMESTAMP).getYears());
    }

    @Test
    public void testDateDiffDate()
    {
        DateTime baseDateTime = new DateTime(1960, 5, 3, 0, 0, 0, 0, DateTimeZone.UTC);
        String baseDateTimeLiteral = "DATE '1960-05-03'";

        assertFunction("date_diff('day', " + baseDateTimeLiteral + ", " + DATE_LITERAL + ")", BIGINT, (long) daysBetween(baseDateTime, DATE).getDays());
        assertFunction("date_diff('week', " + baseDateTimeLiteral + ", " + DATE_LITERAL + ")", BIGINT, (long) weeksBetween(baseDateTime, DATE).getWeeks());
        assertFunction("date_diff('month', " + baseDateTimeLiteral + ", " + DATE_LITERAL + ")", BIGINT, (long) monthsBetween(baseDateTime, DATE).getMonths());
        assertFunction("date_diff('quarter', " + baseDateTimeLiteral + ", " + DATE_LITERAL + ")", BIGINT, (long) monthsBetween(baseDateTime, DATE).getMonths() / 3);
        assertFunction("date_diff('year', " + baseDateTimeLiteral + ", " + DATE_LITERAL + ")", BIGINT, (long) yearsBetween(baseDateTime, DATE).getYears());
    }

    @Test
    public void testDateDiffTime()
    {
        LocalTime baseDateTime = LocalTime.of(7, 2, 9, 678_000_000);
        String baseDateTimeLiteral = "TIME '07:02:09.678'";

        assertFunction("date_diff('millisecond', " + baseDateTimeLiteral + ", " + TIME_LITERAL + ")", BIGINT, millisBetween(baseDateTime, TIME));
        assertFunction("date_diff('second', " + baseDateTimeLiteral + ", " + TIME_LITERAL + ")", BIGINT, secondsBetween(baseDateTime, TIME));
        assertFunction("date_diff('minute', " + baseDateTimeLiteral + ", " + TIME_LITERAL + ")", BIGINT, minutesBetween(baseDateTime, TIME));
        assertFunction("date_diff('hour', " + baseDateTimeLiteral + ", " + TIME_LITERAL + ")", BIGINT, hoursBetween(baseDateTime, TIME));
    }

    @Test
    public void testDateDiffTimeWithTimeZone()
    {
        OffsetTime weirdBaseDateTime = OffsetTime.of(7, 2, 9, 678_000_000, WEIRD_ZONE);
        String weirdBaseDateTimeLiteral = "TIME '07:02:09.678 +07:09'";

        assertFunction("date_diff('millisecond', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIME_LITERAL + ")", BIGINT, millisBetween(weirdBaseDateTime, WEIRD_TIME));
        assertFunction("date_diff('second', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIME_LITERAL + ")", BIGINT, secondsBetween(weirdBaseDateTime, WEIRD_TIME));
        assertFunction("date_diff('minute', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIME_LITERAL + ")", BIGINT, minutesBetween(weirdBaseDateTime, WEIRD_TIME));
        assertFunction("date_diff('hour', " + weirdBaseDateTimeLiteral + ", " + WEIRD_TIME_LITERAL + ")", BIGINT, hoursBetween(weirdBaseDateTime, WEIRD_TIME));
    }

    @Test
    public void testParseDatetime()
    {
        // Modern date
        assertFunction("parse_datetime('2020-08-18 03:04:05.678', 'yyyy-MM-dd HH:mm:ss.SSS')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2020, 8, 18, 3, 4, 5, 678, DATE_TIME_ZONE)));

        // Before epoch
        assertFunction("parse_datetime('1960/01/22 03:04', 'yyyy/MM/dd HH:mm')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(1960, 1, 22, 3, 4, 0, 0, DATE_TIME_ZONE)));

        // With named zone
        assertFunction("parse_datetime('1960/01/22 03:04 Asia/Oral', 'yyyy/MM/dd HH:mm ZZZZZ')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(1960, 1, 22, 3, 4, 0, 0, DateTimeZone.forID("Asia/Oral"))));

        // With zone offset
        assertFunction("parse_datetime('1960/01/22 03:04 +0500', 'yyyy/MM/dd HH:mm Z')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(1960, 1, 22, 3, 4, 0, 0, DateTimeZone.forOffsetHours(5))));
    }

    @Test
    public void testFormatDatetime()
    {
        assertFunction("format_datetime(" + TIMESTAMP_LITERAL + ", 'YYYY/MM/dd HH:mm')", VARCHAR, "2001/08/22 03:04");
        assertFunction("format_datetime(" + WEIRD_TIMESTAMP_LITERAL + ", 'YYYY/MM/dd HH:mm')", VARCHAR, "2001/08/22 03:04");
        assertFunction("format_datetime(" + WEIRD_TIMESTAMP_LITERAL + ", 'YYYY/MM/dd HH:mm ZZZZ')", VARCHAR, "2001/08/22 03:04 +07:09");
    }

    @Test
    public void testDateFormat()
    {
        String dateTimeLiteral = "TIMESTAMP '2001-01-09 13:04:05.321'";

        assertFunction("date_format(" + dateTimeLiteral + ", '%a')", VARCHAR, "Tue");
        assertFunction("date_format(" + dateTimeLiteral + ", '%b')", VARCHAR, "Jan");
        assertFunction("date_format(" + dateTimeLiteral + ", '%c')", VARCHAR, "1");
        assertFunction("date_format(" + dateTimeLiteral + ", '%d')", VARCHAR, "09");
        assertFunction("date_format(" + dateTimeLiteral + ", '%e')", VARCHAR, "9");
        assertFunction("date_format(" + dateTimeLiteral + ", '%f')", VARCHAR, "321000");
        assertFunction("date_format(" + dateTimeLiteral + ", '%H')", VARCHAR, "13");
        assertFunction("date_format(" + dateTimeLiteral + ", '%h')", VARCHAR, "01");
        assertFunction("date_format(" + dateTimeLiteral + ", '%I')", VARCHAR, "01");
        assertFunction("date_format(" + dateTimeLiteral + ", '%i')", VARCHAR, "04");
        assertFunction("date_format(" + dateTimeLiteral + ", '%j')", VARCHAR, "009");
        assertFunction("date_format(" + dateTimeLiteral + ", '%k')", VARCHAR, "13");
        assertFunction("date_format(" + dateTimeLiteral + ", '%l')", VARCHAR, "1");
        assertFunction("date_format(" + dateTimeLiteral + ", '%M')", VARCHAR, "January");
        assertFunction("date_format(" + dateTimeLiteral + ", '%m')", VARCHAR, "01");
        assertFunction("date_format(" + dateTimeLiteral + ", '%p')", VARCHAR, "PM");
        assertFunction("date_format(" + dateTimeLiteral + ", '%r')", VARCHAR, "01:04:05 PM");
        assertFunction("date_format(" + dateTimeLiteral + ", '%S')", VARCHAR, "05");
        assertFunction("date_format(" + dateTimeLiteral + ", '%s')", VARCHAR, "05");
        assertFunction("date_format(" + dateTimeLiteral + ", '%T')", VARCHAR, "13:04:05");
        assertFunction("date_format(" + dateTimeLiteral + ", '%v')", VARCHAR, "02");
        assertFunction("date_format(" + dateTimeLiteral + ", '%W')", VARCHAR, "Tuesday");
        assertFunction("date_format(" + dateTimeLiteral + ", '%Y')", VARCHAR, "2001");
        assertFunction("date_format(" + dateTimeLiteral + ", '%y')", VARCHAR, "01");
        assertFunction("date_format(" + dateTimeLiteral + ", '%%')", VARCHAR, "%");
        assertFunction("date_format(" + dateTimeLiteral + ", 'foo')", VARCHAR, "foo");
        assertFunction("date_format(" + dateTimeLiteral + ", '%g')", VARCHAR, "g");
        assertFunction("date_format(" + dateTimeLiteral + ", '%4')", VARCHAR, "4");
        assertFunction("date_format(" + dateTimeLiteral + ", '%x %v')", VARCHAR, "2001 02");
        assertFunction("date_format(" + dateTimeLiteral + ", '%Y年%m月%d日')", VARCHAR, "2001年01月09日");

        String weirdDateTimeLiteral = "TIMESTAMP '2001-01-09 13:04:05.321 +07:09'";

        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%a')", VARCHAR, "Tue");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%b')", VARCHAR, "Jan");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%c')", VARCHAR, "1");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%d')", VARCHAR, "09");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%e')", VARCHAR, "9");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%f')", VARCHAR, "321000");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%H')", VARCHAR, "13");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%h')", VARCHAR, "01");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%I')", VARCHAR, "01");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%i')", VARCHAR, "04");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%j')", VARCHAR, "009");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%k')", VARCHAR, "13");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%l')", VARCHAR, "1");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%M')", VARCHAR, "January");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%m')", VARCHAR, "01");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%p')", VARCHAR, "PM");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%r')", VARCHAR, "01:04:05 PM");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%S')", VARCHAR, "05");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%s')", VARCHAR, "05");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%T')", VARCHAR, "13:04:05");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%v')", VARCHAR, "02");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%W')", VARCHAR, "Tuesday");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%Y')", VARCHAR, "2001");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%y')", VARCHAR, "01");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%%')", VARCHAR, "%");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", 'foo')", VARCHAR, "foo");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%g')", VARCHAR, "g");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%4')", VARCHAR, "4");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%x %v')", VARCHAR, "2001 02");
        assertFunction("date_format(" + weirdDateTimeLiteral + ", '%Y年%m月%d日')", VARCHAR, "2001年01月09日");

        assertFunction("date_format(TIMESTAMP '2001-01-09 13:04:05.32', '%f')", VARCHAR, "320000");
        assertFunction("date_format(TIMESTAMP '2001-01-09 00:04:05.32', '%k')", VARCHAR, "0");

        assertInvalidFunction("date_format(DATE '2001-01-09', '%D')", "%D not supported in date format string");
        assertInvalidFunction("date_format(DATE '2001-01-09', '%U')", "%U not supported in date format string");
        assertInvalidFunction("date_format(DATE '2001-01-09', '%u')", "%u not supported in date format string");
        assertInvalidFunction("date_format(DATE '2001-01-09', '%V')", "%V not supported in date format string");
        assertInvalidFunction("date_format(DATE '2001-01-09', '%w')", "%w not supported in date format string");
        assertInvalidFunction("date_format(DATE '2001-01-09', '%X')", "%X not supported in date format string");
    }

    @Test
    public void testDateParse()
    {
        assertFunction("date_parse('2013', '%Y')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 1, 1, 0, 0, 0, 0));
        assertFunction("date_parse('2013-05', '%Y-%m')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 1, 0, 0, 0, 0));
        assertFunction("date_parse('2013-05-17', '%Y-%m-%d')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 0, 0, 0, 0));
        assertFunction("date_parse('2013-05-17 12:35:10', '%Y-%m-%d %h:%i:%s')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 0, 35, 10, 0));
        assertFunction("date_parse('2013-05-17 12:35:10 PM', '%Y-%m-%d %h:%i:%s %p')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 12, 35, 10, 0));
        assertFunction("date_parse('2013-05-17 12:35:10 AM', '%Y-%m-%d %h:%i:%s %p')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 0, 35, 10, 0));

        assertFunction("date_parse('2013-05-17 00:35:10', '%Y-%m-%d %H:%i:%s')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 0, 35, 10, 0));
        assertFunction("date_parse('2013-05-17 23:35:10', '%Y-%m-%d %H:%i:%s')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 23, 35, 10, 0));
        assertFunction("date_parse('abc 2013-05-17 fff 23:35:10 xyz', 'abc %Y-%m-%d fff %H:%i:%s xyz')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2013, 5, 17, 23, 35, 10, 0));

        assertFunction("date_parse('2013 14', '%Y %y')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2014, 1, 1, 0, 0, 0, 0));

        assertFunction("date_parse('1998 53', '%x %v')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1998, 12, 28, 0, 0, 0, 0));

        assertFunction("date_parse('1.1', '%s.%f')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 1, 1, 0, 0, 1, 100));
        assertFunction("date_parse('1.01', '%s.%f')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 1, 1, 0, 0, 1, 10));
        assertFunction("date_parse('1.2006', '%s.%f')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 1, 1, 0, 0, 1, 200));
        assertFunction("date_parse('59.123456789', '%s.%f')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 1, 1, 0, 0, 59, 123));

        assertFunction("date_parse('0', '%k')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 1, 1, 0, 0, 0, 0));

        assertFunction("date_parse('28-JAN-16 11.45.46.421000 PM','%d-%b-%y %l.%i.%s.%f %p')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2016, 1, 28, 23, 45, 46, 421));
        assertFunction("date_parse('11-DEC-70 11.12.13.456000 AM','%d-%b-%y %l.%i.%s.%f %p')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 1970, 12, 11, 11, 12, 13, 456));
        assertFunction("date_parse('31-MAY-69 04.59.59.999000 AM','%d-%b-%y %l.%i.%s.%f %p')",
                TIMESTAMP_MILLIS,
                sqlTimestampOf(3, 2069, 5, 31, 4, 59, 59, 999));

        assertInvalidFunction("date_parse('', '%D')", "%D not supported in date format string");
        assertInvalidFunction("date_parse('', '%U')", "%U not supported in date format string");
        assertInvalidFunction("date_parse('', '%u')", "%u not supported in date format string");
        assertInvalidFunction("date_parse('', '%V')", "%V not supported in date format string");
        assertInvalidFunction("date_parse('', '%w')", "%w not supported in date format string");
        assertInvalidFunction("date_parse('', '%X')", "%X not supported in date format string");

        assertInvalidFunction("date_parse('3.0123456789', '%s.%f')", "Invalid format: \"3.0123456789\" is malformed at \"9\"");
        assertInvalidFunction("date_parse('1970-01-01', '')", "Both printing and parsing not supported");
    }

    @Test
    public void testLocale()
    {
        Locale locale = Locale.KOREAN;
        Session localeSession = Session.builder(this.session)
                .setTimeZoneKey(TIME_ZONE_KEY)
                .setLocale(locale)
                .build();

        try (FunctionAssertions localeAssertions = new FunctionAssertions(localeSession)) {
            String dateTimeLiteral = "TIMESTAMP '2001-01-09 13:04:05.321'";

            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%a')", VARCHAR, "화");
            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%W')", VARCHAR, "화요일");
            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%p')", VARCHAR, "오후");
            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%r')", VARCHAR, "01:04:05 오후");
            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%b')", VARCHAR, "1월");
            localeAssertions.assertFunction("date_format(" + dateTimeLiteral + ", '%M')", VARCHAR, "1월");

            localeAssertions.assertFunction("format_datetime(" + dateTimeLiteral + ", 'EEE')", VARCHAR, "화");
            localeAssertions.assertFunction("format_datetime(" + dateTimeLiteral + ", 'EEEE')", VARCHAR, "화요일");
            localeAssertions.assertFunction("format_datetime(" + dateTimeLiteral + ", 'a')", VARCHAR, "오후");
            localeAssertions.assertFunction("format_datetime(" + dateTimeLiteral + ", 'MMM')", VARCHAR, "1월");
            localeAssertions.assertFunction("format_datetime(" + dateTimeLiteral + ", 'MMMM')", VARCHAR, "1월");

            localeAssertions.assertFunction("date_parse('2013-05-17 12:35:10 오후', '%Y-%m-%d %h:%i:%s %p')",
                    TIMESTAMP_MILLIS,
                    sqlTimestampOf(3, 2013, 5, 17, 12, 35, 10, 0));
            localeAssertions.assertFunction("date_parse('2013-05-17 12:35:10 오전', '%Y-%m-%d %h:%i:%s %p')",
                    TIMESTAMP_MILLIS,
                    sqlTimestampOf(3, 2013, 5, 17, 0, 35, 10, 0));

            localeAssertions.assertFunction("parse_datetime('2013-05-17 12:35:10 오후', 'yyyy-MM-dd hh:mm:ss a')",
                    TIMESTAMP_WITH_TIME_ZONE,
                    toTimestampWithTimeZone(new DateTime(2013, 5, 17, 12, 35, 10, 0, DATE_TIME_ZONE)));
            localeAssertions.assertFunction("parse_datetime('2013-05-17 12:35:10 오전', 'yyyy-MM-dd hh:mm:ss aaa')",
                    TIMESTAMP_WITH_TIME_ZONE,
                    toTimestampWithTimeZone(new DateTime(2013, 5, 17, 0, 35, 10, 0, DATE_TIME_ZONE)));
        }
    }

    @Test
    public void testDateTimeOutputString()
    {
        // SqlDate
        assertFunctionString("date '2012-12-31'", DateType.DATE, "2012-12-31");
        assertFunctionString("date '0000-12-31'", DateType.DATE, "0000-12-31");
        assertFunctionString("date '0000-09-23'", DateType.DATE, "0000-09-23");
        assertFunctionString("date '0001-10-25'", DateType.DATE, "0001-10-25");
        assertFunctionString("date '1560-04-29'", DateType.DATE, "1560-04-29");

        // SqlTime
        assertFunctionString("time '00:00:00'", createTimeType(0), "00:00:00");
        assertFunctionString("time '01:02:03'", createTimeType(0), "01:02:03");
        assertFunctionString("time '23:23:23.233'", createTimeType(3), "23:23:23.233");
        assertFunctionString("time '23:59:59.999'", createTimeType(3), "23:59:59.999");

        // SqlTimestamp
        assertFunctionString("timestamp '0000-01-02 01:02:03'", createTimestampType(0), "0000-01-02 01:02:03");
        assertFunctionString("timestamp '2012-12-31 00:00:00'", createTimestampType(0), "2012-12-31 00:00:00");
        assertFunctionString("timestamp '1234-05-06 23:23:23.233'", createTimestampType(3), "1234-05-06 23:23:23.233");
        assertFunctionString("timestamp '2333-02-23 23:59:59.999'", createTimestampType(3), "2333-02-23 23:59:59.999");

        // SqlTimestampWithTimeZone
        assertFunctionString("timestamp '2012-12-31 00:00:00 UTC'", createTimestampWithTimeZoneType(0), "2012-12-31 00:00:00 UTC");
        assertFunctionString("timestamp '0000-01-02 01:02:03 Asia/Shanghai'", createTimestampWithTimeZoneType(0), "0000-01-02 01:02:03 Asia/Shanghai");
        assertFunctionString("timestamp '1234-05-06 23:23:23.233 America/Los_Angeles'", createTimestampWithTimeZoneType(3), "1234-05-06 23:23:23.233 America/Los_Angeles");
        assertFunctionString("timestamp '2333-02-23 23:59:59.999 Asia/Tokyo'", createTimestampWithTimeZoneType(3), "2333-02-23 23:59:59.999 Asia/Tokyo");
    }

    @Test
    public void testParseDuration()
    {
        assertFunction("parse_duration('1234 ns')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 0));
        assertFunction("parse_duration('1234 us')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 1));
        assertFunction("parse_duration('1234 ms')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1, 234));
        assertFunction("parse_duration('1234 s')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 20, 34, 0));
        assertFunction("parse_duration('1234 m')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 20, 34, 0, 0));
        assertFunction("parse_duration('1234 h')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(51, 10, 0, 0, 0));
        assertFunction("parse_duration('1234 d')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(1234, 0, 0, 0, 0));
        assertFunction("parse_duration('1234.567 ns')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 0));
        assertFunction("parse_duration('1234.567 ms')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1, 235));
        assertFunction("parse_duration('1234.567 s')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1234, 567));
        assertFunction("parse_duration('1234.567 m')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 20, 34, 34, 20));
        assertFunction("parse_duration('1234.567 h')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(51, 10, 34, 1, 200));
        assertFunction("parse_duration('1234.567 d')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(1234, 13, 36, 28, 800));

        // without space
        assertFunction("parse_duration('1234ns')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 0));
        assertFunction("parse_duration('1234us')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 1));
        assertFunction("parse_duration('1234ms')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1, 234));
        assertFunction("parse_duration('1234s')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 20, 34, 0));
        assertFunction("parse_duration('1234m')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 20, 34, 0, 0));
        assertFunction("parse_duration('1234h')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(51, 10, 0, 0, 0));
        assertFunction("parse_duration('1234d')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(1234, 0, 0, 0, 0));
        assertFunction("parse_duration('1234.567ns')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 0, 0));
        assertFunction("parse_duration('1234.567ms')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1, 235));
        assertFunction("parse_duration('1234.567s')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 0, 0, 1234, 567));
        assertFunction("parse_duration('1234.567m')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(0, 20, 34, 34, 20));
        assertFunction("parse_duration('1234.567h')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(51, 10, 34, 1, 200));
        assertFunction("parse_duration('1234.567d')", INTERVAL_DAY_TIME, new SqlIntervalDayTime(1234, 13, 36, 28, 800));

        // invalid function calls
        assertInvalidFunction("parse_duration('')", "duration is empty");
        assertInvalidFunction("parse_duration('1f')", "Unknown time unit: f");
        assertInvalidFunction("parse_duration('abc')", "duration is not a valid data duration string: abc");
    }

    @Test
    public void testIntervalDayToSecondToMilliseconds()
    {
        assertFunction("to_milliseconds(parse_duration('1ns'))", BigintType.BIGINT, 0L);
        assertFunction("to_milliseconds(parse_duration('1ms'))", BigintType.BIGINT, 1L);
        assertFunction("to_milliseconds(parse_duration('1s'))", BigintType.BIGINT, SECONDS.toMillis(1));
        assertFunction("to_milliseconds(parse_duration('1h'))", BigintType.BIGINT, HOURS.toMillis(1));
        assertFunction("to_milliseconds(parse_duration('1d'))", BigintType.BIGINT, DAYS.toMillis(1));
    }

    @Test
    public void testWithTimezone()
    {
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', 'UTC')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("UTC")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', '+13')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("+13")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', '-14')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("-14")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', '+00:45')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("+00:45")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', 'Asia/Shanghai')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("Asia/Shanghai")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', 'America/New_York')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 8, 22, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("America/New_York")))));

        assertFunction(
                "with_timezone(TIMESTAMP '2001-06-01 03:04:05.321', 'America/Los_Angeles')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 6, 1, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("America/Los_Angeles")))));
        assertFunction(
                "with_timezone(TIMESTAMP '2001-12-01 03:04:05.321', 'America/Los_Angeles')",
                TIMESTAMP_WITH_TIME_ZONE,
                toTimestampWithTimeZone(new DateTime(2001, 12, 1, 3, 4, 5, 321, getDateTimeZone(getTimeZoneKey("America/Los_Angeles")))));

        assertInvalidFunction("with_timezone(TIMESTAMP '2001-08-22 03:04:05.321', 'invalidzoneid')", "'invalidzoneid' is not a valid time zone");
    }

    private void assertFunctionString(String projection, Type expectedType, String expected)
    {
        functionAssertions.assertFunctionString(projection, expectedType, expected);
    }

    private static SqlDate toDate(LocalDate localDate)
    {
        return new SqlDate(toIntExact(localDate.toEpochDay()));
    }

    private static SqlDate toDate(DateTime dateDate)
    {
        long millis = dateDate.getMillis();
        return new SqlDate(toIntExact(MILLISECONDS.toDays(millis)));
    }

    private static long millisBetween(ReadableInstant start, ReadableInstant end)
    {
        requireNonNull(start, "start is null");
        requireNonNull(end, "end is null");
        return millis().getField(getInstantChronology(start)).getDifferenceAsLong(end.getMillis(), start.getMillis());
    }

    private static Seconds secondsBetween(ReadableInstant start, ReadableInstant end)
    {
        return Seconds.secondsBetween(start, end);
    }

    private static Minutes minutesBetween(ReadableInstant start, ReadableInstant end)
    {
        return Minutes.minutesBetween(start, end);
    }

    private static Hours hoursBetween(ReadableInstant start, ReadableInstant end)
    {
        return Hours.hoursBetween(start, end);
    }

    private static long millisBetween(LocalTime start, LocalTime end)
    {
        return NANOSECONDS.toMillis(end.toNanoOfDay() - start.toNanoOfDay());
    }

    private static long secondsBetween(LocalTime start, LocalTime end)
    {
        return NANOSECONDS.toSeconds(end.toNanoOfDay() - start.toNanoOfDay());
    }

    private static long minutesBetween(LocalTime start, LocalTime end)
    {
        return NANOSECONDS.toMinutes(end.toNanoOfDay() - start.toNanoOfDay());
    }

    private static long hoursBetween(LocalTime start, LocalTime end)
    {
        return NANOSECONDS.toHours(end.toNanoOfDay() - start.toNanoOfDay());
    }

    private static long millisBetween(OffsetTime start, OffsetTime end)
    {
        return millisUtc(end) - millisUtc(start);
    }

    private static long secondsBetween(OffsetTime start, OffsetTime end)
    {
        return MILLISECONDS.toSeconds(millisBetween(start, end));
    }

    private static long minutesBetween(OffsetTime start, OffsetTime end)
    {
        return MILLISECONDS.toMinutes(millisBetween(start, end));
    }

    private static long hoursBetween(OffsetTime start, OffsetTime end)
    {
        return MILLISECONDS.toHours(millisBetween(start, end));
    }

    private static long millisUtc(OffsetTime offsetTime)
    {
        return offsetTime.atDate(LocalDate.ofEpochDay(0)).toInstant().toEpochMilli();
    }

    private static SqlTimestampWithTimeZone toTimestampWithTimeZone(DateTime dateTime)
    {
        return SqlTimestampWithTimeZone.newInstance(3, dateTime.getMillis(), 0, getTimeZoneKey(dateTime.getZone().getID()));
    }
}
