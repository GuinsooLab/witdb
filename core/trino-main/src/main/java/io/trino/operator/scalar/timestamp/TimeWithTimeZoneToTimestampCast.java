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
package io.trino.operator.scalar.timestamp;

import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.LongTimeWithTimeZone;
import io.trino.spi.type.LongTimestamp;

import java.time.LocalDate;

import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.type.DateTimeEncoding.unpackTimeNanos;
import static io.trino.type.DateTimes.MICROSECONDS_PER_SECOND;
import static io.trino.type.DateTimes.PICOSECONDS_PER_MICROSECOND;
import static io.trino.type.DateTimes.PICOSECONDS_PER_SECOND;
import static io.trino.type.DateTimes.SECONDS_PER_DAY;
import static io.trino.type.DateTimes.rescale;
import static io.trino.type.DateTimes.round;
import static java.lang.Math.multiplyExact;

@ScalarOperator(CAST)
public final class TimeWithTimeZoneToTimestampCast
{
    private TimeWithTimeZoneToTimestampCast() {}

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("timestamp(targetPrecision)")
    public static long shortToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision) with time zone") long packedTime)
    {
        // source precision <= 9
        // target precision <= 6
        long picos = rescale(unpackTimeNanos(packedTime), 9, 12);
        picos = round(picos, (int) (12 - targetPrecision));

        return calculateEpochMicros(session, picos);
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("timestamp(targetPrecision)")
    public static long longToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision) with time zone") LongTimeWithTimeZone time)
    {
        // source precision > 9
        // target precision <= 6
        long picos = time.getPicoseconds();
        picos = round(picos, (int) (12 - targetPrecision));

        return calculateEpochMicros(session, picos);
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("timestamp(targetPrecision)")
    public static LongTimestamp shortToLong(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision) with time zone") long packedTime)
    {
        // source precision <= 9
        // target precision > 6
        long picos = rescale(unpackTimeNanos(packedTime), 9, 12);
        picos = round(picos, (int) (12 - targetPrecision));

        long epochMicros = calculateEpochMicros(session, picos);

        return new LongTimestamp(epochMicros, (int) (picos % PICOSECONDS_PER_MICROSECOND));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("timestamp(targetPrecision)")
    public static LongTimestamp longToLong(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision) with time zone") LongTimeWithTimeZone time)
    {
        // source precision > 9
        // target precision > 6
        long picos = time.getPicoseconds();
        picos = round(picos, (int) (12 - targetPrecision));

        long epochMicros = calculateEpochMicros(session, picos);

        return new LongTimestamp(epochMicros, (int) (picos % PICOSECONDS_PER_MICROSECOND));
    }

    private static long calculateEpochMicros(ConnectorSession session, long picos)
    {
        // TODO: consider using something more efficient than LocalDate.ofInstant() to compute epochDay
        long epochDay = LocalDate.ofInstant(session.getStart(), session.getTimeZoneKey().getZoneId())
                .toEpochDay();

        long epochSecond = multiplyExact(epochDay, SECONDS_PER_DAY) + picos / PICOSECONDS_PER_SECOND;
        long picoFraction = picos % PICOSECONDS_PER_SECOND;
        return multiplyExact(epochSecond, MICROSECONDS_PER_SECOND) + rescale(picoFraction, 12, 6);
    }
}
