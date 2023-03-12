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
package io.trino.spi.block;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ObjLongConsumer;

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.trino.spi.block.BlockUtil.checkArrayRange;
import static io.trino.spi.block.BlockUtil.checkReadablePosition;
import static io.trino.spi.block.BlockUtil.checkValidRegion;
import static io.trino.spi.block.BlockUtil.compactArray;
import static io.trino.spi.block.BlockUtil.copyIsNullAndAppendNull;
import static io.trino.spi.block.BlockUtil.ensureCapacity;

public class Int128ArrayBlock
        implements Block
{
    private static final int INSTANCE_SIZE = instanceSize(Int128ArrayBlock.class);
    public static final int INT128_BYTES = Long.BYTES + Long.BYTES;
    public static final int SIZE_IN_BYTES_PER_POSITION = INT128_BYTES + Byte.BYTES;

    private final int positionOffset;
    private final int positionCount;
    @Nullable
    private final boolean[] valueIsNull;
    private final long[] values;

    private final long retainedSizeInBytes;

    public Int128ArrayBlock(int positionCount, Optional<boolean[]> valueIsNull, long[] values)
    {
        this(0, positionCount, valueIsNull.orElse(null), values);
    }

    Int128ArrayBlock(int positionOffset, int positionCount, boolean[] valueIsNull, long[] values)
    {
        if (positionOffset < 0) {
            throw new IllegalArgumentException("positionOffset is negative");
        }
        this.positionOffset = positionOffset;
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.length - (positionOffset * 2) < positionCount * 2) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }
        this.values = values;

        if (valueIsNull != null && valueIsNull.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + sizeOf(values);
    }

    @Override
    public OptionalInt fixedSizeInBytesPerPosition()
    {
        return OptionalInt.of(SIZE_IN_BYTES_PER_POSITION);
    }

    @Override
    public long getSizeInBytes()
    {
        return SIZE_IN_BYTES_PER_POSITION * (long) positionCount;
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        return SIZE_IN_BYTES_PER_POSITION * (long) length;
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions, int selectedPositionsCount)
    {
        return (long) SIZE_IN_BYTES_PER_POSITION * selectedPositionsCount;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : INT128_BYTES;
    }

    @Override
    public void retainedBytesForEachPart(ObjLongConsumer<Object> consumer)
    {
        consumer.accept(values, sizeOf(values));
        if (valueIsNull != null) {
            consumer.accept(valueIsNull, sizeOf(valueIsNull));
        }
        consumer.accept(this, INSTANCE_SIZE);
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getLong(int position, int offset)
    {
        checkReadablePosition(this, position);
        if (offset == 0) {
            return values[(position + positionOffset) * 2];
        }
        if (offset == 8) {
            return values[((position + positionOffset) * 2) + 1];
        }
        throw new IllegalArgumentException("offset must be 0 or 8");
    }

    @Override
    public boolean mayHaveNull()
    {
        return valueIsNull != null;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(this, position);
        return valueIsNull != null && valueIsNull[position + positionOffset];
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(this, position);
        return new Int128ArrayBlock(
                0,
                1,
                isNull(position) ? new boolean[] {true} : null,
                new long[] {
                        values[(position + positionOffset) * 2],
                        values[((position + positionOffset) * 2) + 1]});
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        boolean[] newValueIsNull = null;
        if (valueIsNull != null) {
            newValueIsNull = new boolean[length];
        }
        long[] newValues = new long[length * 2];
        for (int i = 0; i < length; i++) {
            int position = positions[offset + i];
            checkReadablePosition(this, position);
            if (valueIsNull != null) {
                newValueIsNull[i] = valueIsNull[position + positionOffset];
            }
            newValues[i * 2] = values[(position + positionOffset) * 2];
            newValues[(i * 2) + 1] = values[((position + positionOffset) * 2) + 1];
        }
        return new Int128ArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        return new Int128ArrayBlock(positionOffset + this.positionOffset, length, valueIsNull, values);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        positionOffset += this.positionOffset;
        boolean[] newValueIsNull = valueIsNull == null ? null : compactArray(valueIsNull, positionOffset, length);
        long[] newValues = compactArray(values, positionOffset * 2, length * 2);

        if (newValueIsNull == valueIsNull && newValues == values) {
            return this;
        }
        return new Int128ArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public String getEncodingName()
    {
        return Int128ArrayBlockEncoding.NAME;
    }

    @Override
    public Block copyWithAppendedNull()
    {
        boolean[] newValueIsNull = copyIsNullAndAppendNull(valueIsNull, positionOffset, positionCount);
        long[] newValues = ensureCapacity(values, (positionOffset + positionCount + 1) * 2);
        return new Int128ArrayBlock(positionOffset, positionCount + 1, newValueIsNull, newValues);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("Int128ArrayBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    Slice getValuesSlice()
    {
        return Slices.wrappedLongArray(values, positionOffset * 2, positionCount * 2);
    }
}
