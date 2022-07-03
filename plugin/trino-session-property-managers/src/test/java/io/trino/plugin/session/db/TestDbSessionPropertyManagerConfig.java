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
package io.trino.plugin.session.db;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestDbSessionPropertyManagerConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(DbSessionPropertyManagerConfig.class)
                .setConfigDbUrl(null)
                .setUsername(null)
                .setPassword(null)
                .setSpecsRefreshPeriod(new Duration(10, SECONDS)));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("session-property-manager.db.url", "foo")
                .put("session-property-manager.db.username", "bar")
                .put("session-property-manager.db.password", "pass")
                .put("session-property-manager.db.refresh-period", "50s")
                .buildOrThrow();

        DbSessionPropertyManagerConfig expected = new DbSessionPropertyManagerConfig()
                .setConfigDbUrl("foo")
                .setUsername("bar")
                .setPassword("pass")
                .setSpecsRefreshPeriod(new Duration(50, TimeUnit.SECONDS));

        assertFullMapping(properties, expected);
    }
}
