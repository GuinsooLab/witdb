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
package io.trino.plugin.kafka;

import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Module;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.kafka.security.KafkaSecurityModule;
import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;

import static java.util.Objects.requireNonNull;

public class KafkaPlugin
        implements Plugin
{
    public static final Module DEFAULT_EXTENSION = new AbstractConfigurationAwareModule()
    {
        @Override
        protected void setup(Binder binder)
        {
            install(new KafkaClientsModule());
            install(new KafkaSecurityModule());
        }
    };

    private final Module extension;

    public KafkaPlugin()
    {
        this(DEFAULT_EXTENSION);
    }

    public KafkaPlugin(Module extension)
    {
        this.extension = requireNonNull(extension, "extension is null");
    }

    @Override
    public synchronized Iterable<ConnectorFactory> getConnectorFactories()
    {
        return ImmutableList.of(new KafkaConnectorFactory(extension));
    }
}
