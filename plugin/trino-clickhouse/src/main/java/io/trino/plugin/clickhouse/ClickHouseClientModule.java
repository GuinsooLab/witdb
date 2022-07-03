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
package io.trino.plugin.clickhouse;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.ConfigBinder;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.DecimalModule;
import io.trino.plugin.jdbc.DriverConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.credential.CredentialProvider;

import java.sql.Driver;

import static io.trino.plugin.jdbc.JdbcModule.bindSessionPropertiesProvider;
import static io.trino.plugin.jdbc.JdbcModule.bindTablePropertiesProvider;

public class ClickHouseClientModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        ConfigBinder.configBinder(binder).bindConfig(ClickHouseConfig.class);
        bindSessionPropertiesProvider(binder, ClickHouseSessionProperties.class);
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(ClickHouseClient.class).in(Scopes.SINGLETON);
        bindTablePropertiesProvider(binder, ClickHouseTableProperties.class);
        binder.install(new DecimalModule());
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public static ConnectionFactory createConnectionFactory(ClickHouseConfig clickHouseConfig, BaseJdbcConfig config, CredentialProvider credentialProvider)
    {
        return new ClickHouseConnectionFactory(new DriverConnectionFactory(createDriver(clickHouseConfig), config, credentialProvider));
    }

    private static Driver createDriver(ClickHouseConfig config)
    {
        if (config.isLegacyDriver()) {
            return new ru.yandex.clickhouse.ClickHouseDriver();
        }
        return new com.clickhouse.jdbc.ClickHouseDriver();
    }
}
