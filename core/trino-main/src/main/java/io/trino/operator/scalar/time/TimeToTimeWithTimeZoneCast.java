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

import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.LongTimeWithTimeZone;

import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.type.DateTimeEncoding.packTimeWithTimeZone;
import static io.trino.type.DateTimes.NANOSECONDS_PER_DAY;
import static io.trino.type.DateTimes.PICOSECONDS_PER_DAY;
import static io.trino.type.DateTimes.getOffsetMinutes;
import static io.trino.type.DateTimes.rescale;
import static io.trino.type.DateTimes.round;

@ScalarOperator(CAST)
public final class TimeToTimeWithTimeZoneCast
{
    private TimeToTimeWithTimeZoneCast() {}

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static long castToShort(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision)") long time)
    {
        long picos = round(time, (int) (12 - targetPrecision));
        long nanos = rescale(picos, 12, 9) % NANOSECONDS_PER_DAY;

        return packTimeWithTimeZone(nanos, getOffsetMinutes(session.getStart(), session.getTimeZoneKey()));
    }

    @LiteralParameters({"sourcePrecision", "targetPrecision"})
    @SqlType("time(targetPrecision) with time zone")
    public static LongTimeWithTimeZone castToLong(
            @LiteralParameter("targetPrecision") long targetPrecision,
            ConnectorSession session,
            @SqlType("time(sourcePrecision)") long time)
    {
        long picos = round(time, (int) (12 - targetPrecision)) % PICOSECONDS_PER_DAY;

        return new LongTimeWithTimeZone(picos, getOffsetMinutes(session.getStart(), session.getTimeZoneKey()));
    }
}
