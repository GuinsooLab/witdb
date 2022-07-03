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
package io.trino.plugin.kafka.encoder.json.format;

import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlTime;
import io.trino.spi.type.SqlTimeWithTimeZone;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlTimestampWithTimeZone;

public interface JsonDateTimeFormatter
{
    default String formatDate(SqlDate value)
    {
        throw new UnsupportedOperationException("This formatter does not support formatting of date types");
    }

    default String formatTime(SqlTime value, int precision)
    {
        throw new UnsupportedOperationException("This formatter does not support formatting of time types");
    }

    default String formatTimeWithZone(SqlTimeWithTimeZone value)
    {
        throw new UnsupportedOperationException("This formatter does not support formatting of time with time zone types");
    }

    default String formatTimestamp(SqlTimestamp value)
    {
        throw new UnsupportedOperationException("This formatter does not support formatting of timestamp types");
    }

    default String formatTimestampWithZone(SqlTimestampWithTimeZone value)
    {
        throw new UnsupportedOperationException("This formatter does not support formatting of timestamp with time zone types");
    }
}
