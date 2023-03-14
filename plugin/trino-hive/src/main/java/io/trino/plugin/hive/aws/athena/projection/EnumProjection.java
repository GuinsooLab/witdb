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
package io.trino.plugin.hive.aws.athena.projection;

import com.google.common.collect.ImmutableList;
import io.trino.spi.predicate.Domain;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.util.List;
import java.util.Optional;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.spi.predicate.Domain.singleValue;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class EnumProjection
        extends Projection
{
    private final List<String> values;

    public EnumProjection(String columnName, List<String> values)
    {
        super(columnName);
        this.values = ImmutableList.copyOf(requireNonNull(values, "values is null"));
    }

    @Override
    public List<String> getProjectedValues(Optional<Domain> partitionValueFilter)
    {
        if (partitionValueFilter.isEmpty() || partitionValueFilter.get().isAll()) {
            return values;
        }
        return values.stream()
                .filter(value -> isValueInDomain(partitionValueFilter.get(), value))
                .collect(toList());
    }

    private boolean isValueInDomain(Domain valueDomain, String value)
    {
        Type type = valueDomain.getType();
        if (type instanceof VarcharType) {
            return valueDomain.contains(singleValue(type, utf8Slice(value)));
        }
        throw unsupportedProjectionColumnTypeException(type);
    }
}
