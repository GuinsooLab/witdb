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
import io.trino.spi.block.AbstractArrayBlock;
import io.trino.spi.block.ArrayBlock;
import io.trino.spi.block.Block;
import io.trino.spi.block.LongArrayBlock;
import io.trino.spi.type.Type;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.drift.annotations.ThriftField.Requiredness.OPTIONAL;
import static io.trino.plugin.thrift.api.TrinoThriftBlock.bigintArrayData;
import static io.trino.plugin.thrift.api.datatypes.TrinoThriftTypeUtils.calculateOffsets;
import static io.trino.plugin.thrift.api.datatypes.TrinoThriftTypeUtils.sameSizeIfPresent;
import static io.trino.plugin.thrift.api.datatypes.TrinoThriftTypeUtils.totalSize;
import static io.trino.spi.type.BigintType.BIGINT;

/**
 * Elements of {@code nulls} array determine if a value for a corresponding row is null.
 * Each elements of {@code sizes} array contains the number of elements in the corresponding values array.
 * If row is null then the corresponding element in {@code sizes} is ignored.
 * {@code values} is a bigint block containing array elements one after another for all rows.
 * The total number of elements in bigint block must be equal to the sum of all sizes.
 */
@ThriftStruct
public final class TrinoThriftBigintArray
        implements TrinoThriftColumnData
{
    private final boolean[] nulls;
    private final int[] sizes;
    private final TrinoThriftBigint values;

    @ThriftConstructor
    public TrinoThriftBigintArray(
            @ThriftField(name = "nulls") @Nullable boolean[] nulls,
            @ThriftField(name = "sizes") @Nullable int[] sizes,
            @ThriftField(name = "values") @Nullable TrinoThriftBigint values)
    {
        checkArgument(sameSizeIfPresent(nulls, sizes), "nulls and values must be of the same size");
        checkArgument(totalSize(nulls, sizes) == numberOfValues(values), "total number of values doesn't match expected size");
        this.nulls = nulls;
        this.sizes = sizes;
        this.values = values;
    }

    @Nullable
    @ThriftField(value = 1, requiredness = OPTIONAL)
    public boolean[] getNulls()
    {
        return nulls;
    }

    @Nullable
    @ThriftField(value = 2, requiredness = OPTIONAL)
    public int[] getSizes()
    {
        return sizes;
    }

    @Nullable
    @ThriftField(value = 3, requiredness = OPTIONAL)
    public TrinoThriftBigint getValues()
    {
        return values;
    }

    @Override
    public Block toBlock(Type desiredType)
    {
        checkArgument(desiredType.getTypeParameters().size() == 1 && BIGINT.equals(desiredType.getTypeParameters().get(0)),
                "type doesn't match: %s", desiredType);
        int numberOfRecords = numberOfRecords();
        return ArrayBlock.fromElementBlock(
                numberOfRecords,
                Optional.of(nulls == null ? new boolean[numberOfRecords] : nulls),
                calculateOffsets(sizes, nulls, numberOfRecords),
                values != null ? values.toBlock(BIGINT) : new LongArrayBlock(0, Optional.empty(), new long[] {}));
    }

    @Override
    public int numberOfRecords()
    {
        if (nulls != null) {
            return nulls.length;
        }
        if (sizes != null) {
            return sizes.length;
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
        TrinoThriftBigintArray other = (TrinoThriftBigintArray) obj;
        return Arrays.equals(this.nulls, other.nulls) &&
                Arrays.equals(this.sizes, other.sizes) &&
                Objects.equals(this.values, other.values);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(Arrays.hashCode(nulls), Arrays.hashCode(sizes), values);
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
        checkArgument(block instanceof AbstractArrayBlock, "block is not of an array type");
        AbstractArrayBlock arrayBlock = (AbstractArrayBlock) block;
        int positions = arrayBlock.getPositionCount();
        if (positions == 0) {
            return bigintArrayData(new TrinoThriftBigintArray(null, null, null));
        }
        boolean[] nulls = null;
        int[] sizes = null;
        for (int position = 0; position < positions; position++) {
            if (arrayBlock.isNull(position)) {
                if (nulls == null) {
                    nulls = new boolean[positions];
                }
                nulls[position] = true;
            }
            else {
                if (sizes == null) {
                    sizes = new int[positions];
                }
                sizes[position] = arrayBlock.apply((valuesBlock, startPosition, length) -> length, position);
            }
        }
        TrinoThriftBigint values = arrayBlock
                .apply((valuesBlock, startPosition, length) -> TrinoThriftBigint.fromBlock(valuesBlock), 0)
                .getBigintData();
        checkState(values != null, "values must be present");
        checkState(totalSize(nulls, sizes) == values.numberOfRecords(), "unexpected number of values");
        return bigintArrayData(new TrinoThriftBigintArray(nulls, sizes, values));
    }

    private static int numberOfValues(TrinoThriftBigint values)
    {
        return values != null ? values.numberOfRecords() : 0;
    }
}
