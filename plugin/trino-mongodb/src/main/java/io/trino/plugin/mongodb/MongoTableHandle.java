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
package io.trino.plugin.mongodb;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.TupleDomain;

import java.util.Objects;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

public class MongoTableHandle
        implements ConnectorTableHandle
{
    private final SchemaTableName schemaTableName;
    private final TupleDomain<ColumnHandle> constraint;
    private final OptionalInt limit;

    public MongoTableHandle(SchemaTableName schemaTableName)
    {
        this(schemaTableName, TupleDomain.all(), OptionalInt.empty());
    }

    @JsonCreator
    public MongoTableHandle(
            @JsonProperty("schemaTableName") SchemaTableName schemaTableName,
            @JsonProperty("constraint") TupleDomain<ColumnHandle> constraint,
            @JsonProperty("limit") OptionalInt limit)
    {
        this.schemaTableName = requireNonNull(schemaTableName, "schemaTableName is null");
        this.constraint = requireNonNull(constraint, "constraint is null");
        this.limit = requireNonNull(limit, "limit is null");
    }

    @JsonProperty
    public SchemaTableName getSchemaTableName()
    {
        return schemaTableName;
    }

    @JsonProperty
    public TupleDomain<ColumnHandle> getConstraint()
    {
        return constraint;
    }

    @JsonProperty
    public OptionalInt getLimit()
    {
        return limit;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaTableName, constraint, limit);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MongoTableHandle other = (MongoTableHandle) obj;
        return Objects.equals(this.schemaTableName, other.schemaTableName) &&
                Objects.equals(this.constraint, other.constraint) &&
                Objects.equals(this.limit, other.limit);
    }

    @Override
    public String toString()
    {
        return schemaTableName.toString();
    }
}
