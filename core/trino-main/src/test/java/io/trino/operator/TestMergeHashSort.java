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
import io.trino.spi.Page;
import io.trino.spi.type.TypeOperators;
import io.trino.type.BlockTypeOperators;
import org.testng.annotations.Test;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.operator.WorkProcessorAssertion.assertFinishes;
import static io.trino.spi.type.BigintType.BIGINT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestMergeHashSort
{
    private final BlockTypeOperators blockTypeOperators = new BlockTypeOperators(new TypeOperators());

    @Test
    public void testBinaryMergeIteratorOverEmptyPage()
    {
        Page emptyPage = new Page(0, BIGINT.createFixedSizeBlockBuilder(0).build());

        WorkProcessor<Page> mergedPage = new MergeHashSort(newSimpleAggregatedMemoryContext(), blockTypeOperators).merge(
                ImmutableList.of(BIGINT),
                ImmutableList.of(BIGINT),
                ImmutableList.of(ImmutableList.of(emptyPage).iterator()).stream()
                        .map(WorkProcessor::fromIterator)
                        .collect(toImmutableList()),
                new DriverYieldSignal());

        assertFinishes(mergedPage);
    }

    @Test
    public void testBinaryMergeIteratorOverEmptyPageAndNonEmptyPage()
    {
        Page emptyPage = new Page(0, BIGINT.createFixedSizeBlockBuilder(0).build());
        Page page = rowPagesBuilder(BIGINT).row(42).build().get(0);

        WorkProcessor<Page> mergedPage = new MergeHashSort(newSimpleAggregatedMemoryContext(), blockTypeOperators).merge(
                ImmutableList.of(BIGINT),
                ImmutableList.of(BIGINT),
                ImmutableList.of(ImmutableList.of(emptyPage, page).iterator()).stream()
                        .map(WorkProcessor::fromIterator)
                        .collect(toImmutableList()),
                new DriverYieldSignal());

        assertTrue(mergedPage.process());
        Page actualPage = mergedPage.getResult();
        assertEquals(actualPage.getPositionCount(), 1);
        assertEquals(actualPage.getChannelCount(), 1);
        assertEquals(actualPage.getBlock(0).getLong(0, 0), 42);

        assertFinishes(mergedPage);
    }

    @Test
    public void testBinaryMergeIteratorOverPageWith()
    {
        Page emptyPage = new Page(0, BIGINT.createFixedSizeBlockBuilder(0).build());
        Page page = rowPagesBuilder(BIGINT).row(42).build().get(0);

        WorkProcessor<Page> mergedPage = new MergeHashSort(newSimpleAggregatedMemoryContext(), blockTypeOperators).merge(
                ImmutableList.of(BIGINT),
                ImmutableList.of(BIGINT),
                ImmutableList.of(ImmutableList.of(emptyPage, page).iterator()).stream()
                        .map(WorkProcessor::fromIterator)
                        .collect(toImmutableList()),
                new DriverYieldSignal());

        assertTrue(mergedPage.process());
        Page actualPage = mergedPage.getResult();
        assertEquals(actualPage.getPositionCount(), 1);
        assertEquals(actualPage.getChannelCount(), 1);
        assertEquals(actualPage.getBlock(0).getLong(0, 0), 42);

        assertFinishes(mergedPage);
    }

    @Test
    public void testBinaryMergeIteratorOverPageWithDifferentHashes()
    {
        Page page = rowPagesBuilder(BIGINT)
                .row(42)
                .row(42)
                .row(52)
                .row(60)
                .build().get(0);

        WorkProcessor<Page> mergedPages = new MergeHashSort(newSimpleAggregatedMemoryContext(), blockTypeOperators).merge(
                ImmutableList.of(BIGINT),
                ImmutableList.of(BIGINT),
                ImmutableList.of(ImmutableList.of(page).iterator()).stream()
                        .map(WorkProcessor::fromIterator)
                        .collect(toImmutableList()),
                new DriverYieldSignal());

        assertTrue(mergedPages.process());
        Page resultPage = mergedPages.getResult();
        assertEquals(resultPage.getPositionCount(), 4);
        assertEquals(resultPage.getBlock(0).getLong(0, 0), 42);
        assertEquals(resultPage.getBlock(0).getLong(1, 0), 42);
        assertEquals(resultPage.getBlock(0).getLong(2, 0), 52);
        assertEquals(resultPage.getBlock(0).getLong(3, 0), 60);

        assertFinishes(mergedPages);
    }
}
