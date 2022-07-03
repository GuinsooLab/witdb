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
package io.trino.execution.resourcegroups.db;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.trino.plugin.resourcegroups.db.DbResourceGroupConfig;
import io.trino.plugin.resourcegroups.db.DbResourceGroupConfigurationManager;
import io.trino.plugin.resourcegroups.db.ForEnvironment;
import io.trino.plugin.resourcegroups.db.H2DaoProvider;
import io.trino.plugin.resourcegroups.db.ResourceGroupsDao;
import io.trino.spi.resourcegroups.ResourceGroupConfigurationManager;
import io.trino.spi.resourcegroups.ResourceGroupConfigurationManagerContext;

import javax.inject.Singleton;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class H2ResourceGroupsModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        configBinder(binder).bindConfig(DbResourceGroupConfig.class);
        binder.bind(ResourceGroupsDao.class).toProvider(H2DaoProvider.class).in(Scopes.SINGLETON);
        binder.bind(DbResourceGroupConfigurationManager.class).in(Scopes.SINGLETON);
        binder.bind(ResourceGroupConfigurationManager.class).to(DbResourceGroupConfigurationManager.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    @ForEnvironment
    public String getEnvironment(ResourceGroupConfigurationManagerContext context)
    {
        return context.getEnvironment();
    }
}
