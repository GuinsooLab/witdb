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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import io.trino.array.LongBigArray;
import io.trino.operator.RowReferencePageManager.LoadCursor;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.type.Type;
import org.openjdk.jol.info.ClassLayout;

import java.util.Iterator;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static io.trino.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

/**
 * This class finds the top N rows defined by {@param comparator} for each group specified by {@param groupByHash}.
 */
public class GroupedTopNRowNumberBuilder
        implements GroupedTopNBuilder
{
    private static final long INSTANCE_SIZE = ClassLayout.parseClass(GroupedTopNRowNumberBuilder.class).instanceSize();

    private final List<Type> sourceTypes;
    private final boolean produceRowNumber;
    private final GroupByHash groupByHash;
    private final RowReferencePageManager pageManager = new RowReferencePageManager();
    private final GroupedTopNRowNumberAccumulator groupedTopNRowNumberAccumulator;

    public GroupedTopNRowNumberBuilder(
            List<Type> sourceTypes,
            PageWithPositionComparator comparator,
            int topN,
            boolean produceRowNumber,
            GroupByHash groupByHash)
    {
        this.sourceTypes = requireNonNull(sourceTypes, "sourceTypes is null");
        checkArgument(topN > 0, "topN must be > 0");
        this.produceRowNumber = produceRowNumber;
        this.groupByHash = requireNonNull(groupByHash, "groupByHash is null");

        requireNonNull(comparator, "comparator is null");
        groupedTopNRowNumberAccumulator = new GroupedTopNRowNumberAccumulator(
                (leftRowId, rightRowId) -> {
                    Page leftPage = pageManager.getPage(leftRowId);
                    int leftPosition = pageManager.getPosition(leftRowId);
                    Page rightPage = pageManager.getPage(rightRowId);
                    int rightPosition = pageManager.getPosition(rightRowId);
                    return comparator.compareTo(leftPage, leftPosition, rightPage, rightPosition);
                },
                topN,
                pageManager::dereference);
    }

    @Override
    public Work<?> processPage(Page page)
    {
        return new TransformWork<>(
                groupByHash.getGroupIds(page),
                groupIds -> {
                    processPage(page, groupIds);
                    return null;
                });
    }

    @Override
    public Iterator<Page> buildResult()
    {
        return new ResultIterator();
    }

    @Override
    public long getEstimatedSizeInBytes()
    {
        return INSTANCE_SIZE
                + groupByHash.getEstimatedSize()
                + pageManager.sizeOf()
                + groupedTopNRowNumberAccumulator.sizeOf();
    }

    private void processPage(Page newPage, GroupByIdBlock groupIds)
    {
        try (LoadCursor loadCursor = pageManager.add(newPage)) {
            for (int position = 0; position < newPage.getPositionCount(); position++) {
                long groupId = groupIds.getGroupId(position);
                loadCursor.advance();
                groupedTopNRowNumberAccumulator.add(groupId, loadCursor);
            }
            verify(!loadCursor.advance());
        }

        pageManager.compactIfNeeded();
    }

    @VisibleForTesting
    GroupByHash getGroupByHash()
    {
        return groupByHash;
    }

    private class ResultIterator
            extends AbstractIterator<Page>
    {
        private final PageBuilder pageBuilder;
        private final long groupIdCount = groupByHash.getGroupCount();
        private long currentGroupId = -1;
        private final LongBigArray rowIdOutput = new LongBigArray();
        private long currentGroupSize;
        private int currentIndexInGroup;

        ResultIterator()
        {
            ImmutableList.Builder<Type> sourceTypesBuilders = ImmutableList.<Type>builder().addAll(sourceTypes);
            if (produceRowNumber) {
                sourceTypesBuilders.add(BIGINT);
            }
            pageBuilder = new PageBuilder(sourceTypesBuilders.build());
        }

        @Override
        protected Page computeNext()
        {
            pageBuilder.reset();
            while (!pageBuilder.isFull()) {
                while (currentIndexInGroup >= currentGroupSize) {
                    if (currentGroupId + 1 >= groupIdCount) {
                        if (pageBuilder.isEmpty()) {
                            return endOfData();
                        }
                        return pageBuilder.build();
                    }
                    currentGroupId++;
                    currentGroupSize = groupedTopNRowNumberAccumulator.drainTo(currentGroupId, rowIdOutput);
                    currentIndexInGroup = 0;
                }

                long rowId = rowIdOutput.get(currentIndexInGroup);
                Page page = pageManager.getPage(rowId);
                int position = pageManager.getPosition(rowId);
                for (int i = 0; i < sourceTypes.size(); i++) {
                    sourceTypes.get(i).appendTo(page.getBlock(i), position, pageBuilder.getBlockBuilder(i));
                }
                if (produceRowNumber) {
                    BIGINT.writeLong(pageBuilder.getBlockBuilder(sourceTypes.size()), currentIndexInGroup + 1);
                }
                pageBuilder.declarePosition();
                currentIndexInGroup++;

                // Deference the row for hygiene, but no need to compact them at this point
                pageManager.dereference(rowId);
            }

            if (pageBuilder.isEmpty()) {
                return endOfData();
            }
            return pageBuilder.build();
        }
    }
}
