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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;

import java.util.Map;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.airlift.slice.SizeOf.instanceSize;
import static java.util.Objects.requireNonNull;

public class SystemColumnHandle
        implements ColumnHandle
{
    private static final int INSTANCE_SIZE = instanceSize(SystemColumnHandle.class);

    private final String columnName;

    @JsonCreator
    public SystemColumnHandle(
            @JsonProperty("columnName") String columnName)
    {
        this.columnName = requireNonNull(columnName, "columnName is null");
    }

    @JsonProperty
    public String getColumnName()
    {
        return columnName;
    }

    @Override
    public int hashCode()
    {
        return columnName.hashCode();
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
        SystemColumnHandle other = (SystemColumnHandle) obj;
        return columnName.equals(other.columnName);
    }

    @Override
    public String toString()
    {
        return columnName;
    }

    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + estimatedSizeOf(columnName);
    }

    public static Map<String, ColumnHandle> toSystemColumnHandles(ConnectorTableMetadata tableMetadata)
    {
        return tableMetadata.getColumns().stream().collect(toImmutableMap(
                ColumnMetadata::getName,
                column -> new SystemColumnHandle(column.getName())));
    }
}
