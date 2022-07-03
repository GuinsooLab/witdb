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
package io.trino.plugin.thrift.api.datatypes;

import io.airlift.drift.annotations.ThriftConstructor;
import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftStruct;
import io.trino.plugin.thrift.api.TrinoThriftBlock;
import io.trino.spi.block.Block;
import io.trino.spi.block.IntArrayBlock;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.Type;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.drift.annotations.ThriftField.Requiredness.OPTIONAL;
import static io.trino.plugin.thrift.api.TrinoThriftBlock.integerData;
import static io.trino.plugin.thrift.api.datatypes.TrinoThriftTypeUtils.fromIntBasedBlock;
import static io.trino.plugin.thrift.api.datatypes.TrinoThriftTypeUtils.fromIntBasedColumn;
import static io.trino.spi.type.IntegerType.INTEGER;

/**
 * Elements of {@code nulls} array determine if a value for a corresponding row is null.
 * Elements of {@code ints} array are values for each row. If row is null then value is ignored.
 */
@ThriftStruct
public final class TrinoThriftInteger
        implements TrinoThriftColumnData
{
    private final boolean[] nulls;
    private final int[] ints;

    @ThriftConstructor
    public TrinoThriftInteger(
            @ThriftField(name = "nulls") @Nullable boolean[] nulls,
            @ThriftField(name = "ints") @Nullable int[] ints)
    {
        checkArgument(sameSizeIfPresent(nulls, ints), "nulls and values must be of the same size");
        this.nulls = nulls;
        this.ints = ints;
    }

    @Nullable
    @ThriftField(value = 1, requiredness = OPTIONAL)
    public boolean[] getNulls()
    {
        return nulls;
    }

    @Nullable
    @ThriftField(value = 2, requiredness = OPTIONAL)
    public int[] getInts()
    {
        return ints;
    }

    @Override
    public Block toBlock(Type desiredType)
    {
        checkArgument(INTEGER.equals(desiredType), "type doesn't match: %s", desiredType);
        int numberOfRecords = numberOfRecords();
        return new IntArrayBlock(
                numberOfRecords,
                Optional.ofNullable(nulls),
                ints == null ? new int[numberOfRecords] : ints);
    }

    @Override
    public int numberOfRecords()
    {
        if (nulls != null) {
            return nulls.length;
        }
        if (ints != null) {
            return ints.length;
        }
        return 0;
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
        TrinoThriftInteger other = (TrinoThriftInteger) obj;
        return Arrays.equals(this.nulls, other.nulls) &&
                Arrays.equals(this.ints, other.ints);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(Arrays.hashCode(nulls), Arrays.hashCode(ints));
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("numberOfRecords", numberOfRecords())
                .toString();
    }

    public static TrinoThriftBlock fromBlock(Block block)
    {
        return fromIntBasedBlock(block, INTEGER, (nulls, ints) -> integerData(new TrinoThriftInteger(nulls, ints)));
    }

    public static TrinoThriftBlock fromRecordSetColumn(RecordSet recordSet, int columnIndex, int totalRecords)
    {
        return fromIntBasedColumn(recordSet, columnIndex, totalRecords, (nulls, ints) -> integerData(new TrinoThriftInteger(nulls, ints)));
    }

    private static boolean sameSizeIfPresent(boolean[] nulls, int[] ints)
    {
        return nulls == null || ints == null || nulls.length == ints.length;
    }
}
