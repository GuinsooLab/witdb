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

package io.trino.block;

import com.google.common.collect.ImmutableList;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.ByteArrayBlock;
import io.trino.spi.block.RowBlockBuilder;
import io.trino.spi.block.SingleRowBlock;
import io.trino.spi.type.Type;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.spi.block.RowBlock.fromFieldBlocks;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestRowBlock
        extends AbstractTestBlock
{
    @Test
    public void testWithVarcharBigint()
    {
        List<Type> fieldTypes = ImmutableList.of(VARCHAR, BIGINT);
        List<Object>[] testRows = generateTestRows(fieldTypes, 100);

        testWith(fieldTypes, testRows);
        testWith(fieldTypes, alternatingNullValues(testRows));
    }

    @Test
    public void testEstimatedDataSizeForStats()
    {
        List<Type> fieldTypes = ImmutableList.of(VARCHAR, BIGINT);
        List<Object>[] expectedValues = alternatingNullValues(generateTestRows(fieldTypes, 100));
        BlockBuilder blockBuilder = createBlockBuilderWithValues(fieldTypes, expectedValues);
        Block block = blockBuilder.build();
        assertEquals(block.getPositionCount(), expectedValues.length);
        for (int i = 0; i < block.getPositionCount(); i++) {
            int expectedSize = getExpectedEstimatedDataSize(expectedValues[i]);
            assertEquals(blockBuilder.getEstimatedDataSizeForStats(i), expectedSize);
            assertEquals(block.getEstimatedDataSizeForStats(i), expectedSize);
        }
    }

    @Test
    public void testFromFieldBlocksNoNullsDetection()
    {
        Block emptyBlock = new ByteArrayBlock(0, Optional.empty(), new byte[0]);
        Block fieldBlock = new ByteArrayBlock(5, Optional.empty(), createExpectedValue(5).getBytes());

        boolean[] rowIsNull = new boolean[fieldBlock.getPositionCount()];
        Arrays.fill(rowIsNull, false);

        // Blocks may discard the null mask during creation if no values are null
        assertFalse(fromFieldBlocks(5, Optional.of(rowIsNull), new Block[]{fieldBlock}).mayHaveNull());
        // Last position is null must retain the nulls mask
        rowIsNull[rowIsNull.length - 1] = true;
        assertTrue(fromFieldBlocks(5, Optional.of(rowIsNull), new Block[]{fieldBlock}).mayHaveNull());
        // Empty blocks have no nulls and can also discard their null mask
        assertFalse(fromFieldBlocks(0, Optional.of(new boolean[0]), new Block[]{emptyBlock}).mayHaveNull());

        // Normal blocks should have null masks preserved
        List<Type> fieldTypes = ImmutableList.of(VARCHAR, BIGINT);
        Block hasNullsBlock = createBlockBuilderWithValues(fieldTypes, alternatingNullValues(generateTestRows(fieldTypes, 100))).build();
        assertTrue(hasNullsBlock.mayHaveNull());
    }

    private int getExpectedEstimatedDataSize(List<Object> row)
    {
        if (row == null) {
            return 0;
        }
        int size = 0;
        size += row.get(0) == null ? 0 : ((String) row.get(0)).length();
        size += row.get(1) == null ? 0 : Long.BYTES;
        return size;
    }

    @Test
    public void testCompactBlock()
    {
        Block emptyBlock = new ByteArrayBlock(0, Optional.empty(), new byte[0]);
        Block compactFieldBlock1 = new ByteArrayBlock(5, Optional.empty(), createExpectedValue(5).getBytes());
        Block compactFieldBlock2 = new ByteArrayBlock(5, Optional.empty(), createExpectedValue(5).getBytes());
        Block incompactFieldBlock1 = new ByteArrayBlock(5, Optional.empty(), createExpectedValue(6).getBytes());
        Block incompactFieldBlock2 = new ByteArrayBlock(5, Optional.empty(), createExpectedValue(6).getBytes());
        boolean[] rowIsNull = {false, true, false, false, false, false};

        assertCompact(fromFieldBlocks(0, Optional.empty(), new Block[] {emptyBlock, emptyBlock}));
        assertCompact(fromFieldBlocks(rowIsNull.length, Optional.of(rowIsNull), new Block[] {compactFieldBlock1, compactFieldBlock2}));
        // TODO: add test case for a sliced RowBlock

        // underlying field blocks are not compact
        testIncompactBlock(fromFieldBlocks(rowIsNull.length, Optional.of(rowIsNull), new Block[] {incompactFieldBlock1, incompactFieldBlock2}));
        testIncompactBlock(fromFieldBlocks(rowIsNull.length, Optional.of(rowIsNull), new Block[] {incompactFieldBlock1, incompactFieldBlock2}));
    }

    private void testWith(List<Type> fieldTypes, List<Object>[] expectedValues)
    {
        BlockBuilder blockBuilder = createBlockBuilderWithValues(fieldTypes, expectedValues);

        assertBlock(blockBuilder, expectedValues);
        assertBlock(blockBuilder.build(), expectedValues);

        IntArrayList positionList = generatePositionList(expectedValues.length, expectedValues.length / 2);
        assertBlockFilteredPositions(expectedValues, blockBuilder, positionList.toIntArray());
        assertBlockFilteredPositions(expectedValues, blockBuilder.build(), positionList.toIntArray());
    }

    private BlockBuilder createBlockBuilderWithValues(List<Type> fieldTypes, List<Object>[] rows)
    {
        BlockBuilder rowBlockBuilder = new RowBlockBuilder(fieldTypes, null, 1);
        for (List<Object> row : rows) {
            if (row == null) {
                rowBlockBuilder.appendNull();
            }
            else {
                BlockBuilder singleRowBlockWriter = rowBlockBuilder.beginBlockEntry();
                for (Object fieldValue : row) {
                    if (fieldValue == null) {
                        singleRowBlockWriter.appendNull();
                    }
                    else {
                        if (fieldValue instanceof Long) {
                            BIGINT.writeLong(singleRowBlockWriter, ((Long) fieldValue).longValue());
                        }
                        else if (fieldValue instanceof String) {
                            VARCHAR.writeSlice(singleRowBlockWriter, utf8Slice((String) fieldValue));
                        }
                        else {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                rowBlockBuilder.closeEntry();
            }
        }

        return rowBlockBuilder;
    }

    @Override
    protected <T> void assertPositionValue(Block block, int position, T expectedValue)
    {
        if (expectedValue instanceof List) {
            assertValue(block, position, (List<?>) expectedValue);
            return;
        }
        super.assertPositionValue(block, position, expectedValue);
    }

    private void assertValue(Block rowBlock, int position, List<?> row)
    {
        // null rows are handled by assertPositionValue
        requireNonNull(row, "row is null");

        assertFalse(rowBlock.isNull(position));
        SingleRowBlock singleRowBlock = (SingleRowBlock) rowBlock.getObject(position, Block.class);
        assertEquals(singleRowBlock.getPositionCount(), row.size());

        for (int i = 0; i < row.size(); i++) {
            Object fieldValue = row.get(i);
            if (fieldValue == null) {
                assertTrue(singleRowBlock.isNull(i));
            }
            else {
                if (fieldValue instanceof Long) {
                    assertEquals(BIGINT.getLong(singleRowBlock, i), ((Long) fieldValue).longValue());
                }
                else if (fieldValue instanceof String) {
                    assertEquals(VARCHAR.getSlice(singleRowBlock, i), utf8Slice((String) fieldValue));
                }
                else {
                    throw new IllegalArgumentException();
                }
            }
        }
    }

    private List<Object>[] generateTestRows(List<Type> fieldTypes, int numRows)
    {
        @SuppressWarnings("unchecked")
        List<Object>[] testRows = new List[numRows];
        for (int i = 0; i < numRows; i++) {
            List<Object> testRow = new ArrayList<>(fieldTypes.size());
            for (int j = 0; j < fieldTypes.size(); j++) {
                int cellId = i * fieldTypes.size() + j;
                if (cellId % 7 == 3) {
                    // Put null value for every 7 cells
                    testRow.add(null);
                }
                else {
                    if (fieldTypes.get(j) == BIGINT) {
                        testRow.add(i * 100L + j);
                    }
                    else if (fieldTypes.get(j) == VARCHAR) {
                        testRow.add(format("field(%s, %s)", i, j));
                    }
                    else {
                        throw new IllegalArgumentException();
                    }
                }
            }
            testRows[i] = testRow;
        }
        return testRows;
    }

    private IntArrayList generatePositionList(int numRows, int numPositions)
    {
        IntArrayList positions = new IntArrayList(numPositions);
        for (int i = 0; i < numPositions; i++) {
            positions.add((7 * i + 3) % numRows);
        }
        Collections.sort(positions);
        return positions;
    }
}
