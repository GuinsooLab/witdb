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
package io.trino.proxy;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestJwtHandlerConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(JwtHandlerConfig.class)
                .setJwtKeyFile(null)
                .setJwtKeyFilePassword(null)
                .setJwtKeyId(null)
                .setJwtIssuer(null)
                .setJwtAudience(null));
    }

    @Test
    public void testExplicitPropertyMappings()
            throws IOException
    {
        Path keyFile = Files.createTempFile(null, null);

        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("jwt.key-file", keyFile.toString())
                .put("jwt.key-file-password", "password")
                .put("jwt.key-id", "testkeyid")
                .put("jwt.issuer", "testissuer")
                .put("jwt.audience", "testaudience")
                .buildOrThrow();

        JwtHandlerConfig expected = new JwtHandlerConfig()
                .setJwtKeyFile(keyFile.toFile())
                .setJwtKeyFilePassword("password")
                .setJwtKeyId("testkeyid")
                .setJwtIssuer("testissuer")
                .setJwtAudience("testaudience");

        assertFullMapping(properties, expected);
    }
}
