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
package io.trino.spi.connector;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class MaterializedViewNotFoundException
        extends NotFoundException
{
    private final SchemaTableName materializedViewName;

    public MaterializedViewNotFoundException(SchemaTableName materializedViewName)
    {
        this(materializedViewName, format("Materialized View '%s' not found", materializedViewName));
    }

    public MaterializedViewNotFoundException(SchemaTableName materializedViewName, String message)
    {
        super(message);
        this.materializedViewName = requireNonNull(materializedViewName, "materializedViewName is null");
    }

    public MaterializedViewNotFoundException(SchemaTableName materializedViewName, Throwable cause)
    {
        this(materializedViewName, format("Materialized View '%s' not found", materializedViewName), cause);
    }

    public MaterializedViewNotFoundException(SchemaTableName materializedViewName, String message, Throwable cause)
    {
        super(message, cause);
        this.materializedViewName = requireNonNull(materializedViewName, "materializedViewName is null");
    }

    public SchemaTableName getMaterializedViewName()
    {
        return materializedViewName;
    }
}
