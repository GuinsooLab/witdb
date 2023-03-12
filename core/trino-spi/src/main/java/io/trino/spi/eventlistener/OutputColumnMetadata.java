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
package io.trino.spi.eventlistener;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.Unstable;

import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * This class is JSON serializable for convenience and serialization compatibility is not guaranteed across versions.
 */
public class OutputColumnMetadata
{
    private final String columnName;
    private final String columnType;
    private final Set<ColumnDetail> sourceColumns;

    @JsonCreator
    @Unstable
    public OutputColumnMetadata(String columnName, String columnType, Set<ColumnDetail> sourceColumns)
    {
        this.columnName = requireNonNull(columnName, "columnName is null");
        this.columnType = requireNonNull(columnType, "columnType is null");
        this.sourceColumns = requireNonNull(sourceColumns, "sourceColumns is null");
    }

    @JsonProperty
    public String getColumnName()
    {
        return columnName;
    }

    @JsonProperty
    public String getColumnType()
    {
        return columnType;
    }

    @JsonProperty
    public Set<ColumnDetail> getSourceColumns()
    {
        return sourceColumns;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(columnName, columnType, sourceColumns);
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
        OutputColumnMetadata other = (OutputColumnMetadata) obj;
        return Objects.equals(columnName, other.columnName) &&
                Objects.equals(columnType, other.columnType) &&
                Objects.equals(sourceColumns, other.sourceColumns);
    }
}
