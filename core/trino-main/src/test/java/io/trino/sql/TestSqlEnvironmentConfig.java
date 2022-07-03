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
package io.trino.sql;

import com.google.common.collect.ImmutableMap;
import io.trino.sql.parser.ParsingException;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSqlEnvironmentConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(SqlEnvironmentConfig.class)
                .setPath(null)
                .setDefaultCatalog(null)
                .setDefaultSchema(null)
                .setForcedSessionTimeZone(null));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("sql.path", "a.b, c.d")
                .put("sql.default-catalog", "some-catalog")
                .put("sql.default-schema", "some-schema")
                .put("sql.forced-session-time-zone", "UTC")
                .buildOrThrow();

        SqlEnvironmentConfig expected = new SqlEnvironmentConfig()
                .setPath("a.b, c.d")
                .setDefaultCatalog("some-catalog")
                .setDefaultSchema("some-schema")
                .setForcedSessionTimeZone("UTC");

        assertFullMapping(properties, expected);
    }

    @Test
    public void testInvalidPath()
    {
        SqlEnvironmentConfig config = new SqlEnvironmentConfig().setPath("too.many.qualifiers");
        assertThatThrownBy(() -> new SqlPath(config.getPath()).getParsedPath())
                .isInstanceOf(ParsingException.class)
                .hasMessageMatching("\\Qline 1:9: mismatched input '.'. Expecting: ',', <EOF>\\E");
    }
}
