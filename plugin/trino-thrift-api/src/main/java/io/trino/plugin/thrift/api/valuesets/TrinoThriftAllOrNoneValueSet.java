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
package io.trino.plugin.thrift.api.valuesets;

import io.airlift.drift.annotations.ThriftConstructor;
import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftStruct;
import io.trino.spi.predicate.AllOrNoneValueSet;

import static com.google.common.base.MoreObjects.toStringHelper;

/**
 * Set that either includes all values, or excludes all values.
 */
@ThriftStruct
public final class TrinoThriftAllOrNoneValueSet
{
    private final boolean all;

    @ThriftConstructor
    public TrinoThriftAllOrNoneValueSet(boolean all)
    {
        this.all = all;
    }

    @ThriftField(1)
    public boolean isAll()
    {
        return all;
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
        TrinoThriftAllOrNoneValueSet other = (TrinoThriftAllOrNoneValueSet) obj;
        return this.all == other.all;
    }

    @Override
    public int hashCode()
    {
        return Boolean.hashCode(all);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("all", all)
                .toString();
    }

    public static TrinoThriftAllOrNoneValueSet fromAllOrNoneValueSet(AllOrNoneValueSet valueSet)
    {
        return new TrinoThriftAllOrNoneValueSet(valueSet.isAll());
    }
}
