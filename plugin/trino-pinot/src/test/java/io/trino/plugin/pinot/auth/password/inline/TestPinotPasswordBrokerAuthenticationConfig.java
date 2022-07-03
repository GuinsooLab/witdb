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
package io.trino.plugin.pinot.auth.password.inline;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;

public class TestPinotPasswordBrokerAuthenticationConfig
{
    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("pinot.broker.authentication.user", "query")
                .put("pinot.broker.authentication.password", "secret")
                .buildOrThrow();

        PinotPasswordBrokerAuthenticationConfig expected = new PinotPasswordBrokerAuthenticationConfig()
                .setUser("query")
                .setPassword("secret");

        assertFullMapping(properties, expected);
    }
}
