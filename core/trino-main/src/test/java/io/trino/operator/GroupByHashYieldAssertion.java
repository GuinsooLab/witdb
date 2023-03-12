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
import io.airlift.stats.TestingGcMonitor;
import io.airlift.units.DataSize;
import io.trino.RowPagesBuilder;
import io.trino.execution.StageId;
import io.trino.execution.TaskId;
import io.trino.memory.MemoryPool;
import io.trino.memory.QueryContext;
import io.trino.spi.Page;
import io.trino.spi.QueryId;
import io.trino.spi.type.Type;
import io.trino.spiller.SpillSpaceTracker;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.testing.Assertions.assertBetweenInclusive;
import static io.airlift.testing.Assertions.assertGreaterThan;
import static io.airlift.testing.Assertions.assertLessThan;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.operator.OperatorAssertion.finishOperator;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.testing.TestingTaskContext.createTaskContext;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public final class GroupByHashYieldAssertion
{
    private static final ExecutorService EXECUTOR = newCachedThreadPool(daemonThreadsNamed("GroupByHashYieldAssertion-%s"));
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR = newScheduledThreadPool(2, daemonThreadsNamed("GroupByHashYieldAssertion-scheduledExecutor-%s"));

    private GroupByHashYieldAssertion() {}

    public static List<Page> createPagesWithDistinctHashKeys(Type type, int pageCount, int positionCountPerPage)
    {
        RowPagesBuilder rowPagesBuilder = rowPagesBuilder(true, ImmutableList.of(0), type);
        for (int i = 0; i < pageCount; i++) {
            rowPagesBuilder.addSequencePage(positionCountPerPage, positionCountPerPage * i);
        }
        return rowPagesBuilder.build();
    }

    /**
     * @param operatorFactory creates an Operator that should directly or indirectly contain GroupByHash
     * @param getHashCapacity returns the hash table capacity for the input operator
     * @param additionalMemoryInBytes the memory used in addition to the GroupByHash in the operator (e.g., aggregator)
     */
    public static GroupByHashYieldResult finishOperatorWithYieldingGroupByHash(List<Page> input, Type hashKeyType, OperatorFactory operatorFactory, Function<Operator, Integer> getHashCapacity, long additionalMemoryInBytes)
    {
        assertLessThan(additionalMemoryInBytes, 1L << 21, "additionalMemoryInBytes should be a relatively small number");
        List<Page> result = new LinkedList<>();

        // mock an adjustable memory pool
        QueryId queryId = new QueryId("test_query");
        TaskId anotherTaskId = new TaskId(new StageId("another_query", 0), 0, 0);
        MemoryPool memoryPool = new MemoryPool(DataSize.of(1, GIGABYTE));
        QueryContext queryContext = new QueryContext(
                queryId,
                DataSize.of(512, MEGABYTE),
                memoryPool,
                new TestingGcMonitor(),
                EXECUTOR,
                SCHEDULED_EXECUTOR,
                DataSize.of(512, MEGABYTE),
                new SpillSpaceTracker(DataSize.of(512, MEGABYTE)));

        DriverContext driverContext = createTaskContext(queryContext, EXECUTOR, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
        Operator operator = operatorFactory.createOperator(driverContext);

        // run operator
        int yieldCount = 0;
        long expectedReservedExtraBytes = 0;
        for (Page page : input) {
            // unblocked
            assertTrue(operator.needsInput());

            // saturate the pool with a tiny memory left
            long reservedMemoryInBytes = memoryPool.getFreeBytes() - additionalMemoryInBytes;
            memoryPool.reserve(anotherTaskId, "test", reservedMemoryInBytes);

            long oldMemoryUsage = operator.getOperatorContext().getDriverContext().getMemoryUsage();
            int oldCapacity = getHashCapacity.apply(operator);

            // add a page and verify different behaviors
            operator.addInput(page);

            // get output to consume the input
            Page output = operator.getOutput();
            if (output != null) {
                result.add(output);
            }

            long newMemoryUsage = operator.getOperatorContext().getDriverContext().getMemoryUsage();

            // Skip if the memory usage is not large enough since we cannot distinguish
            // between rehash and memory used by aggregator
            if (newMemoryUsage < DataSize.of(4, MEGABYTE).toBytes()) {
                // free the pool for the next iteration
                memoryPool.free(anotherTaskId, "test", reservedMemoryInBytes);
                // this required in case input is blocked
                output = operator.getOutput();
                if (output != null) {
                    result.add(output);
                }
                continue;
            }

            long actualIncreasedMemory = newMemoryUsage - oldMemoryUsage;

            if (operator.needsInput()) {
                // We have successfully added a page

                // Assert we are not blocked
                assertTrue(operator.getOperatorContext().isWaitingForMemory().isDone());

                // assert the hash capacity is not changed; otherwise, we should have yielded
                assertEquals((int) getHashCapacity.apply(operator), oldCapacity);

                // We are not going to rehash; therefore, assert the memory increase only comes from the aggregator
                assertLessThan(actualIncreasedMemory, additionalMemoryInBytes);

                // free the pool for the next iteration
                memoryPool.free(anotherTaskId, "test", reservedMemoryInBytes);
            }
            else {
                // We failed to finish the page processing i.e. we yielded
                yieldCount++;

                // Assert we are blocked
                assertFalse(operator.getOperatorContext().isWaitingForMemory().isDone());

                // Hash table capacity should not change
                assertEquals(oldCapacity, (long) getHashCapacity.apply(operator));

                expectedReservedExtraBytes = getHashTableSizeInBytes(hashKeyType, oldCapacity * 2);
                if (hashKeyType == BIGINT) {
                    expectedReservedExtraBytes += page.getRetainedSizeInBytes();
                }
                // Increased memory is no smaller than the hash table size and no greater than the hash table size + the memory used by aggregator
                assertBetweenInclusive(actualIncreasedMemory, expectedReservedExtraBytes, expectedReservedExtraBytes + additionalMemoryInBytes);

                // Output should be blocked as well
                assertNull(operator.getOutput());

                // Free the pool to unblock
                memoryPool.free(anotherTaskId, "test", reservedMemoryInBytes);

                // Trigger a process through getOutput() or needsInput()
                output = operator.getOutput();
                if (output != null) {
                    result.add(output);
                }
                assertTrue(operator.needsInput());

                // Hash table capacity has increased
                assertGreaterThan(getHashCapacity.apply(operator), oldCapacity);

                // Assert the estimated reserved memory after rehash is lower than the one before rehash (extra memory allocation has been released)
                long rehashedMemoryUsage = operator.getOperatorContext().getDriverContext().getMemoryUsage();
                long previousHashTableSizeInBytes = getHashTableSizeInBytes(hashKeyType, oldCapacity);
                long expectedMemoryUsageAfterRehash = newMemoryUsage - previousHashTableSizeInBytes;
                double memoryUsageErrorUpperBound = 1.01;
                double memoryUsageError = rehashedMemoryUsage * 1.0 / expectedMemoryUsageAfterRehash;
                if (memoryUsageError > memoryUsageErrorUpperBound) {
                    // Usually the error is < 1%, but since MultiChannelGroupByHash.getEstimatedSize
                    // accounts for changes in completedPagesMemorySize, which is increased if new page is
                    // added by addNewGroup (an even that cannot be predicted as it depends on the number of unique groups
                    // in the current page being processed), the difference includes size of the added new page.
                    // Lower bound is 1% lower than normal because additionalMemoryInBytes includes also aggregator state.
                    assertBetweenInclusive(rehashedMemoryUsage * 1.0 / (expectedMemoryUsageAfterRehash + additionalMemoryInBytes), 0.97, memoryUsageErrorUpperBound,
                            "rehashedMemoryUsage " + rehashedMemoryUsage + ", expectedMemoryUsageAfterRehash: " + expectedMemoryUsageAfterRehash);
                }
                else {
                    assertBetweenInclusive(memoryUsageError, 0.99, memoryUsageErrorUpperBound);
                }

                // unblocked
                assertTrue(operator.needsInput());
                assertTrue(operator.getOperatorContext().isWaitingForMemory().isDone());
            }
        }

        result.addAll(finishOperator(operator));
        return new GroupByHashYieldResult(yieldCount, expectedReservedExtraBytes, result);
    }

    private static long getHashTableSizeInBytes(Type hashKeyType, int capacity)
    {
        if (hashKeyType == BIGINT) {
            // groupIds and values double by hashCapacity; while valuesByGroupId double by maxFill = hashCapacity / 0.75
            return capacity * (long) (Long.BYTES * 1.75 + Integer.BYTES);
        }
        // groupIdsByHash, and rawHashByHashPosition double by hashCapacity
        return capacity * (long) (Integer.BYTES + Byte.BYTES);
    }

    public static final class GroupByHashYieldResult
    {
        private final int yieldCount;
        private final long maxReservedBytes;
        private final List<Page> output;

        public GroupByHashYieldResult(int yieldCount, long maxReservedBytes, List<Page> output)
        {
            this.yieldCount = yieldCount;
            this.maxReservedBytes = maxReservedBytes;
            this.output = requireNonNull(output, "output is null");
        }

        public int getYieldCount()
        {
            return yieldCount;
        }

        public long getMaxReservedBytes()
        {
            return maxReservedBytes;
        }

        public List<Page> getOutput()
        {
            return output;
        }
    }
}
