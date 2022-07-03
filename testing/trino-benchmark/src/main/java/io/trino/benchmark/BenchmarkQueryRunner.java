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
package io.trino.benchmark;

import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.plugin.tpch.TpchConnectorFactory;
import io.trino.testing.LocalQueryRunner;

import java.util.Map;

import static io.trino.Session.SessionBuilder;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;

public final class BenchmarkQueryRunner
{
    private BenchmarkQueryRunner() {}

    public static LocalQueryRunner createLocalQueryRunnerHashEnabled()
    {
        return createLocalQueryRunner(ImmutableMap.of("optimizer.optimize_hash_generation", "true"));
    }

    public static LocalQueryRunner createLocalQueryRunner()
    {
        return createLocalQueryRunner(ImmutableMap.of());
    }

    public static LocalQueryRunner createLocalQueryRunner(Map<String, String> extraSessionProperties)
    {
        SessionBuilder sessionBuilder = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema(TINY_SCHEMA_NAME);

        extraSessionProperties.forEach(sessionBuilder::setSystemProperty);

        Session session = sessionBuilder.build();
        LocalQueryRunner localQueryRunner = LocalQueryRunner.create(session);

        // add tpch
        localQueryRunner.createCatalog("tpch", new TpchConnectorFactory(1), ImmutableMap.of());

        return localQueryRunner;
    }
}
