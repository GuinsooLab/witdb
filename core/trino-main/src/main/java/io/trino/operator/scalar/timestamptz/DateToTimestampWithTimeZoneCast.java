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

import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.LongTimestampWithTimeZone;
import io.trino.spi.type.StandardTypes;
import org.joda.time.chrono.ISOChronology;

import java.util.concurrent.TimeUnit;

import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.util.DateTimeZoneIndex.getChronology;

@ScalarOperator(CAST)
public final class DateToTimestampWithTimeZoneCast
{
    private DateToTimestampWithTimeZoneCast() {}

    @LiteralParameters("p")
    @SqlType("timestamp(p) with time zone")
    public static long castToShort(ConnectorSession session, @SqlType(StandardTypes.DATE) long date)
    {
        return packDateTimeWithZone(toEpochMillis(session, date), session.getTimeZoneKey());
    }

    @LiteralParameters("p")
    @SqlType("timestamp(p) with time zone")
    public static LongTimestampWithTimeZone castToLong(ConnectorSession session, @SqlType(StandardTypes.DATE) long date)
    {
        return LongTimestampWithTimeZone.fromEpochMillisAndFraction(toEpochMillis(session, date), 0, session.getTimeZoneKey());
    }

    private static long toEpochMillis(ConnectorSession session, @SqlType(StandardTypes.DATE) long date)
    {
        long epochMillis = TimeUnit.DAYS.toMillis(date);

        // date is encoded as milliseconds at midnight in UTC
        // convert to midnight in the session timezone
        ISOChronology chronology = getChronology(session.getTimeZoneKey());
        return epochMillis - chronology.getZone().getOffset(epochMillis);
    }
}
