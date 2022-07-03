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

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.trino.spi.Page;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import io.trino.sql.gen.JoinCompiler;
import io.trino.type.BlockTypeOperators;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.RowPageBuilder.rowPageBuilder;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.operator.PageAssertions.assertPageEquals;
import static io.trino.operator.UpdateMemory.NOOP;
import static io.trino.spi.connector.SortOrder.ASC_NULLS_LAST;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestGroupedTopNRankBuilder
{
    @DataProvider
    public static Object[][] produceRanking()
    {
        return new Object[][] {{true}, {false}};
    }

    @Test
    public void testEmptyInput()
    {
        GroupedTopNBuilder groupedTopNBuilder = new GroupedTopNRankBuilder(
                ImmutableList.of(BIGINT),
                (left, leftPosition, right, rightPosition) -> {
                    throw new UnsupportedOperationException();
                },
                new PageWithPositionEqualsAndHash()
                {
                    @Override
                    public boolean equals(Page left, int leftPosition, Page right, int rightPosition)
                    {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long hashCode(Page page, int position)
                    {
                        throw new UnsupportedOperationException();
                    }
                },
                5,
                false,
                new NoChannelGroupByHash());
        assertFalse(groupedTopNBuilder.buildResult().hasNext());
    }

    @Test(dataProvider = "produceRanking")
    public void testSingleGroupTopN(boolean produceRanking)
    {
        TypeOperators typeOperators = new TypeOperators();
        BlockTypeOperators blockTypeOperators = new BlockTypeOperators(typeOperators);
        List<Type> types = ImmutableList.of(DOUBLE);

        GroupedTopNBuilder groupedTopNBuilder = new GroupedTopNRankBuilder(
                types,
                new SimplePageWithPositionComparator(types, ImmutableList.of(0), ImmutableList.of(ASC_NULLS_LAST), typeOperators),
                new SimplePageWithPositionEqualsAndHash(types, ImmutableList.of(0), blockTypeOperators),
                3,
                produceRanking,
                new NoChannelGroupByHash());

        // Expected effect: [0.2 x 1 => rank=1, 0.3 x 2 => rank=2]
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0.3)
                        .row(0.3)
                        .row(0.2)
                        .build()).process());

        // Page should be dropped, because single value 0.4 is too large to be considered
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0.4)
                        .build()).process());

        // Next page should cause 0.3 values to be evicted (first page will be compacted)
        // Expected effect: [0.1 x 2 => rank 1, 0.2 x 3 => rank 3]
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0.1)
                        .row(0.2)
                        .row(0.3)
                        .row(0.2)
                        .row(0.1)
                        .build()).process());

        List<Page> output = ImmutableList.copyOf(groupedTopNBuilder.buildResult());
        assertEquals(output.size(), 1);

        List<Type> outputTypes = ImmutableList.of(DOUBLE, BIGINT);
        Page expected = rowPageBuilder(outputTypes)
                .row(0.1, 1)
                .row(0.1, 1)
                .row(0.2, 3)
                .row(0.2, 3)
                .row(0.2, 3)
                .build();
        if (!produceRanking) {
            outputTypes = outputTypes.subList(0, outputTypes.size() - 1);
            expected = dropLastColumn(expected);
        }
        assertPageEquals(outputTypes, getOnlyElement(output), expected);
    }

    @Test(dataProvider = "produceRanking")
    public void testMultiGroupTopN(boolean produceRanking)
    {
        TypeOperators typeOperators = new TypeOperators();
        BlockTypeOperators blockTypeOperators = new BlockTypeOperators(typeOperators);
        List<Type> types = ImmutableList.of(BIGINT, DOUBLE);

        GroupByHash groupByHash = createGroupByHash(ImmutableList.of(types.get(0)), ImmutableList.of(0), NOOP, typeOperators, blockTypeOperators);
        GroupedTopNBuilder groupedTopNBuilder = new GroupedTopNRankBuilder(
                types,
                new SimplePageWithPositionComparator(types, ImmutableList.of(1), ImmutableList.of(ASC_NULLS_LAST), typeOperators),
                new SimplePageWithPositionEqualsAndHash(types, ImmutableList.of(1), blockTypeOperators),
                3,
                produceRanking,
                groupByHash);

        // Expected effect:
        // Group 0 [0.2 x 1 => rank=1, 0.3 x 3 => rank=2]
        // Group 1 [0.2 x 1 => rank=1]
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0L, 0.3)
                        .row(0L, 0.3)
                        .row(0L, 0.3)
                        .row(0L, 0.2)
                        .row(1L, 0.2)
                        .build()).process());

        // Page should be dropped, because all values too large to be considered
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0L, 0.4)
                        .row(1L, 0.4)
                        .build()).process());

        // Next page should cause evict 0.3 from group 0, which should cause the first page to be compacted
        // Expected effect:
        // Group 0 [0.1 x 1 => rank=1, 0.2 x 2 => rank=2]
        // Group 1 [0.2 x 2 => rank=1, 0.3 x 2 => rank=3]
        assertTrue(groupedTopNBuilder.processPage(
                rowPageBuilder(types)
                        .row(0L, 0.1)
                        .row(1L, 0.2)
                        .row(0L, 0.3)
                        .row(0L, 0.2)
                        .row(1L, 0.5)
                        .row(1L, 0.4)
                        .row(1L, 0.3)
                        .row(1L, 0.3)
                        .build()).process());

        List<Page> output = ImmutableList.copyOf(groupedTopNBuilder.buildResult());
        assertEquals(output.size(), 1);

        List<Type> outputTypes = ImmutableList.of(BIGINT, DOUBLE, BIGINT);
        Page expected = rowPageBuilder(outputTypes)
                .row(0, 0.1, 1)
                .row(0, 0.2, 2)
                .row(0, 0.2, 2)
                .row(1, 0.2, 1)
                .row(1, 0.2, 1)
                .row(1, 0.3, 3)
                .row(1, 0.3, 3)
                .build();

        if (!produceRanking) {
            outputTypes = outputTypes.subList(0, outputTypes.size() - 1);
            expected = dropLastColumn(expected);
        }
        assertPageEquals(outputTypes, getOnlyElement(output), expected);
    }

    @Test
    public void testYield()
    {
        TypeOperators typeOperators = new TypeOperators();
        BlockTypeOperators blockTypeOperators = new BlockTypeOperators(typeOperators);
        List<Type> types = ImmutableList.of(BIGINT, DOUBLE);
        Page input = rowPagesBuilder(types)
                .row(1L, 0.3)
                .row(1L, 0.2)
                .row(1L, 0.9)
                .row(1L, 0.1)
                .build()
                .get(0);
        input.compact();

        AtomicBoolean unblock = new AtomicBoolean();
        GroupByHash groupByHash = createGroupByHash(ImmutableList.of(types.get(0)), ImmutableList.of(0), unblock::get, typeOperators, blockTypeOperators);
        GroupedTopNBuilder groupedTopNBuilder = new GroupedTopNRankBuilder(
                types,
                new SimplePageWithPositionComparator(types, ImmutableList.of(1), ImmutableList.of(ASC_NULLS_LAST), typeOperators),
                new SimplePageWithPositionEqualsAndHash(types, ImmutableList.of(1), blockTypeOperators),
                5,
                false,
                groupByHash);

        Work<?> work = groupedTopNBuilder.processPage(input);
        assertFalse(work.process());
        assertFalse(work.process());
        unblock.set(true);
        assertTrue(work.process());
        List<Page> output = ImmutableList.copyOf(groupedTopNBuilder.buildResult());
        assertEquals(output.size(), 1);

        Page expected = rowPagesBuilder(types)
                .row(1L, 0.1)
                .row(1L, 0.2)
                .row(1L, 0.3)
                .row(1L, 0.9)
                .build()
                .get(0);
        assertPageEquals(types, output.get(0), expected);
    }

    private GroupByHash createGroupByHash(List<Type> partitionTypes, List<Integer> partitionChannels, UpdateMemory updateMemory, TypeOperators typeOperators, BlockTypeOperators blockTypeOperators)
    {
        return GroupByHash.createGroupByHash(
                partitionTypes,
                Ints.toArray(partitionChannels),
                Optional.empty(),
                1,
                false,
                new JoinCompiler(typeOperators),
                blockTypeOperators,
                updateMemory);
    }

    private static Page dropLastColumn(Page page)
    {
        checkArgument(page.getChannelCount() > 0);
        return page.getColumns(IntStream.range(0, page.getChannelCount() - 1).toArray());
    }
}
