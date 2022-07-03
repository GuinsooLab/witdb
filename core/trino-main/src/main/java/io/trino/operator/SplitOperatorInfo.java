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
package io.trino.operator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.connector.CatalogName;

import static java.util.Objects.requireNonNull;

public class SplitOperatorInfo
        implements OperatorInfo
{
    private final CatalogName catalogName;
    // NOTE: this deserializes to a map instead of the expected type
    private final Object splitInfo;

    @JsonCreator
    public SplitOperatorInfo(
            @JsonProperty("catalogName") CatalogName catalogName,
            @JsonProperty("splitInfo") Object splitInfo)
    {
        this.catalogName = requireNonNull(catalogName, "catalogName is null");
        this.splitInfo = splitInfo;
    }

    @Override
    public boolean isFinal()
    {
        return true;
    }

    @JsonProperty
    public Object getSplitInfo()
    {
        return splitInfo;
    }

    @JsonProperty
    public CatalogName getCatalogName()
    {
        return catalogName;
    }
}
