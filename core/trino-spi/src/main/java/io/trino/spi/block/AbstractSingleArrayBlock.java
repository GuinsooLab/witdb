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

import java.util.List;

import static io.trino.spi.block.BlockUtil.checkReadablePosition;
import static java.util.Collections.singletonList;

public abstract class AbstractSingleArrayBlock
        implements Block
{
    protected final int start;

    protected AbstractSingleArrayBlock(int start)
    {
        this.start = start;
    }

    @Override
    public final List<Block> getChildren()
    {
        return singletonList(getBlock());
    }

    protected abstract Block getBlock();

    @Override
    public int getSliceLength(int position)
    {
        checkReadablePosition(this, position);
        return getBlock().getSliceLength(position + start);
    }

    @Override
    public byte getByte(int position, int offset)
    {
        checkReadablePosition(this, position);
        return getBlock().getByte(position + start, offset);
    }

    @Override
    public short getShort(int position, int offset)
    {
        checkReadablePosition(this, position);
        return getBlock().getShort(position + start, offset);
    }

    @Override
    public int getInt(int position, int offset)
    {
        checkReadablePosition(this, position);
        return getBlock().getInt(position + start, offset);
    }

    @Override
    public long getLong(int position, int offset)
    {
        checkReadablePosition(this, position);
        return getBlock().getLong(position + start, offset);
    }

    @Override
    public Slice getSlice(int position, int offset, int length)
    {
        checkReadablePosition(this, position);
        return getBlock().getSlice(position + start, offset, length);
    }

    @Override
    public <T> T getObject(int position, Class<T> clazz)
    {
        checkReadablePosition(this, position);
        return getBlock().getObject(position + start, clazz);
    }

    @Override
    public boolean bytesEqual(int position, int offset, Slice otherSlice, int otherOffset, int length)
    {
        checkReadablePosition(this, position);
        return getBlock().bytesEqual(position + start, offset, otherSlice, otherOffset, length);
    }

    @Override
    public int bytesCompare(int position, int offset, int length, Slice otherSlice, int otherOffset, int otherLength)
    {
        checkReadablePosition(this, position);
        return getBlock().bytesCompare(position + start, offset, length, otherSlice, otherOffset, otherLength);
    }

    @Override
    public void writeBytesTo(int position, int offset, int length, BlockBuilder blockBuilder)
    {
        checkReadablePosition(this, position);
        getBlock().writeBytesTo(position + start, offset, length, blockBuilder);
    }

    @Override
    public boolean equals(int position, int offset, Block otherBlock, int otherPosition, int otherOffset, int length)
    {
        checkReadablePosition(this, position);
        return getBlock().equals(position + start, offset, otherBlock, otherPosition, otherOffset, length);
    }

    @Override
    public long hash(int position, int offset, int length)
    {
        checkReadablePosition(this, position);
        return getBlock().hash(position + start, offset, length);
    }

    @Override
    public int compareTo(int leftPosition, int leftOffset, int leftLength, Block rightBlock, int rightPosition, int rightOffset, int rightLength)
    {
        checkReadablePosition(this, leftPosition);
        return getBlock().compareTo(leftPosition + start, leftOffset, leftLength, rightBlock, rightPosition, rightOffset, rightLength);
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(this, position);
        return getBlock().getSingleValueBlock(position + start);
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        checkReadablePosition(this, position);
        return getBlock().getEstimatedDataSizeForStats(position + start);
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(this, position);
        return getBlock().isNull(position + start);
    }

    @Override
    public String getEncodingName()
    {
        // SingleArrayBlockEncoding does not exist
        throw new UnsupportedOperationException();
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Block getRegion(int position, int length)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions, int selectedPositionsCount)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Block copyRegion(int position, int length)
    {
        throw new UnsupportedOperationException();
    }
}
