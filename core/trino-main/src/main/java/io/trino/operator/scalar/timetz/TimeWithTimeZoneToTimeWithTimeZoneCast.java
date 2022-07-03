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
package io.trino.operator.scalar.timetz;

import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.LongTimeWithTimeZone;

import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.type.DateTimeEncoding.packTimeWithTimeZone;
import static io.trino.spi.type.DateTimeEncoding.unpackOffsetMinutes;
import static io.trino.spi.type.DateTimeEncoding.unpackTimeNanos;
import static io.trino.spi.type.TimeWithTimeZoneType.MAX_PRECISION;
import static io.trino.spi.type.TimeWithTimeZoneType.MAX_SHORT_PRECISION;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_DAY;
import static io.trino.type.DateTimes.PICOSECONDS_PER_DAY;
import static io.trino.type.DateTimes.PICOSECONDS_PER_NANOSECOND;
import static io.trino.type.DateTimes.round;

@ScalarOperator(CAST)
public final class TimeWithTimeZoneToTimeWithTimeZoneCast
{
    private TimeWithTimeZoneToTimeWithTimeZoneCast() {}

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static long shortToShort(
            @LiteralParameter("sourcePrecision") long sourcePrecision,
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("time(sourcePrecision) with time zone") long packedTime)
    {
        if (sourcePrecision <= targetPrecision) {
            return packedTime;
        }

        long nanos = unpackTimeNanos(packedTime);
        nanos = round(nanos, (int) (MAX_SHORT_PRECISION - targetPrecision));
        return packTimeWithTimeZone(nanos % NANOSECONDS_PER_DAY, unpackOffsetMinutes(packedTime));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static LongTimeWithTimeZone shortToLong(@SqlType("time(sourcePrecision) with time zone") long packedTime)
    {
        return new LongTimeWithTimeZone(unpackTimeNanos(packedTime) * PICOSECONDS_PER_NANOSECOND, unpackOffsetMinutes(packedTime));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static long longToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("time(sourcePrecision) with time zone") LongTimeWithTimeZone timestamp)
    {
        long nanos = round(timestamp.getPicoseconds(), (int) (MAX_PRECISION - targetPrecision)) / PICOSECONDS_PER_NANOSECOND;

        return packTimeWithTimeZone(nanos % NANOSECONDS_PER_DAY, timestamp.getOffsetMinutes());
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static LongTimeWithTimeZone longToLong(
            @LiteralParameter("targetPrecision") long targetPrecision,
            @SqlType("time(sourcePrecision) with time zone") LongTimeWithTimeZone timestamp)
    {
        return new LongTimeWithTimeZone(
                round(timestamp.getPicoseconds(), (int) (MAX_PRECISION - targetPrecision)) % PICOSECONDS_PER_DAY,
                timestamp.getOffsetMinutes());
    }
}
