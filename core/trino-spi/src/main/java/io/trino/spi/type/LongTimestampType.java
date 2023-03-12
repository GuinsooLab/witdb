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
package io.trino.spi.type;

import io.airlift.slice.XxHash64;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.BlockBuilderStatus;
import io.trino.spi.block.Int96ArrayBlockBuilder;
import io.trino.spi.block.PageBuilderStatus;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.BlockIndex;
import io.trino.spi.function.BlockPosition;
import io.trino.spi.function.ScalarOperator;

import java.util.Optional;

import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.trino.spi.function.OperatorType.COMPARISON_UNORDERED_LAST;
import static io.trino.spi.function.OperatorType.EQUAL;
import static io.trino.spi.function.OperatorType.LESS_THAN;
import static io.trino.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.trino.spi.function.OperatorType.XX_HASH_64;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.Timestamps.rescale;
import static io.trino.spi.type.TypeOperatorDeclaration.extractOperatorDeclaration;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;

/**
 * The representation is a 96-bit value that contains the microseconds from the epoch
 * in the first long and the fractional increment in the remaining integer, as
 * a number of picoseconds additional to the epoch microsecond.
 */
class LongTimestampType
        extends TimestampType
{
    private static final TypeOperatorDeclaration TYPE_OPERATOR_DECLARATION = extractOperatorDeclaration(LongTimestampType.class, lookup(), LongTimestamp.class);
    private final Range range;

    public LongTimestampType(int precision)
    {
        super(precision, LongTimestamp.class);

        if (precision < MAX_SHORT_PRECISION + 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException(format("Precision must be in the range [%s, %s]", MAX_SHORT_PRECISION + 1, MAX_PRECISION));
        }

        // ShortTimestampType instances are created eagerly and shared so it's OK to precompute some things.
        int picosOfMicroMax = toIntExact(PICOSECONDS_PER_MICROSECOND - rescale(1, 0, 12 - getPrecision()));
        range = new Range(new LongTimestamp(Long.MIN_VALUE, 0), new LongTimestamp(Long.MAX_VALUE, picosOfMicroMax));
    }

    @Override
    public TypeOperatorDeclaration getTypeOperatorDeclaration(TypeOperators typeOperators)
    {
        return TYPE_OPERATOR_DECLARATION;
    }

    @Override
    public int getFixedSize()
    {
        return Long.BYTES + Integer.BYTES;
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries, int expectedBytesPerEntry)
    {
        int maxBlockSizeInBytes;
        if (blockBuilderStatus == null) {
            maxBlockSizeInBytes = PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
        }
        else {
            maxBlockSizeInBytes = blockBuilderStatus.getMaxPageSizeInBytes();
        }
        return new Int96ArrayBlockBuilder(
                blockBuilderStatus,
                Math.min(expectedEntries, maxBlockSizeInBytes / getFixedSize()));
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        return createBlockBuilder(blockBuilderStatus, expectedEntries, getFixedSize());
    }

    @Override
    public BlockBuilder createFixedSizeBlockBuilder(int positionCount)
    {
        return new Int96ArrayBlockBuilder(null, positionCount);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            blockBuilder.writeLong(getEpochMicros(block, position));
            blockBuilder.writeInt(getFraction(block, position));
            blockBuilder.closeEntry();
        }
    }

    @Override
    public Object getObject(Block block, int position)
    {
        return new LongTimestamp(getEpochMicros(block, position), getFraction(block, position));
    }

    @Override
    public void writeObject(BlockBuilder blockBuilder, Object value)
    {
        LongTimestamp timestamp = (LongTimestamp) value;
        write(blockBuilder, timestamp.getEpochMicros(), timestamp.getPicosOfMicro());
    }

    public void write(BlockBuilder blockBuilder, long epochMicros, int fraction)
    {
        blockBuilder.writeLong(epochMicros);
        blockBuilder.writeInt(fraction);
        blockBuilder.closeEntry();
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        long epochMicros = getEpochMicros(block, position);
        int fraction = getFraction(block, position);

        return SqlTimestamp.newInstance(getPrecision(), epochMicros, fraction);
    }

    private static long getEpochMicros(Block block, int position)
    {
        return block.getLong(position, 0);
    }

    private static int getFraction(Block block, int position)
    {
        return block.getInt(position, SIZE_OF_LONG);
    }

    @Override
    public Optional<Range> getRange()
    {
        return Optional.of(range);
    }

    @ScalarOperator(EQUAL)
    private static boolean equalOperator(LongTimestamp left, LongTimestamp right)
    {
        return equal(
                left.getEpochMicros(),
                left.getPicosOfMicro(),
                right.getEpochMicros(),
                right.getPicosOfMicro());
    }

    @ScalarOperator(EQUAL)
    private static boolean equalOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return equal(
                getEpochMicros(leftBlock, leftPosition),
                getFraction(leftBlock, leftPosition),
                getEpochMicros(rightBlock, rightPosition),
                getFraction(rightBlock, rightPosition));
    }

    private static boolean equal(long leftEpochMicros, int leftFraction, long rightEpochMicros, int rightFraction)
    {
        return leftEpochMicros == rightEpochMicros && leftFraction == rightFraction;
    }

    @ScalarOperator(XX_HASH_64)
    private static long xxHash64Operator(LongTimestamp value)
    {
        return xxHash64(value.getEpochMicros(), value.getPicosOfMicro());
    }

    @ScalarOperator(XX_HASH_64)
    private static long xxHash64Operator(@BlockPosition Block block, @BlockIndex int position)
    {
        return xxHash64(
                getEpochMicros(block, position),
                getFraction(block, position));
    }

    private static long xxHash64(long epochMicros, int fraction)
    {
        return XxHash64.hash(epochMicros) ^ XxHash64.hash(fraction);
    }

    @ScalarOperator(COMPARISON_UNORDERED_LAST)
    private static long comparisonOperator(LongTimestamp left, LongTimestamp right)
    {
        return comparison(left.getEpochMicros(), left.getPicosOfMicro(), right.getEpochMicros(), right.getPicosOfMicro());
    }

    @ScalarOperator(COMPARISON_UNORDERED_LAST)
    private static long comparisonOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return comparison(
                getEpochMicros(leftBlock, leftPosition),
                getFraction(leftBlock, leftPosition),
                getEpochMicros(rightBlock, rightPosition),
                getFraction(rightBlock, rightPosition));
    }

    private static int comparison(long leftEpochMicros, int leftPicosOfMicro, long rightEpochMicros, int rightPicosOfMicro)
    {
        int value = Long.compare(leftEpochMicros, rightEpochMicros);
        if (value != 0) {
            return value;
        }
        return Integer.compare(leftPicosOfMicro, rightPicosOfMicro);
    }

    @ScalarOperator(LESS_THAN)
    private static boolean lessThanOperator(LongTimestamp left, LongTimestamp right)
    {
        return lessThan(left.getEpochMicros(), left.getPicosOfMicro(), right.getEpochMicros(), right.getPicosOfMicro());
    }

    @ScalarOperator(LESS_THAN)
    private static boolean lessThanOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return lessThan(
                getEpochMicros(leftBlock, leftPosition),
                getFraction(leftBlock, leftPosition),
                getEpochMicros(rightBlock, rightPosition),
                getFraction(rightBlock, rightPosition));
    }

    private static boolean lessThan(long leftEpochMicros, int leftPicosOfMicro, long rightEpochMicros, int rightPicosOfMicro)
    {
        return (leftEpochMicros < rightEpochMicros) ||
                ((leftEpochMicros == rightEpochMicros) && (leftPicosOfMicro < rightPicosOfMicro));
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    private static boolean lessThanOrEqualOperator(LongTimestamp left, LongTimestamp right)
    {
        return lessThanOrEqual(left.getEpochMicros(), left.getPicosOfMicro(), right.getEpochMicros(), right.getPicosOfMicro());
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    private static boolean lessThanOrEqualOperator(@BlockPosition Block leftBlock, @BlockIndex int leftPosition, @BlockPosition Block rightBlock, @BlockIndex int rightPosition)
    {
        return lessThanOrEqual(
                getEpochMicros(leftBlock, leftPosition),
                getFraction(leftBlock, leftPosition),
                getEpochMicros(rightBlock, rightPosition),
                getFraction(rightBlock, rightPosition));
    }

    private static boolean lessThanOrEqual(long leftEpochMicros, int leftPicosOfMicro, long rightEpochMicros, int rightPicosOfMicro)
    {
        return (leftEpochMicros < rightEpochMicros) ||
                ((leftEpochMicros == rightEpochMicros) && (leftPicosOfMicro <= rightPicosOfMicro));
    }
}
