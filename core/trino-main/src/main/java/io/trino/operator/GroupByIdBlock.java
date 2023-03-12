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

import io.airlift.slice.Slice;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.ObjLongConsumer;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.trino.spi.type.BigintType.BIGINT;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

public class GroupByIdBlock
        implements Block
{
    private static final int INSTANCE_SIZE = instanceSize(GroupByIdBlock.class);

    private final long groupCount;
    private final Block block;

    public GroupByIdBlock(long groupCount, Block block)
    {
        requireNonNull(block, "block is null");
        this.groupCount = groupCount;
        this.block = block;
    }

    public long getGroupCount()
    {
        return groupCount;
    }

    public long getGroupId(int position)
    {
        return BIGINT.getLong(block, position);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        return block.getRegion(positionOffset, length);
    }

    @Override
    public long getRegionSizeInBytes(int positionOffset, int length)
    {
        return block.getRegionSizeInBytes(positionOffset, length);
    }

    @Override
    public OptionalInt fixedSizeInBytesPerPosition()
    {
        return block.fixedSizeInBytesPerPosition();
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions, int selectedPositionCount)
    {
        return block.getPositionsSizeInBytes(positions, selectedPositionCount);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        return block.copyRegion(positionOffset, length);
    }

    @Override
    public int getSliceLength(int position)
    {
        return block.getSliceLength(position);
    }

    @Override
    public byte getByte(int position, int offset)
    {
        return block.getByte(position, offset);
    }

    @Override
    public short getShort(int position, int offset)
    {
        return block.getShort(position, offset);
    }

    @Override
    public int getInt(int position, int offset)
    {
        return block.getInt(position, offset);
    }

    @Override
    public long getLong(int position, int offset)
    {
        return block.getLong(position, offset);
    }

    @Override
    public Slice getSlice(int position, int offset, int length)
    {
        return block.getSlice(position, offset, length);
    }

    @Override
    public <T> T getObject(int position, Class<T> clazz)
    {
        return block.getObject(position, clazz);
    }

    @Override
    public boolean bytesEqual(int position, int offset, Slice otherSlice, int otherOffset, int length)
    {
        return block.bytesEqual(position, offset, otherSlice, otherOffset, length);
    }

    @Override
    public int bytesCompare(int position, int offset, int length, Slice otherSlice, int otherOffset, int otherLength)
    {
        return block.bytesCompare(position, offset, length, otherSlice, otherOffset, otherLength);
    }

    @Override
    public void writeBytesTo(int position, int offset, int length, BlockBuilder blockBuilder)
    {
        block.writeBytesTo(position, offset, length, blockBuilder);
    }

    @Override
    public boolean equals(int position, int offset, Block otherBlock, int otherPosition, int otherOffset, int length)
    {
        return block.equals(position, offset, otherBlock, otherPosition, otherOffset, length);
    }

    @Override
    public long hash(int position, int offset, int length)
    {
        return block.hash(position, offset, length);
    }

    @Override
    public int compareTo(int leftPosition, int leftOffset, int leftLength, Block rightBlock, int rightPosition, int rightOffset, int rightLength)
    {
        return block.compareTo(leftPosition, leftOffset, leftLength, rightBlock, rightPosition, rightOffset, rightLength);
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        return block.getSingleValueBlock(position);
    }

    @Override
    public boolean mayHaveNull()
    {
        return block.mayHaveNull();
    }

    @Override
    public boolean isNull(int position)
    {
        return block.isNull(position);
    }

    @Override
    public int getPositionCount()
    {
        return block.getPositionCount();
    }

    @Override
    public long getSizeInBytes()
    {
        return block.getSizeInBytes();
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + block.getRetainedSizeInBytes();
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return block.getEstimatedDataSizeForStats(position);
    }

    @Override
    public void retainedBytesForEachPart(ObjLongConsumer<Object> consumer)
    {
        consumer.accept(block, block.getRetainedSizeInBytes());
        consumer.accept(this, INSTANCE_SIZE);
    }

    @Override
    public String getEncodingName()
    {
        throw new UnsupportedOperationException("GroupByIdBlock does not support serialization");
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        return block.copyPositions(positions, offset, length);
    }

    @Override
    public Block copyWithAppendedNull()
    {
        throw new UnsupportedOperationException("GroupByIdBlock does not support newBlockWithAppendedNull()");
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("groupCount", groupCount)
                .add("positionCount", getPositionCount())
                .toString();
    }

    @Override
    public boolean isLoaded()
    {
        return block.isLoaded();
    }

    @Override
    public Block getLoadedBlock()
    {
        return block.getLoadedBlock();
    }

    @Override
    public final List<Block> getChildren()
    {
        return singletonList(block);
    }
}
