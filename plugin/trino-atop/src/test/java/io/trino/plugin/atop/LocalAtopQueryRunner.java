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
package io.trino.plugin.atop;

import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.spi.type.TimeZoneKey;
import io.trino.testing.LocalQueryRunner;

import java.util.Map;
import java.util.TimeZone;

import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.trino.testing.TestingSession.testSessionBuilder;

public final class LocalAtopQueryRunner
{
    private LocalAtopQueryRunner() {}

    public static LocalQueryRunner createQueryRunner()
    {
        return createQueryRunner(ImmutableMap.of("atop.executable-path", "/dev/null"), TestingAtopFactory.class);
    }

    public static LocalQueryRunner createQueryRunner(Map<String, String> catalogProperties, Class<? extends AtopFactory> factoryClass)
    {
        Session session = testSessionBuilder()
                .setCatalog("atop")
                .setSchema("default")
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(TimeZone.getDefault().getID()))
                .build();

        LocalQueryRunner queryRunner = LocalQueryRunner.create(session);

        try {
            AtopConnectorFactory connectorFactory = new AtopConnectorFactory(factoryClass, LocalAtopQueryRunner.class.getClassLoader());
            ImmutableMap.Builder<String, String> properties = ImmutableMap.<String, String>builder()
                    .putAll(catalogProperties)
                    .put("atop.max-history-days", "1");

            queryRunner.createCatalog("atop", connectorFactory, properties.buildOrThrow());

            return queryRunner;
        }
        catch (Exception e) {
            closeAllSuppress(e, queryRunner);
            throw e;
        }
    }
}
