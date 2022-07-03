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
package io.trino.plugin.raptor.legacy.security;

import com.google.inject.Binder;
import com.google.inject.Module;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.base.security.ConnectorAccessControlModule;
import io.trino.plugin.base.security.FileBasedAccessControlModule;
import io.trino.plugin.base.security.ReadOnlySecurityModule;

import static io.airlift.configuration.ConditionalModule.conditionalModule;
import static io.trino.plugin.raptor.legacy.security.RaptorSecurity.FILE;
import static io.trino.plugin.raptor.legacy.security.RaptorSecurity.READ_ONLY;

public class RaptorSecurityModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        install(new ConnectorAccessControlModule());
        bindSecurityModule(READ_ONLY, new ReadOnlySecurityModule());
        bindSecurityModule(FILE, new FileBasedAccessControlModule());
    }

    private void bindSecurityModule(RaptorSecurity raptorSecurity, Module module)
    {
        install(conditionalModule(
                RaptorSecurityConfig.class,
                security -> raptorSecurity.equals(security.getSecuritySystem()),
                module));
    }
}
