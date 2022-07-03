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
package io.trino.plugin.hive.metastore.thrift;

import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.multibindings.OptionalBinder;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.hive.metastore.HiveMetastoreFactory;
import io.trino.plugin.hive.metastore.RawHiveMetastoreFactory;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class ThriftMetastoreModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        OptionalBinder.newOptionalBinder(binder, ThriftMetastoreClientFactory.class)
                .setDefault().to(DefaultThriftMetastoreClientFactory.class).in(Scopes.SINGLETON);
        binder.bind(MetastoreLocator.class).to(StaticMetastoreLocator.class).in(Scopes.SINGLETON);
        binder.bind(TokenDelegationThriftMetastoreFactory.class);
        configBinder(binder).bindConfig(StaticMetastoreConfig.class);
        configBinder(binder).bindConfig(ThriftMetastoreConfig.class);

        binder.bind(ThriftMetastoreFactory.class).to(ThriftHiveMetastoreFactory.class).in(Scopes.SINGLETON);
        newExporter(binder).export(ThriftMetastoreFactory.class)
                .as(generator -> generator.generatedNameOf(ThriftHiveMetastore.class));

        binder.bind(HiveMetastoreFactory.class)
                .annotatedWith(RawHiveMetastoreFactory.class)
                .to(BridgingHiveMetastoreFactory.class)
                .in(Scopes.SINGLETON);

        install(new ThriftMetastoreAuthenticationModule());
    }
}
