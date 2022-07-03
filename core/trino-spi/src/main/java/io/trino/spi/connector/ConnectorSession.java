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
package io.trino.spi.connector;

import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.type.TimeZoneKey;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

public interface ConnectorSession
{
    String getQueryId();

    Optional<String> getSource();

    default String getUser()
    {
        return getIdentity().getUser();
    }

    ConnectorIdentity getIdentity();

    TimeZoneKey getTimeZoneKey();

    Locale getLocale();

    Optional<String> getTraceToken();

    /**
     * @deprecated use {@link #getStart()} instead
     */
    @Deprecated
    default long getStartTime()
    {
        return getStart().toEpochMilli();
    }

    Instant getStart();

    <T> T getProperty(String name, Class<T> type);
}
