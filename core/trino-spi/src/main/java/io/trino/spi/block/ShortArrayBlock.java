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

public class ShortArrayBlock
        implements Block
{
    private static final int INSTANCE_SIZE = instanceSize(ShortArrayBlock.class);
    public static final int SIZE_IN_BYTES_PER_POSITION = Short.BYTES + Byte.BYTES;

    private final int arrayOffset;
    private final int positionCount;
    @Nullable
    private final boolean[] valueIsNull;
    private final short[] values;

    private final long retainedSizeInBytes;

    public ShortArrayBlock(int positionCount, Optional<boolean[]> valueIsNull, short[] values)
    {
        this(0, positionCount, valueIsNull.orElse(null), values);
    }

    ShortArrayBlock(int arrayOffset, int positionCount, boolean[] valueIsNull, short[] values)
    {
        if (arrayOffset < 0) {
            throw new IllegalArgumentException("arrayOffset is negative");
        }
        this.arrayOffset = arrayOffset;
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.length - arrayOffset < positionCount) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }
        this.values = values;

        if (valueIsNull != null && valueIsNull.length - arrayOffset < positionCount) {
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
        return isNull(position) ? 0 : Short.BYTES;
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
    public short getShort(int position, int offset)
    {
        checkReadablePosition(this, position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }
        return values[position + arrayOffset];
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
        return valueIsNull != null && valueIsNull[position + arrayOffset];
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(this, position);
        return new ShortArrayBlock(
                0,
                1,
                isNull(position) ? new boolean[] {true} : null,
                new short[] {values[position + arrayOffset]});
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        boolean[] newValueIsNull = null;
        if (valueIsNull != null) {
            newValueIsNull = new boolean[length];
        }
        short[] newValues = new short[length];
        for (int i = 0; i < length; i++) {
            int position = positions[offset + i];
            checkReadablePosition(this, position);
            if (valueIsNull != null) {
                newValueIsNull[i] = valueIsNull[position + arrayOffset];
            }
            newValues[i] = values[position + arrayOffset];
        }
        return new ShortArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        return new ShortArrayBlock(positionOffset + arrayOffset, length, valueIsNull, values);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        positionOffset += arrayOffset;
        boolean[] newValueIsNull = valueIsNull == null ? null : compactArray(valueIsNull, positionOffset, length);
        short[] newValues = compactArray(values, positionOffset, length);

        if (newValueIsNull == valueIsNull && newValues == values) {
            return this;
        }
        return new ShortArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public String getEncodingName()
    {
        return ShortArrayBlockEncoding.NAME;
    }

    @Override
    public Block copyWithAppendedNull()
    {
        boolean[] newValueIsNull = copyIsNullAndAppendNull(valueIsNull, arrayOffset, positionCount);
        short[] newValues = ensureCapacity(values, arrayOffset + positionCount + 1);
        return new ShortArrayBlock(arrayOffset, positionCount + 1, newValueIsNull, newValues);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ShortArrayBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    Slice getValuesSlice()
    {
        return Slices.wrappedShortArray(values, arrayOffset, positionCount);
    }
}
