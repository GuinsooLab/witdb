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
package io.trino.connector.system;

import io.trino.metadata.Metadata;
import io.trino.metadata.SchemaPropertyManager;
import io.trino.security.AccessControl;

import javax.inject.Inject;

public class SchemaPropertiesSystemTable
        extends AbstractPropertiesSystemTable
{
    @Inject
    public SchemaPropertiesSystemTable(Metadata metadata, AccessControl accessControl, SchemaPropertyManager schemaPropertyManager)
    {
        super("schema_properties", metadata, accessControl, schemaPropertyManager::getAllProperties);
    }
}
