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
package io.trino.plugin.session.db;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

public class DbSessionPropertyManagerConfig
{
    private String configDbUrl;
    private String username;
    private String password;
    private Duration specsRefreshPeriod = new Duration(10, SECONDS);

    @NotNull
    public String getConfigDbUrl()
    {
        return configDbUrl;
    }

    @Config("session-property-manager.db.url")
    public DbSessionPropertyManagerConfig setConfigDbUrl(String configDbUrl)
    {
        this.configDbUrl = configDbUrl;
        return this;
    }

    @Nullable
    public String getUsername()
    {
        return username;
    }

    @Config("session-property-manager.db.username")
    public DbSessionPropertyManagerConfig setUsername(String username)
    {
        this.username = username;
        return this;
    }

    @Nullable
    public String getPassword()
    {
        return password;
    }

    @Config("session-property-manager.db.password")
    @ConfigSecuritySensitive
    public DbSessionPropertyManagerConfig setPassword(String password)
    {
        this.password = password;
        return this;
    }

    @NotNull
    @MinDuration("1ms")
    public Duration getSpecsRefreshPeriod()
    {
        return specsRefreshPeriod;
    }

    @Config("session-property-manager.db.refresh-period")
    public DbSessionPropertyManagerConfig setSpecsRefreshPeriod(Duration specsRefreshPeriod)
    {
        this.specsRefreshPeriod = specsRefreshPeriod;
        return this;
    }
}
