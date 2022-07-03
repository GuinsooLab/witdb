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
package io.trino.plugin.session.db.util;

public final class SessionPropertiesDaoUtil
{
    private SessionPropertiesDaoUtil() {}

    public static final String SESSION_SPECS_TABLE = "session_specs";
    public static final String CLIENT_TAGS_TABLE = "session_client_tags";
    public static final String PROPERTIES_TABLE = "session_property_values";
}
