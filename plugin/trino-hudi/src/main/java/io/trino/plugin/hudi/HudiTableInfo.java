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
package io.trino.plugin.hudi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.SchemaTableName;

import static java.util.Objects.requireNonNull;

public class HudiTableInfo
{
    private final SchemaTableName table;
    private final String tableType;
    private final String basePath;

    @JsonCreator
    public HudiTableInfo(
            @JsonProperty("table") SchemaTableName table,
            @JsonProperty("tableType") String tableType,
            @JsonProperty("basePath") String basePath)
    {
        this.table = requireNonNull(table, "table is null");
        this.tableType = requireNonNull(tableType, "tableType is null");
        this.basePath = requireNonNull(basePath, "basePath is null");
    }

    @JsonProperty
    public SchemaTableName getTable()
    {
        return table;
    }

    @JsonProperty
    public String getTableType()
    {
        return tableType;
    }

    @JsonProperty
    public String getBasePath()
    {
        return basePath;
    }

    public static HudiTableInfo from(HudiTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        return new HudiTableInfo(
                tableHandle.getSchemaTableName(),
                tableHandle.getTableType().name(),
                tableHandle.getBasePath());
    }
}
