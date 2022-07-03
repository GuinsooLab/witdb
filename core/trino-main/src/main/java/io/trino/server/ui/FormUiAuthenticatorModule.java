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
package io.trino.server.ui;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import io.trino.server.security.Authenticator;
import io.trino.server.security.PasswordAuthenticatorConfig;
import io.trino.server.security.PasswordAuthenticatorManager;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;

public class FormUiAuthenticatorModule
        implements Module
{
    private final boolean usePasswordManager;

    public FormUiAuthenticatorModule(boolean usePasswordManager)
    {
        this.usePasswordManager = usePasswordManager;
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(FormWebUiAuthenticationFilter.class).in(SINGLETON);
        binder.bind(WebUiAuthenticationFilter.class).to(FormWebUiAuthenticationFilter.class).in(SINGLETON);
        if (usePasswordManager) {
            binder.bind(PasswordAuthenticatorManager.class).in(SINGLETON);
            configBinder(binder).bindConfig(PasswordAuthenticatorConfig.class);
            binder.bind(FormAuthenticator.class).to(PasswordManagerFormAuthenticator.class).in(SINGLETON);
        }
        else {
            binder.bind(FormAuthenticator.class).to(InsecureFormAuthenticator.class).in(SINGLETON);
        }
        configBinder(binder).bindConfig(FormWebUiConfig.class);
        jaxrsBinder(binder).bind(LoginResource.class);
        newOptionalBinder(binder, Key.get(Authenticator.class, ForWebUi.class));
    }
}
