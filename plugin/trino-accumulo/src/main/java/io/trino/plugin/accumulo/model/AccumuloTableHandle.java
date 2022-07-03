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
package io.trino.plugin.accumulo.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.plugin.accumulo.metadata.AccumuloTable;
import io.trino.plugin.accumulo.serializers.AccumuloRowSerializer;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.TupleDomain;

import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static java.util.Objects.requireNonNull;

public final class AccumuloTableHandle
        implements ConnectorInsertTableHandle, ConnectorOutputTableHandle, ConnectorTableHandle
{
    private final boolean external;
    private final String rowId;
    private final Optional<String> scanAuthorizations;
    private final String schema;
    private final String serializerClassName;
    private final String table;
    private final TupleDomain<ColumnHandle> constraint;

    public AccumuloTableHandle(
            String schema,
            String table,
            String rowId,
            boolean external,
            String serializerClassName,
            Optional<String> scanAuthorizations)
    {
        this(schema, table, rowId, TupleDomain.all(), external, serializerClassName, scanAuthorizations);
    }

    @JsonCreator
    public AccumuloTableHandle(
            @JsonProperty("schema") String schema,
            @JsonProperty("table") String table,
            @JsonProperty("rowId") String rowId,
            @JsonProperty("constraint") TupleDomain<ColumnHandle> constraint,
            @JsonProperty("external") boolean external,
            @JsonProperty("serializerClassName") String serializerClassName,
            @JsonProperty("scanAuthorizations") Optional<String> scanAuthorizations)
    {
        this.external = external;
        this.rowId = requireNonNull(rowId, "rowId is null");
        this.scanAuthorizations = scanAuthorizations;
        this.schema = requireNonNull(schema, "schema is null");
        this.serializerClassName = requireNonNull(serializerClassName, "serializerClassName is null");
        this.table = requireNonNull(table, "table is null");
        this.constraint = requireNonNull(constraint, "constraints is null");
    }

    @JsonProperty
    public String getRowId()
    {
        return rowId;
    }

    @JsonProperty
    public Optional<String> getScanAuthorizations()
    {
        return scanAuthorizations;
    }

    @JsonProperty
    public String getSchema()
    {
        return schema;
    }

    @JsonProperty
    public String getSerializerClassName()
    {
        return serializerClassName;
    }

    @JsonIgnore
    public AccumuloRowSerializer getSerializerInstance()
    {
        try {
            return (AccumuloRowSerializer) Class.forName(serializerClassName).getConstructor().newInstance();
        }
        catch (Exception e) {
            throw new TrinoException(NOT_FOUND, "Configured serializer class not found", e);
        }
    }

    @JsonProperty
    public String getTable()
    {
        return table;
    }

    @JsonProperty
    public boolean isExternal()
    {
        return external;
    }

    @JsonProperty
    public TupleDomain<ColumnHandle> getConstraint()
    {
        return constraint;
    }

    public SchemaTableName toSchemaTableName()
    {
        return new SchemaTableName(schema, table);
    }

    @JsonIgnore
    public String getFullTableName()
    {
        return AccumuloTable.getFullTableName(schema, table);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schema, table, rowId, external, serializerClassName);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }

        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }

        AccumuloTableHandle other = (AccumuloTableHandle) obj;
        return Objects.equals(this.schema, other.schema)
                && Objects.equals(this.table, other.table)
                && Objects.equals(this.rowId, other.rowId)
                && Objects.equals(this.external, other.external)
                && Objects.equals(this.serializerClassName, other.serializerClassName)
                && Objects.equals(this.scanAuthorizations, other.scanAuthorizations);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("schema", schema)
                .add("table", table)
                .add("rowId", rowId)
                .add("internal", external)
                .add("serializerClassName", serializerClassName)
                .add("scanAuthorizations", scanAuthorizations)
                .toString();
    }
}
