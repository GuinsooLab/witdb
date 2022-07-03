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
package io.trino.plugin.jdbc;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.ALLOW_OVERFLOW;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.STRICT;
import static java.math.RoundingMode.HALF_UP;
import static java.math.RoundingMode.UNNECESSARY;

public class TestDecimalConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(DecimalConfig.class)
                .setDecimalMapping(STRICT)
                .setDecimalDefaultScale(0)
                .setDecimalRoundingMode(UNNECESSARY));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("decimal-mapping", "allow_overflow")
                .put("decimal-default-scale", "16")
                .put("decimal-rounding-mode", "HALF_UP")
                .buildOrThrow();

        DecimalConfig expected = new DecimalConfig()
                .setDecimalMapping(ALLOW_OVERFLOW)
                .setDecimalDefaultScale(16)
                .setDecimalRoundingMode(HALF_UP);

        assertFullMapping(properties, expected);
    }
}
