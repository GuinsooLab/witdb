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

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.ObjLongConsumer;

import static io.trino.spi.block.BlockUtil.checkArrayRange;

public interface Block
{
    /**
     * Gets the length of the value at the {@code position}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default int getSliceLength(int position)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets a byte at {@code offset} in the value at {@code position}.
     */
    default byte getByte(int position, int offset)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian short at {@code offset} in the value at {@code position}.
     */
    default short getShort(int position, int offset)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian int at {@code offset} in the value at {@code position}.
     */
    default int getInt(int position, int offset)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian long at {@code offset} in the value at {@code position}.
     */
    default long getLong(int position, int offset)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a slice at {@code offset} in the value at {@code position}.
     */
    default Slice getSlice(int position, int offset, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets an object in the value at {@code position}.
     */
    default <T> T getObject(int position, Class<T> clazz)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Is the byte sequences at {@code offset} in the value at {@code position} equal
     * to the byte sequence at {@code otherOffset} in {@code otherSlice}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default boolean bytesEqual(int position, int offset, Slice otherSlice, int otherOffset, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Compares the byte sequences at {@code offset} in the value at {@code position}
     * to the byte sequence at {@code otherOffset} in {@code otherSlice}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default int bytesCompare(int position, int offset, int length, Slice otherSlice, int otherOffset, int otherLength)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Appends the byte sequences at {@code offset} in the value at {@code position}
     * to {@code blockBuilder}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default void writeBytesTo(int position, int offset, int length, BlockBuilder blockBuilder)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Is the byte sequences at {@code offset} in the value at {@code position} equal
     * to the byte sequence at {@code otherOffset} in the value at {@code otherPosition}
     * in {@code otherBlock}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default boolean equals(int position, int offset, Block otherBlock, int otherPosition, int otherOffset, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Calculates the hash code the byte sequences at {@code offset} in the
     * value at {@code position}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default long hash(int position, int offset, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Compares the byte sequences at {@code offset} in the value at {@code position}
     * to the byte sequence at {@code otherOffset} in the value at {@code otherPosition}
     * in {@code otherBlock}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default int compareTo(int leftPosition, int leftOffset, int leftLength, Block rightBlock, int rightPosition, int rightOffset, int rightLength)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets the value at the specified position as a single element block.  The method
     * must copy the data into a new block.
     * <p>
     * This method is useful for operators that hold on to a single value without
     * holding on to the entire block.
     *
     * @throws IllegalArgumentException if this position is not valid
     */
    Block getSingleValueBlock(int position);

    /**
     * Returns the number of positions in this block.
     */
    int getPositionCount();

    /**
     * Returns the size of this block as if it was compacted, ignoring any over-allocations
     * and any unloaded nested blocks.
     * For example, in dictionary blocks, this only counts each dictionary entry once,
     * rather than each time a value is referenced.
     */
    long getSizeInBytes();

    /**
     * Returns the size of the block contents, regardless of internal representation.
     * The same logical data values should always have the same size, no matter
     * what block type is used or how they are represented within a specific block.
     * <p>
     * This can differ substantially from {@link #getSizeInBytes} for certain block
     * types. For RLE, it will be {@code N} times larger. For dictionary, it will be
     * larger based on how many times dictionary entries are reused.
     */
    default long getLogicalSizeInBytes()
    {
        return getSizeInBytes();
    }

    /**
     * Returns the size of {@code block.getRegion(position, length)}.
     * The method can be expensive. Do not use it outside an implementation of Block.
     */
    long getRegionSizeInBytes(int position, int length);

    /**
     * Returns the number of bytes (in terms of {@link Block#getSizeInBytes()}) required per position
     * that this block contains, assuming that the number of bytes required is a known static quantity
     * and not dependent on any particular specific position. This allows for some complex block wrappings
     * to potentially avoid having to call {@link Block#getPositionsSizeInBytes(boolean[], int)}  which
     * would require computing the specific positions selected
     *
     * @return The size in bytes, per position, if this block type does not require specific position information to compute its size
     */
    OptionalInt fixedSizeInBytesPerPosition();

    /**
     * Returns the size of all positions marked true in the positions array.
     * This is equivalent to multiple calls of {@code block.getRegionSizeInBytes(position, length)}
     * where you mark all positions for the regions first.
     * The 'selectedPositionsCount' variable may be used to skip iterating through
     * the positions array in case this is a fixed-width block
     */
    long getPositionsSizeInBytes(boolean[] positions, int selectedPositionsCount);

    /**
     * Returns the retained size of this block in memory, including over-allocations.
     * This method is called from the inner most execution loop and must be fast.
     */
    long getRetainedSizeInBytes();

    /**
     * Returns the estimated in memory data size for stats of position.
     * Do not use it for other purpose.
     */
    long getEstimatedDataSizeForStats(int position);

    /**
     * {@code consumer} visits each of the internal data container and accepts the size for it.
     * This method can be helpful in cases such as memory counting for internal data structure.
     * Also, the method should be non-recursive, only visit the elements at the top level,
     * and specifically should not call retainedBytesForEachPart on nested blocks
     * {@code consumer} should be called at least once with the current block and
     * must include the instance size of the current block
     */
    void retainedBytesForEachPart(ObjLongConsumer<Object> consumer);

    /**
     * Get the encoding for this block.
     */
    String getEncodingName();

    /**
     * Create a new block from the current block by keeping the same elements only with respect
     * to {@code positions} that starts at {@code offset} and has length of {@code length}. The
     * implementation may return a view over the data in this block or may return a copy, and the
     * implementation is allowed to retain the positions array for use in the view.
     */
    default Block getPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        return new DictionaryBlock(offset, length, this, positions);
    }

    /**
     * Returns a block containing the specified positions.
     * Positions to copy are stored in a subarray within {@code positions} array
     * that starts at {@code offset} and has length of {@code length}.
     * All specified positions must be valid for this block.
     * <p>
     * The returned block must be a compact representation of the original block.
     */
    Block copyPositions(int[] positions, int offset, int length);

    /**
     * Returns a block starting at the specified position and extends for the
     * specified length.  The specified region must be entirely contained
     * within this block.
     * <p>
     * The region can be a view over this block.  If this block is released
     * the region block may also be released.  If the region block is released
     * this block may also be released.
     */
    Block getRegion(int positionOffset, int length);

    /**
     * Returns a block starting at the specified position and extends for the
     * specified length.  The specified region must be entirely contained
     * within this block.
     * <p>
     * The region returned must be a compact representation of the original block, unless their internal
     * representation will be exactly the same. This method is useful for
     * operators that hold on to a range of values without holding on to the
     * entire block.
     */
    Block copyRegion(int position, int length);

    /**
     * Is it possible the block may have a null value?  If false, the block cannot contain
     * a null, but if true, the block may or may not have a null.
     */
    default boolean mayHaveNull()
    {
        return true;
    }

    /**
     * Is the specified position null?
     *
     * @throws IllegalArgumentException if this position is not valid. The method may return false
     * without throwing exception when there are no nulls in the block, even if the position is invalid
     */
    boolean isNull(int position);

    /**
     * Returns true if block data is fully loaded into memory.
     */
    default boolean isLoaded()
    {
        return true;
    }

    /**
     * Returns a fully loaded block that assures all data is in memory.
     * Neither the returned block nor any nested block will be a {@link LazyBlock}.
     * The same block will be returned if neither the current block nor any
     * nested blocks are {@link LazyBlock},
     * <p>
     * This allows streaming data sources to skip sections that are not
     * accessed in a query.
     */
    default Block getLoadedBlock()
    {
        return this;
    }

    /**
     * Gets the direct child blocks of this block.
     */
    default List<Block> getChildren()
    {
        return Collections.emptyList();
    }

    /**
     * Returns a block that contains a copy of the contents of the current block, and an appended null at the end. The
     * original block will not be modified. The purpose of this method is to leverage the contents of a block and the
     * structure of the implementation to efficiently produce a copy of the block with a NULL element inserted - so that
     * it can be used as a dictionary. This method is expected to be invoked on completely built {@link Block} instances
     * i.e. not on in-progress block builders.
     */
    Block copyWithAppendedNull();
}
