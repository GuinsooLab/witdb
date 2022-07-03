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

import com.google.common.collect.ImmutableList;
import org.testng.annotations.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestRowBlockBuilder
{
    @Test
    public void testBuilderProducesNullRleForNullRows()
    {
        // empty block
        assertIsNullRle(blockBuilder().build(), 0);

        // single null
        assertIsNullRle(blockBuilder().appendNull().build(), 1);

        // multiple nulls
        assertIsNullRle(blockBuilder().appendNull().appendNull().build(), 2);

        BlockBuilder blockBuilder = blockBuilder().appendNull().appendNull();
        assertIsNullRle(blockBuilder.copyPositions(new int[] {0}, 0, 1), 1);
        assertIsNullRle(blockBuilder.getRegion(0, 1), 1);
        assertIsNullRle(blockBuilder.copyRegion(0, 1), 1);
    }

    private static BlockBuilder blockBuilder()
    {
        return new RowBlockBuilder(ImmutableList.of(BIGINT), null, 10);
    }

    private void assertIsNullRle(Block block, int expectedPositionCount)
    {
        assertEquals(block.getPositionCount(), expectedPositionCount);
        assertEquals(block.getClass(), RunLengthEncodedBlock.class);
        if (expectedPositionCount > 0) {
            assertTrue(block.isNull(0));
        }
    }
}
