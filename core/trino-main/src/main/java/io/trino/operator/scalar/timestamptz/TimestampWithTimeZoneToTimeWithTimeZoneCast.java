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
package io.trino.operator.scalar.timestamptz;

import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.LongTimeWithTimeZone;
import io.trino.spi.type.LongTimestampWithTimeZone;
import io.trino.spi.type.TimeZoneKey;
import io.trino.type.DateTimes;

import java.time.Instant;

import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.type.DateTimeEncoding.packTimeWithTimeZone;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateTimeEncoding.unpackZoneKey;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.trino.type.DateTimes.MILLISECONDS_PER_DAY;
import static io.trino.type.DateTimes.PICOSECONDS_PER_DAY;
import static io.trino.type.DateTimes.PICOSECONDS_PER_MILLISECOND;
import static io.trino.type.DateTimes.rescale;
import static io.trino.type.DateTimes.round;
import static io.trino.util.DateTimeZoneIndex.getChronology;
import static java.lang.Math.floorMod;

@ScalarOperator(CAST)
public final class TimestampWithTimeZoneToTimeWithTimeZoneCast
{
    private TimestampWithTimeZoneToTimeWithTimeZoneCast() {}

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static long shortToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("timestamp(sourcePrecision) with time zone") long packedTimestamp)
    {
        // source precision is <= 3
        // target precision is <= 9
        TimeZoneKey zoneKey = unpackZoneKey(packedTimestamp);
        long epochMillis = getChronology(zoneKey)
                .getZone()
                .convertUTCToLocal(unpackMillisUtc(packedTimestamp));

        if (targetPrecision <= 3) {
            epochMillis = round(epochMillis, (int) (3 - targetPrecision));
        }

        long nanos = rescale(floorMod(epochMillis, MILLISECONDS_PER_DAY), 3, 9);

        return packTimeWithTimeZone(nanos, DateTimes.getOffsetMinutes(Instant.ofEpochMilli(epochMillis), zoneKey));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static long longToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("timestamp(sourcePrecision) with time zone") LongTimestampWithTimeZone timestamp)
    {
        // source precision is > 3
        // target precision is <= 9
        TimeZoneKey zoneKey = getTimeZoneKey(timestamp.getTimeZoneKey());
        long epochMillis = getChronology(zoneKey)
                .getZone()
                .convertUTCToLocal(timestamp.getEpochMillis());

        // combine epochMillis with picosOfMilli from the timestamp. We compute modulo 24 to avoid overflow when rescaling epocMilli to picoseconds
        long picos = rescale(floorMod(epochMillis, MILLISECONDS_PER_DAY), 3, 12) + timestamp.getPicosOfMilli();

        picos = round(picos, (int) (12 - targetPrecision));
        picos = floorMod(picos, PICOSECONDS_PER_DAY);
        long nanos = rescale(picos, 12, 9);

        return packTimeWithTimeZone(nanos, DateTimes.getOffsetMinutes(Instant.ofEpochMilli(epochMillis), zoneKey));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static LongTimeWithTimeZone shortToLong(@SqlType("timestamp(sourcePrecision) with time zone") long timestamp)
    {
        // source precision is <= 3
        // target precision is > 9
        TimeZoneKey zoneKey = unpackZoneKey(timestamp);
        long epochMillis = getChronology(zoneKey)
                .getZone()
                .convertUTCToLocal(unpackMillisUtc(timestamp));

        long millis = floorMod(epochMillis, MILLISECONDS_PER_DAY);
        return new LongTimeWithTimeZone(millis * PICOSECONDS_PER_MILLISECOND, DateTimes.getOffsetMinutes(Instant.ofEpochMilli(epochMillis), zoneKey));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static LongTimeWithTimeZone longToLong(
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("timestamp(sourcePrecision) with time zone") LongTimestampWithTimeZone timestamp)
    {
        // source precision is > 3
        // target precision is > 9
        TimeZoneKey zoneKey = getTimeZoneKey(timestamp.getTimeZoneKey());
        long epochMillis = getChronology(zoneKey)
                .getZone()
                .convertUTCToLocal(timestamp.getEpochMillis());

        // combine epochMillis with picosOfMilli from the timestamp. We compute modulo 24 to avoid overflow when rescaling epocMilli to picoseconds
        long picos = rescale(floorMod(epochMillis, MILLISECONDS_PER_DAY), 3, 12) + timestamp.getPicosOfMilli();

        picos = round(picos, (int) (12 - targetPrecision));

        return new LongTimeWithTimeZone(floorMod(picos, PICOSECONDS_PER_DAY), DateTimes.getOffsetMinutes(Instant.ofEpochMilli(epochMillis), zoneKey));
    }
}
