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
package io.trino.plugin.cassandra;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.plugin.cassandra.util.CassandraCqlUtils;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.type.Type;

import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class CassandraColumnHandle
        implements ColumnHandle
{
    private final String name;
    private final int ordinalPosition;
    private final CassandraType cassandraType;
    private final boolean partitionKey;
    private final boolean clusteringKey;
    private final boolean indexed;
    private final boolean hidden;

    @JsonCreator
    public CassandraColumnHandle(
            @JsonProperty("name") String name,
            @JsonProperty("ordinalPosition") int ordinalPosition,
            @JsonProperty("cassandraType") CassandraType cassandraType,
            @JsonProperty("partitionKey") boolean partitionKey,
            @JsonProperty("clusteringKey") boolean clusteringKey,
            @JsonProperty("indexed") boolean indexed,
            @JsonProperty("hidden") boolean hidden)
    {
        this.name = requireNonNull(name, "name is null");
        checkArgument(ordinalPosition >= 0, "ordinalPosition is negative");
        this.ordinalPosition = ordinalPosition;
        this.cassandraType = requireNonNull(cassandraType, "cassandraType is null");
        this.partitionKey = partitionKey;
        this.clusteringKey = clusteringKey;
        this.indexed = indexed;
        this.hidden = hidden;
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public int getOrdinalPosition()
    {
        return ordinalPosition;
    }

    @JsonProperty
    public CassandraType getCassandraType()
    {
        return cassandraType;
    }

    @JsonProperty
    public boolean isPartitionKey()
    {
        return partitionKey;
    }

    @JsonProperty
    public boolean isClusteringKey()
    {
        return clusteringKey;
    }

    @JsonProperty
    public boolean isIndexed()
    {
        return indexed;
    }

    @JsonProperty
    public boolean isHidden()
    {
        return hidden;
    }

    public ColumnMetadata getColumnMetadata()
    {
        return ColumnMetadata.builder()
                .setName(CassandraCqlUtils.cqlNameToSqlName(name))
                .setType(cassandraType.getTrinoType())
                .setHidden(hidden)
                .build();
    }

    public Type getType()
    {
        return cassandraType.getTrinoType();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(
                name,
                ordinalPosition,
                cassandraType,
                partitionKey,
                clusteringKey,
                indexed,
                hidden);
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
        CassandraColumnHandle other = (CassandraColumnHandle) obj;
        return Objects.equals(this.name, other.name) &&
                Objects.equals(this.ordinalPosition, other.ordinalPosition) &&
                Objects.equals(this.cassandraType, other.cassandraType) &&
                Objects.equals(this.partitionKey, other.partitionKey) &&
                Objects.equals(this.clusteringKey, other.clusteringKey) &&
                Objects.equals(this.indexed, other.indexed) &&
                Objects.equals(this.hidden, other.hidden);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", name)
                .add("ordinalPosition", ordinalPosition)
                .add("cassandraType", cassandraType)
                .add("partitionKey", partitionKey)
                .add("clusteringKey", clusteringKey)
                .add("indexed", indexed)
                .add("hidden", hidden)
                .toString();
    }
}
