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
package io.trino.plugin.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.trino.spi.HostAddress;
import io.trino.spi.connector.ConnectorSplit;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOf;
import static java.util.Objects.requireNonNull;

public class MemorySplit
        implements ConnectorSplit
{
    private static final int INSTANCE_SIZE = instanceSize(MemorySplit.class);

    private final long table;
    private final int totalPartsPerWorker; // how many concurrent reads there will be from one worker
    private final int partNumber; // part of the pages on one worker that this splits is responsible
    private final HostAddress address;
    private final long expectedRows;
    private final OptionalLong limit;

    @JsonCreator
    public MemorySplit(
            @JsonProperty("table") long table,
            @JsonProperty("partNumber") int partNumber,
            @JsonProperty("totalPartsPerWorker") int totalPartsPerWorker,
            @JsonProperty("address") HostAddress address,
            @JsonProperty("expectedRows") long expectedRows,
            @JsonProperty("limit") OptionalLong limit)
    {
        checkState(partNumber >= 0, "partNumber must be >= 0");
        checkState(totalPartsPerWorker >= 1, "totalPartsPerWorker must be >= 1");
        checkState(totalPartsPerWorker > partNumber, "totalPartsPerWorker must be > partNumber");

        this.table = table;
        this.partNumber = partNumber;
        this.totalPartsPerWorker = totalPartsPerWorker;
        this.address = requireNonNull(address, "address is null");
        this.expectedRows = expectedRows;
        this.limit = limit;
    }

    @JsonProperty
    public long getTable()
    {
        return table;
    }

    @JsonProperty
    public int getTotalPartsPerWorker()
    {
        return totalPartsPerWorker;
    }

    @JsonProperty
    public int getPartNumber()
    {
        return partNumber;
    }

    @Override
    public Object getInfo()
    {
        return this;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + address.getRetainedSizeInBytes()
                + sizeOf(limit);
    }

    @Override
    public boolean isRemotelyAccessible()
    {
        return false;
    }

    @JsonProperty
    public HostAddress getAddress()
    {
        return address;
    }

    @Override
    public List<HostAddress> getAddresses()
    {
        return ImmutableList.of(address);
    }

    @JsonProperty
    public long getExpectedRows()
    {
        return expectedRows;
    }

    @JsonProperty
    public OptionalLong getLimit()
    {
        return limit;
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
        MemorySplit other = (MemorySplit) obj;
        return Objects.equals(this.table, other.table) &&
                Objects.equals(this.totalPartsPerWorker, other.totalPartsPerWorker) &&
                Objects.equals(this.partNumber, other.partNumber);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(table, totalPartsPerWorker, partNumber);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("tableHandle", table)
                .add("partNumber", partNumber)
                .add("totalPartsPerWorker", totalPartsPerWorker)
                .toString();
    }
}
