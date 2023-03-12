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
package io.trino.execution;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.stats.TestingGcMonitor;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.cost.StatsAndCosts;
import io.trino.exchange.ExchangeManagerRegistry;
import io.trino.execution.NodeTaskMap.PartitionedSplitCountTracker;
import io.trino.execution.buffer.LazyOutputBuffer;
import io.trino.execution.buffer.OutputBuffer;
import io.trino.execution.buffer.OutputBuffers;
import io.trino.execution.buffer.PipelinedOutputBuffers;
import io.trino.execution.buffer.SpoolingOutputStats;
import io.trino.memory.MemoryPool;
import io.trino.memory.QueryContext;
import io.trino.memory.context.SimpleLocalMemoryContext;
import io.trino.metadata.InternalNode;
import io.trino.metadata.Split;
import io.trino.operator.TaskContext;
import io.trino.operator.TaskStats;
import io.trino.spi.SplitWeight;
import io.trino.spiller.SpillSpaceTracker;
import io.trino.sql.planner.Partitioning;
import io.trino.sql.planner.PartitioningScheme;
import io.trino.sql.planner.PlanFragment;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.DynamicFilterId;
import io.trino.sql.planner.plan.PlanFragmentId;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.sql.planner.plan.TableScanNode;
import io.trino.testing.TestingMetadata.TestingColumnHandle;
import org.joda.time.DateTime;

import javax.annotation.concurrent.GuardedBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.execution.DynamicFiltersCollector.INITIAL_DYNAMIC_FILTERS_VERSION;
import static io.trino.execution.StateMachine.StateChangeListener;
import static io.trino.execution.buffer.PipelinedOutputBuffers.BufferType.BROADCAST;
import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.trino.sql.planner.SystemPartitioningHandle.SOURCE_DISTRIBUTION;
import static io.trino.testing.TestingHandles.TEST_TABLE_HANDLE;
import static io.trino.util.Failures.toFailures;
import static java.lang.Math.addExact;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class MockRemoteTaskFactory
        implements RemoteTaskFactory
{
    private static final String TASK_INSTANCE_ID = "task-instance-id";
    private final Executor executor;
    private final ScheduledExecutorService scheduledExecutor;

    public MockRemoteTaskFactory(Executor executor, ScheduledExecutorService scheduledExecutor)
    {
        this.executor = executor;
        this.scheduledExecutor = scheduledExecutor;
    }

    public MockRemoteTask createTableScanTask(TaskId taskId, InternalNode newNode, List<Split> splits, PartitionedSplitCountTracker partitionedSplitCountTracker)
    {
        Symbol symbol = new Symbol("column");
        PlanNodeId sourceId = new PlanNodeId("sourceId");
        PlanFragment testFragment = new PlanFragment(
                new PlanFragmentId("test"),
                TableScanNode.newInstance(
                        sourceId,
                        TEST_TABLE_HANDLE,
                        ImmutableList.of(symbol),
                        ImmutableMap.of(symbol, new TestingColumnHandle("column")),
                        false,
                        Optional.empty()),
                ImmutableMap.of(symbol, VARCHAR),
                SOURCE_DISTRIBUTION,
                Optional.empty(),
                ImmutableList.of(sourceId),
                new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), ImmutableList.of(symbol)),
                StatsAndCosts.empty(),
                ImmutableList.of(),
                Optional.empty());

        ImmutableMultimap.Builder<PlanNodeId, Split> initialSplits = ImmutableMultimap.builder();
        for (Split sourceSplit : splits) {
            initialSplits.put(sourceId, sourceSplit);
        }
        return createRemoteTask(TEST_SESSION, taskId, newNode, testFragment, initialSplits.build(), PipelinedOutputBuffers.createInitial(BROADCAST), partitionedSplitCountTracker, ImmutableSet.of(), Optional.empty(), true);
    }

    @Override
    public MockRemoteTask createRemoteTask(
            Session session,
            TaskId taskId,
            InternalNode node,
            PlanFragment fragment,
            Multimap<PlanNodeId, Split> initialSplits,
            OutputBuffers outputBuffers,
            PartitionedSplitCountTracker partitionedSplitCountTracker,
            Set<DynamicFilterId> outboundDynamicFilterIds,
            Optional<DataSize> estimatedMemory,
            boolean summarizeTaskInfo)
    {
        return new MockRemoteTask(taskId, fragment, node.getNodeIdentifier(), executor, scheduledExecutor, initialSplits, partitionedSplitCountTracker);
    }

    public static final class MockRemoteTask
            implements RemoteTask
    {
        private final AtomicLong nextTaskInfoVersion = new AtomicLong(TaskStatus.STARTING_VERSION);

        private final URI location;
        private final TaskStateMachine taskStateMachine;
        private final TaskContext taskContext;
        private final OutputBuffer outputBuffer;
        private final String nodeId;

        private final PlanFragment fragment;

        @GuardedBy("this")
        private final Set<PlanNodeId> noMoreSplits = new HashSet<>();

        @GuardedBy("this")
        private final Multimap<PlanNodeId, Split> splits = HashMultimap.create();

        @GuardedBy("this")
        private int runningDrivers;

        @GuardedBy("this")
        private int maxUnacknowledgedSplits = Integer.MAX_VALUE;
        @GuardedBy("this")
        private int unacknowledgedSplits;

        @GuardedBy("this")
        private SettableFuture<Void> whenSplitQueueHasSpace = SettableFuture.create();

        private final PartitionedSplitCountTracker partitionedSplitCountTracker;

        public MockRemoteTask(
                TaskId taskId,
                PlanFragment fragment,
                String nodeId,
                Executor executor,
                ScheduledExecutorService scheduledExecutor,
                Multimap<PlanNodeId, Split> initialSplits,
                PartitionedSplitCountTracker partitionedSplitCountTracker)
        {
            this.taskStateMachine = new TaskStateMachine(requireNonNull(taskId, "taskId is null"), requireNonNull(executor, "executor is null"));

            MemoryPool memoryPool = new MemoryPool(DataSize.of(1, GIGABYTE));
            SpillSpaceTracker spillSpaceTracker = new SpillSpaceTracker(DataSize.of(1, GIGABYTE));
            QueryContext queryContext = new QueryContext(taskId.getQueryId(),
                    DataSize.of(1, MEGABYTE),
                    memoryPool,
                    new TestingGcMonitor(),
                    executor,
                    scheduledExecutor,
                    DataSize.of(1, MEGABYTE),
                    spillSpaceTracker);
            this.taskContext = queryContext.addTaskContext(taskStateMachine, TEST_SESSION, () -> {}, true, true);

            this.location = URI.create("fake://task/" + taskId);

            this.outputBuffer = new LazyOutputBuffer(
                    taskId,
                    TASK_INSTANCE_ID,
                    executor,
                    DataSize.ofBytes(1),
                    DataSize.ofBytes(1),
                    () -> new SimpleLocalMemoryContext(newSimpleAggregatedMemoryContext(), "test"),
                    () -> {},
                    new ExchangeManagerRegistry());

            this.fragment = requireNonNull(fragment, "fragment is null");
            this.nodeId = requireNonNull(nodeId, "nodeId is null");
            splits.putAll(initialSplits);
            this.partitionedSplitCountTracker = requireNonNull(partitionedSplitCountTracker, "partitionedSplitCountTracker is null");
            partitionedSplitCountTracker.setPartitionedSplits(getPartitionedSplitsInfo());
            updateSplitQueueSpace();
        }

        @Override
        public TaskId getTaskId()
        {
            return taskStateMachine.getTaskId();
        }

        @Override
        public String getNodeId()
        {
            return nodeId;
        }

        @Override
        public TaskInfo getTaskInfo()
        {
            TaskState state = taskStateMachine.getState();
            List<ExecutionFailureInfo> failures = ImmutableList.of();
            if (state == TaskState.FAILED) {
                failures = toFailures(taskStateMachine.getFailureCauses());
            }

            return new TaskInfo(
                    new TaskStatus(
                            taskStateMachine.getTaskId(),
                            TASK_INSTANCE_ID,
                            nextTaskInfoVersion.getAndIncrement(),
                            state,
                            location,
                            nodeId,
                            failures,
                            0,
                            0,
                            outputBuffer.getStatus(),
                            DataSize.ofBytes(0),
                            DataSize.ofBytes(0),
                            Optional.empty(),
                            DataSize.ofBytes(0),
                            DataSize.ofBytes(0),
                            DataSize.ofBytes(0),
                            0,
                            new Duration(0, MILLISECONDS),
                            INITIAL_DYNAMIC_FILTERS_VERSION,
                            0L,
                            0L),
                    DateTime.now(),
                    outputBuffer.getInfo(),
                    ImmutableSet.of(),
                    taskContext.getTaskStats(),
                    Optional.empty(),
                    true);
        }

        @Override
        public TaskStatus getTaskStatus()
        {
            TaskStats stats = taskContext.getTaskStats();
            PartitionedSplitsInfo combinedSplitsInfo = getPartitionedSplitsInfo();
            PartitionedSplitsInfo queuedSplitsInfo = getQueuedPartitionedSplitsInfo();
            return new TaskStatus(taskStateMachine.getTaskId(),
                    TASK_INSTANCE_ID,
                    nextTaskInfoVersion.get(),
                    taskStateMachine.getState(),
                    location,
                    nodeId,
                    ImmutableList.of(),
                    queuedSplitsInfo.getCount(),
                    combinedSplitsInfo.getCount() - queuedSplitsInfo.getCount(),
                    outputBuffer.getStatus(),
                    stats.getOutputDataSize(),
                    stats.getPhysicalWrittenDataSize(),
                    stats.getMaxWriterCount(),
                    stats.getUserMemoryReservation(),
                    stats.getPeakUserMemoryReservation(),
                    stats.getRevocableMemoryReservation(),
                    0,
                    new Duration(0, MILLISECONDS),
                    INITIAL_DYNAMIC_FILTERS_VERSION,
                    queuedSplitsInfo.getWeightSum(),
                    combinedSplitsInfo.getWeightSum() - queuedSplitsInfo.getWeightSum());
        }

        private synchronized void updateSplitQueueSpace()
        {
            if (unacknowledgedSplits < maxUnacknowledgedSplits && getQueuedPartitionedSplitsInfo().getWeightSum() < 900L) {
                if (!whenSplitQueueHasSpace.isDone()) {
                    whenSplitQueueHasSpace.set(null);
                }
            }
            else {
                if (whenSplitQueueHasSpace.isDone()) {
                    whenSplitQueueHasSpace = SettableFuture.create();
                }
            }
        }

        public synchronized void finishSplits(int splits)
        {
            List<Map.Entry<PlanNodeId, Split>> toRemove = new ArrayList<>();
            Iterator<Map.Entry<PlanNodeId, Split>> iterator = this.splits.entries().iterator();
            while (toRemove.size() < splits && iterator.hasNext()) {
                toRemove.add(iterator.next());
            }
            for (Map.Entry<PlanNodeId, Split> entry : toRemove) {
                this.splits.remove(entry.getKey(), entry.getValue());
            }
            updateSplitQueueSpace();
        }

        public synchronized void clearSplits()
        {
            unacknowledgedSplits = 0;
            splits.clear();
            partitionedSplitCountTracker.setPartitionedSplits(PartitionedSplitsInfo.forZeroSplits());
            runningDrivers = 0;
            updateSplitQueueSpace();
        }

        public synchronized void setMaxUnacknowledgedSplits(int maxUnacknowledgedSplits)
        {
            checkArgument(maxUnacknowledgedSplits > 0);
            this.maxUnacknowledgedSplits = maxUnacknowledgedSplits;
            updateSplitQueueSpace();
        }

        public synchronized void setUnacknowledgedSplits(int unacknowledgedSplits)
        {
            checkArgument(unacknowledgedSplits >= 0);
            this.unacknowledgedSplits = unacknowledgedSplits;
            updateSplitQueueSpace();
        }

        public synchronized void startSplits(int maxRunning)
        {
            runningDrivers = splits.size();
            runningDrivers = Math.min(runningDrivers, maxRunning);
            updateSplitQueueSpace();
        }

        @Override
        public void start()
        {
            taskStateMachine.addStateChangeListener(newValue -> {
                if (newValue.isDone()) {
                    clearSplits();
                }
            });
        }

        @Override
        public void addSplits(Multimap<PlanNodeId, Split> splits)
        {
            synchronized (this) {
                this.splits.putAll(splits);
            }
            partitionedSplitCountTracker.setPartitionedSplits(getPartitionedSplitsInfo());
            updateSplitQueueSpace();
        }

        @Override
        public synchronized void noMoreSplits(PlanNodeId sourceId)
        {
            noMoreSplits.add(sourceId);

            boolean allSourcesComplete = Stream.concat(fragment.getPartitionedSourceNodes().stream(), fragment.getRemoteSourceNodes().stream())
                    .filter(Objects::nonNull)
                    .map(PlanNode::getId)
                    .allMatch(noMoreSplits::contains);

            if (allSourcesComplete) {
                taskStateMachine.finished();
            }
        }

        @Override
        public void setOutputBuffers(OutputBuffers outputBuffers)
        {
            outputBuffer.setOutputBuffers(outputBuffers);
        }

        @Override
        public void addStateChangeListener(StateChangeListener<TaskStatus> stateChangeListener)
        {
            taskStateMachine.addStateChangeListener(newValue -> stateChangeListener.stateChanged(getTaskStatus()));
        }

        @Override
        public void addFinalTaskInfoListener(StateChangeListener<TaskInfo> stateChangeListener)
        {
            AtomicBoolean done = new AtomicBoolean();
            StateChangeListener<TaskState> fireOnceStateChangeListener = state -> {
                if (state.isDone() && done.compareAndSet(false, true)) {
                    stateChangeListener.stateChanged(getTaskInfo());
                }
            };
            taskStateMachine.addStateChangeListener(fireOnceStateChangeListener);
            fireOnceStateChangeListener.stateChanged(taskStateMachine.getState());
        }

        @Override
        public synchronized ListenableFuture<Void> whenSplitQueueHasSpace(long weightThreshold)
        {
            return nonCancellationPropagating(whenSplitQueueHasSpace);
        }

        @Override
        public void cancel()
        {
            taskStateMachine.cancel();
        }

        @Override
        public void abort()
        {
            taskStateMachine.abort();
            clearSplits();
        }

        @Override
        public void fail(Throwable cause)
        {
            taskStateMachine.failed(cause);
            clearSplits();
        }

        @Override
        public void failRemotely(Throwable cause)
        {
            taskStateMachine.failed(cause);
            clearSplits();
        }

        @Override
        public PartitionedSplitsInfo getPartitionedSplitsInfo()
        {
            if (taskStateMachine.getState().isDone()) {
                return PartitionedSplitsInfo.forZeroSplits();
            }
            synchronized (this) {
                int count = 0;
                long weight = 0;
                for (PlanNodeId tableScanPlanNodeId : fragment.getPartitionedSources()) {
                    Collection<Split> partitionedSplits = splits.get(tableScanPlanNodeId);
                    count += partitionedSplits.size();
                    weight = addExact(weight, SplitWeight.rawValueSum(partitionedSplits, Split::getSplitWeight));
                }
                return PartitionedSplitsInfo.forSplitCountAndWeightSum(count, weight);
            }
        }

        @Override
        public synchronized PartitionedSplitsInfo getQueuedPartitionedSplitsInfo()
        {
            if (taskStateMachine.getState().isDone()) {
                return PartitionedSplitsInfo.forZeroSplits();
            }
            // Let's consider the first drivers encountered to be "running"
            int remainingRunning = runningDrivers;
            int queuedCount = 0;
            long queuedWeight = 0;
            for (PlanNodeId tableScanPlanNodeId : fragment.getPartitionedSources()) {
                for (Split split : splits.get(tableScanPlanNodeId)) {
                    if (remainingRunning > 0) {
                        remainingRunning--;
                    }
                    else {
                        queuedCount++;
                        queuedWeight = addExact(queuedWeight, split.getSplitWeight().getRawValue());
                    }
                }
            }
            return PartitionedSplitsInfo.forSplitCountAndWeightSum(queuedCount, queuedWeight);
        }

        @Override
        public synchronized int getUnacknowledgedPartitionedSplitCount()
        {
            return unacknowledgedSplits;
        }

        @Override
        public SpoolingOutputStats.Snapshot retrieveAndDropSpoolingOutputStats()
        {
            throw new UnsupportedOperationException();
        }
    }
}
