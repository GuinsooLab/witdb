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
package io.trino.plugin.hive;

import io.airlift.slice.Slice;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;

import java.time.ZoneId;

import static io.airlift.slice.Slices.utf8Slice;

/**
 * Translate timezone id used by Hive to canonical form which is understandable by Trino; used in Hive view translation logic
 */
public final class CanonicalizeHiveTimezoneId
{
    private CanonicalizeHiveTimezoneId() {}

    @ScalarFunction(value = "$canonicalize_hive_timezone_id", hidden = true)
    @LiteralParameters("x")
    @SqlType("varchar")
    public static Slice canonicalizeHiveTimezoneId(@SqlType("varchar(x)") Slice hiveTimeZoneId)
    {
        if (hiveTimeZoneId != null) {
            String zoneId = hiveTimeZoneId.toStringUtf8();
            if (ZoneId.SHORT_IDS.containsKey(zoneId)) {
                return utf8Slice(ZoneId.SHORT_IDS.get(zoneId));
            }
        }
        return hiveTimeZoneId;
    }
}
