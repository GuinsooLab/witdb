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
package io.trino.execution.scheduler;

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.testing.ConfigAssertions;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MINUTES;

public class TestTopologyFileConfig
{
    @Test
    public void testDefaults()
    {
        ConfigAssertions.assertRecordedDefaults(ConfigAssertions.recordDefaults(TopologyFileConfig.class)
                .setNetworkTopologyFile(null)
                .setRefreshPeriod(new Duration(5, MINUTES)));
    }

    @Test
    public void testExplicitPropertyMappings()
            throws IOException
    {
        Path networkTopologyFile = Files.createTempFile(null, null);

        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("node-scheduler.network-topology.file", networkTopologyFile.toString())
                .put("node-scheduler.network-topology.refresh-period", "27m")
                .buildOrThrow();

        TopologyFileConfig expected = new TopologyFileConfig()
                .setNetworkTopologyFile(networkTopologyFile.toFile())
                .setRefreshPeriod(new Duration(27, MINUTES));

        ConfigAssertions.assertFullMapping(properties, expected);
    }
}
