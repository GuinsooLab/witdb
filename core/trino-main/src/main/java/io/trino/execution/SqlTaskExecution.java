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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.SetThreadName;
import io.airlift.units.Duration;
import io.trino.event.SplitMonitor;
import io.trino.execution.StateMachine.StateChangeListener;
import io.trino.execution.buffer.BufferState;
import io.trino.execution.buffer.OutputBuffer;
import io.trino.execution.executor.TaskExecutor;
import io.trino.execution.executor.TaskHandle;
import io.trino.operator.Driver;
import io.trino.operator.DriverContext;
import io.trino.operator.DriverFactory;
import io.trino.operator.DriverStats;
import io.trino.operator.PipelineContext;
import io.trino.operator.TaskContext;
import io.trino.spi.SplitWeight;
import io.trino.spi.TrinoException;
import io.trino.sql.planner.LocalExecutionPlanner.LocalExecutionPlan;
import io.trino.sql.planner.plan.PlanNodeId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.trino.SystemSessionProperties.getInitialSplitsPerNode;
import static io.trino.SystemSessionProperties.getMaxDriversPerTask;
import static io.trino.SystemSessionProperties.getSplitConcurrencyAdjustmentInterval;
import static io.trino.execution.SqlTaskExecution.SplitsState.ADDING_SPLITS;
import static io.trino.execution.SqlTaskExecution.SplitsState.FINISHED;
import static io.trino.execution.SqlTaskExecution.SplitsState.NO_MORE_SPLITS;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;

public class SqlTaskExecution
{
    private final TaskId taskId;
    private final TaskStateMachine taskStateMachine;
    private final TaskContext taskContext;
    private final OutputBuffer outputBuffer;

    private final TaskHandle taskHandle;
    private final TaskExecutor taskExecutor;

    private final Executor notificationExecutor;

    private final SplitMonitor splitMonitor;

    private final List<WeakReference<Driver>> drivers = new CopyOnWriteArrayList<>();

    private final Map<PlanNodeId, DriverSplitRunnerFactory> driverRunnerFactoriesWithSplitLifeCycle;
    private final List<DriverSplitRunnerFactory> driverRunnerFactoriesWithTaskLifeCycle;

    // guarded for update only
    @GuardedBy("this")
    private final ConcurrentMap<PlanNodeId, SplitAssignment> unpartitionedSplitAssignments = new ConcurrentHashMap<>();

    @GuardedBy("this")
    private long maxAcknowledgedSplit = Long.MIN_VALUE;

    @GuardedBy("this")
    private final List<PlanNodeId> sourceStartOrder;
    @GuardedBy("this")
    private int schedulingPlanNodeOrdinal;

    @GuardedBy("this")
    private final Map<PlanNodeId, PendingSplitsForPlanNode> pendingSplitsByPlanNode;

    private final Status status;

    static SqlTaskExecution createSqlTaskExecution(
            TaskStateMachine taskStateMachine,
            TaskContext taskContext,
            OutputBuffer outputBuffer,
            LocalExecutionPlan localExecutionPlan,
            TaskExecutor taskExecutor,
            Executor notificationExecutor,
            SplitMonitor queryMonitor)
    {
        SqlTaskExecution task = new SqlTaskExecution(
                taskStateMachine,
                taskContext,
                outputBuffer,
                localExecutionPlan,
                taskExecutor,
                queryMonitor,
                notificationExecutor);
        try (SetThreadName ignored = new SetThreadName("Task-%s", task.getTaskId())) {
            // The scheduleDriversForTaskLifeCycle method calls enqueueDriverSplitRunner, which registers a callback with access to this object.
            // The call back is accessed from another thread, so this code cannot be placed in the constructor.
            task.scheduleDriversForTaskLifeCycle();
            return task;
        }
    }

    private SqlTaskExecution(
            TaskStateMachine taskStateMachine,
            TaskContext taskContext,
            OutputBuffer outputBuffer,
            LocalExecutionPlan localExecutionPlan,
            TaskExecutor taskExecutor,
            SplitMonitor splitMonitor,
            Executor notificationExecutor)
    {
        this.taskStateMachine = requireNonNull(taskStateMachine, "taskStateMachine is null");
        this.taskId = taskStateMachine.getTaskId();
        this.taskContext = requireNonNull(taskContext, "taskContext is null");
        this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");

        this.taskExecutor = requireNonNull(taskExecutor, "taskExecutor is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");

        this.splitMonitor = requireNonNull(splitMonitor, "splitMonitor is null");

        try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
            // index driver factories
            Set<PlanNodeId> partitionedSources = ImmutableSet.copyOf(localExecutionPlan.getPartitionedSourceOrder());
            ImmutableMap.Builder<PlanNodeId, DriverSplitRunnerFactory> driverRunnerFactoriesWithSplitLifeCycle = ImmutableMap.builder();
            ImmutableList.Builder<DriverSplitRunnerFactory> driverRunnerFactoriesWithTaskLifeCycle = ImmutableList.builder();
            for (DriverFactory driverFactory : localExecutionPlan.getDriverFactories()) {
                Optional<PlanNodeId> sourceId = driverFactory.getSourceId();
                if (sourceId.isPresent() && partitionedSources.contains(sourceId.get())) {
                    driverRunnerFactoriesWithSplitLifeCycle.put(sourceId.get(), new DriverSplitRunnerFactory(driverFactory, true));
                }
                else {
                    driverRunnerFactoriesWithTaskLifeCycle.add(new DriverSplitRunnerFactory(driverFactory, false));
                }
            }
            this.driverRunnerFactoriesWithSplitLifeCycle = driverRunnerFactoriesWithSplitLifeCycle.buildOrThrow();
            this.driverRunnerFactoriesWithTaskLifeCycle = driverRunnerFactoriesWithTaskLifeCycle.build();

            this.pendingSplitsByPlanNode = this.driverRunnerFactoriesWithSplitLifeCycle.keySet().stream()
                    .collect(toImmutableMap(identity(), ignore -> new PendingSplitsForPlanNode()));
            this.status = new Status(
                    localExecutionPlan.getDriverFactories().stream()
                            .map(DriverFactory::getPipelineId)
                            .collect(toImmutableSet()));
            sourceStartOrder = localExecutionPlan.getPartitionedSourceOrder();

            checkArgument(this.driverRunnerFactoriesWithSplitLifeCycle.keySet().equals(partitionedSources),
                    "Fragment is partitioned, but not all partitioned drivers were found");

            // don't register the task if it is already completed (most likely failed during planning above)
            if (!taskStateMachine.getState().isDone()) {
                taskHandle = createTaskHandle(taskStateMachine, taskContext, outputBuffer, localExecutionPlan, taskExecutor);
            }
            else {
                taskHandle = null;
            }

            outputBuffer.addStateChangeListener(new CheckTaskCompletionOnBufferFinish(SqlTaskExecution.this));
        }
    }

    // this is a separate method to ensure that the `this` reference is not leaked during construction
    private static TaskHandle createTaskHandle(
            TaskStateMachine taskStateMachine,
            TaskContext taskContext,
            OutputBuffer outputBuffer,
            LocalExecutionPlan localExecutionPlan,
            TaskExecutor taskExecutor)
    {
        TaskHandle taskHandle = taskExecutor.addTask(
                taskStateMachine.getTaskId(),
                outputBuffer::getUtilization,
                getInitialSplitsPerNode(taskContext.getSession()),
                getSplitConcurrencyAdjustmentInterval(taskContext.getSession()),
                getMaxDriversPerTask(taskContext.getSession()));
        taskStateMachine.addStateChangeListener(state -> {
            if (state.isDone()) {
                taskExecutor.removeTask(taskHandle);
                for (DriverFactory factory : localExecutionPlan.getDriverFactories()) {
                    factory.noMoreDrivers();
                }
            }
        });
        return taskHandle;
    }

    public TaskId getTaskId()
    {
        return taskId;
    }

    public TaskContext getTaskContext()
    {
        return taskContext;
    }

    public void addSplitAssignments(List<SplitAssignment> splitAssignments)
    {
        requireNonNull(splitAssignments, "splitAssignments is null");
        checkState(!Thread.holdsLock(this), "Cannot add split assignments while holding a lock on the %s", getClass().getSimpleName());

        try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
            // update our record of split assignments and schedule drivers for new partitioned splits
            Map<PlanNodeId, SplitAssignment> updatedUnpartitionedSources = updateSplitAssignments(splitAssignments);

            // tell existing drivers about the new splits; it is safe to update drivers
            // multiple times and out of order because split assignments contain full record of
            // the unpartitioned splits
            for (WeakReference<Driver> driverReference : drivers) {
                Driver driver = driverReference.get();
                // the driver can be GCed due to a failure or a limit
                if (driver == null) {
                    // remove the weak reference from the list to avoid a memory leak
                    // NOTE: this is a concurrent safe operation on a CopyOnWriteArrayList
                    drivers.remove(driverReference);
                    continue;
                }
                Optional<PlanNodeId> sourceId = driver.getSourceId();
                if (sourceId.isEmpty()) {
                    continue;
                }
                SplitAssignment splitAssignmentUpdate = updatedUnpartitionedSources.get(sourceId.get());
                if (splitAssignmentUpdate == null) {
                    continue;
                }
                driver.updateSplitAssignment(splitAssignmentUpdate);
            }

            // we may have transitioned to no more splits, so check for completion
            checkTaskCompletion();
        }
    }

    private synchronized Map<PlanNodeId, SplitAssignment> updateSplitAssignments(List<SplitAssignment> splitAssignments)
    {
        Map<PlanNodeId, SplitAssignment> updatedUnpartitionedSplitAssignments = new HashMap<>();

        // first remove any split that was already acknowledged
        long currentMaxAcknowledgedSplit = this.maxAcknowledgedSplit;
        splitAssignments = splitAssignments.stream()
                .map(source -> new SplitAssignment(
                        source.getPlanNodeId(),
                        source.getSplits().stream()
                                .filter(scheduledSplit -> scheduledSplit.getSequenceId() > currentMaxAcknowledgedSplit)
                                .collect(Collectors.toSet()),
                        source.isNoMoreSplits()))
                .collect(toList());

        // update task with new assignments
        for (SplitAssignment assignment : splitAssignments) {
            if (driverRunnerFactoriesWithSplitLifeCycle.containsKey(assignment.getPlanNodeId())) {
                schedulePartitionedSource(assignment);
            }
            else {
                scheduleUnpartitionedSource(assignment, updatedUnpartitionedSplitAssignments);
            }
        }

        for (DriverSplitRunnerFactory driverSplitRunnerFactory :
                Iterables.concat(driverRunnerFactoriesWithSplitLifeCycle.values(), driverRunnerFactoriesWithTaskLifeCycle)) {
            driverSplitRunnerFactory.closeDriverFactoryIfFullyCreated();
        }

        // update maxAcknowledgedSplit
        maxAcknowledgedSplit = splitAssignments.stream()
                .flatMap(source -> source.getSplits().stream())
                .mapToLong(ScheduledSplit::getSequenceId)
                .max()
                .orElse(maxAcknowledgedSplit);
        return updatedUnpartitionedSplitAssignments;
    }

    @GuardedBy("this")
    private void mergeIntoPendingSplits(PlanNodeId planNodeId, Set<ScheduledSplit> scheduledSplits, boolean noMoreSplits)
    {
        checkHoldsLock();

        DriverSplitRunnerFactory partitionedDriverFactory = driverRunnerFactoriesWithSplitLifeCycle.get(planNodeId);
        PendingSplitsForPlanNode pendingSplitsForPlanNode = pendingSplitsByPlanNode.get(planNodeId);

        partitionedDriverFactory.splitsAdded(scheduledSplits.size(), SplitWeight.rawValueSum(scheduledSplits, scheduledSplit -> scheduledSplit.getSplit().getSplitWeight()));
        for (ScheduledSplit scheduledSplit : scheduledSplits) {
            pendingSplitsForPlanNode.addSplit(scheduledSplit);
        }
        if (noMoreSplits) {
            pendingSplitsForPlanNode.setNoMoreSplits();
        }
    }

    private synchronized void schedulePartitionedSource(SplitAssignment splitAssignmentUpdate)
    {
        mergeIntoPendingSplits(splitAssignmentUpdate.getPlanNodeId(), splitAssignmentUpdate.getSplits(), splitAssignmentUpdate.isNoMoreSplits());

        while (schedulingPlanNodeOrdinal < sourceStartOrder.size()) {
            PlanNodeId schedulingPlanNode = sourceStartOrder.get(schedulingPlanNodeOrdinal);

            DriverSplitRunnerFactory partitionedDriverRunnerFactory = driverRunnerFactoriesWithSplitLifeCycle.get(schedulingPlanNode);

            PendingSplitsForPlanNode pendingSplits = pendingSplitsByPlanNode.get(schedulingPlanNode);

            // Enqueue driver runners with split lifecycle for this plan node and driver life cycle combination.
            ImmutableList.Builder<DriverSplitRunner> runners = ImmutableList.builder();
            for (ScheduledSplit scheduledSplit : pendingSplits.removeAllSplits()) {
                // create a new driver for the split
                runners.add(partitionedDriverRunnerFactory.createDriverRunner(scheduledSplit));
            }
            enqueueDriverSplitRunner(false, runners.build());

            // If all driver runners have been enqueued for this plan node and driver life cycle combination,
            // move on to the next plan node.
            if (pendingSplits.getState() != NO_MORE_SPLITS) {
                break;
            }
            partitionedDriverRunnerFactory.noMoreDriverRunner();
            pendingSplits.markAsCleanedUp();

            schedulingPlanNodeOrdinal++;
        }
    }

    private synchronized void scheduleUnpartitionedSource(SplitAssignment splitAssignmentUpdate, Map<PlanNodeId, SplitAssignment> updatedUnpartitionedSources)
    {
        // create new source
        SplitAssignment newSplitAssignment;
        SplitAssignment currentSplitAssignment = unpartitionedSplitAssignments.get(splitAssignmentUpdate.getPlanNodeId());
        if (currentSplitAssignment == null) {
            newSplitAssignment = splitAssignmentUpdate;
        }
        else {
            newSplitAssignment = currentSplitAssignment.update(splitAssignmentUpdate);
        }

        // only record new source if something changed
        if (newSplitAssignment != currentSplitAssignment) {
            unpartitionedSplitAssignments.put(splitAssignmentUpdate.getPlanNodeId(), newSplitAssignment);
            updatedUnpartitionedSources.put(splitAssignmentUpdate.getPlanNodeId(), newSplitAssignment);
        }
    }

    // scheduleDriversForTaskLifeCycle and scheduleDriversForDriverGroupLifeCycle are similar.
    // They are invoked under different circumstances, and schedules a disjoint set of drivers, as suggested by their names.
    // They also have a few differences, making it more convenient to keep the two methods separate.
    private void scheduleDriversForTaskLifeCycle()
    {
        // This method is called at the beginning of the task.
        // It schedules drivers for all the pipelines that have task life cycle.
        List<DriverSplitRunner> runners = new ArrayList<>();
        for (DriverSplitRunnerFactory driverRunnerFactory : driverRunnerFactoriesWithTaskLifeCycle) {
            for (int i = 0; i < driverRunnerFactory.getDriverInstances().orElse(1); i++) {
                runners.add(driverRunnerFactory.createDriverRunner(null));
            }
        }
        enqueueDriverSplitRunner(true, runners);
        for (DriverSplitRunnerFactory driverRunnerFactory : driverRunnerFactoriesWithTaskLifeCycle) {
            driverRunnerFactory.noMoreDriverRunner();
            verify(driverRunnerFactory.isNoMoreDriverRunner());
        }
    }

    private synchronized void enqueueDriverSplitRunner(boolean forceRunSplit, List<DriverSplitRunner> runners)
    {
        // schedule driver to be executed
        List<ListenableFuture<Void>> finishedFutures = taskExecutor.enqueueSplits(taskHandle, forceRunSplit, runners);
        checkState(finishedFutures.size() == runners.size(), "Expected %s futures but got %s", runners.size(), finishedFutures.size());

        // when driver completes, update state and fire events
        for (int i = 0; i < finishedFutures.size(); i++) {
            ListenableFuture<Void> finishedFuture = finishedFutures.get(i);
            DriverSplitRunner splitRunner = runners.get(i);

            // record new driver
            status.incrementRemainingDriver();

            Futures.addCallback(finishedFuture, new FutureCallback<Object>()
            {
                @Override
                public void onSuccess(Object result)
                {
                    try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
                        // record driver is finished
                        status.decrementRemainingDriver();

                        checkTaskCompletion();

                        splitMonitor.splitCompletedEvent(taskId, getDriverStats());
                    }
                }

                @Override
                public void onFailure(Throwable cause)
                {
                    try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
                        taskStateMachine.failed(cause);

                        // record driver is finished
                        status.decrementRemainingDriver();

                        // fire failed event with cause
                        splitMonitor.splitFailedEvent(taskId, getDriverStats(), cause);
                    }
                }

                private DriverStats getDriverStats()
                {
                    DriverContext driverContext = splitRunner.getDriverContext();
                    DriverStats driverStats;
                    if (driverContext != null) {
                        driverStats = driverContext.getDriverStats();
                    }
                    else {
                        // split runner did not start successfully
                        driverStats = new DriverStats();
                    }

                    return driverStats;
                }
            }, notificationExecutor);
        }
    }

    public synchronized Set<PlanNodeId> getNoMoreSplits()
    {
        ImmutableSet.Builder<PlanNodeId> noMoreSplits = ImmutableSet.builder();
        for (Entry<PlanNodeId, DriverSplitRunnerFactory> entry : driverRunnerFactoriesWithSplitLifeCycle.entrySet()) {
            if (entry.getValue().isNoMoreDriverRunner()) {
                noMoreSplits.add(entry.getKey());
            }
        }
        for (SplitAssignment splitAssignment : unpartitionedSplitAssignments.values()) {
            if (splitAssignment.isNoMoreSplits()) {
                noMoreSplits.add(splitAssignment.getPlanNodeId());
            }
        }
        return noMoreSplits.build();
    }

    private synchronized void checkTaskCompletion()
    {
        if (taskStateMachine.getState().isDone()) {
            return;
        }

        // are there more partition splits expected?
        for (DriverSplitRunnerFactory driverSplitRunnerFactory : driverRunnerFactoriesWithSplitLifeCycle.values()) {
            if (!driverSplitRunnerFactory.isNoMoreDriverRunner()) {
                return;
            }
        }
        // do we still have running tasks?
        if (status.getRemainingDriver() != 0) {
            return;
        }

        // no more output will be created
        outputBuffer.setNoMorePages();

        BufferState bufferState = outputBuffer.getState();
        if (!bufferState.isTerminal()) {
            taskStateMachine.transitionToFlushing();
            return;
        }

        if (bufferState == BufferState.FINISHED) {
            // Cool! All done!
            taskStateMachine.finished();
            return;
        }

        if (bufferState == BufferState.FAILED) {
            Throwable failureCause = outputBuffer.getFailureCause()
                    .orElseGet(() -> new TrinoException(GENERIC_INTERNAL_ERROR, "Output buffer is failed but the failure cause is missing"));
            taskStateMachine.failed(failureCause);
            return;
        }

        // The only terminal state that remains is ABORTED.
        // Buffer is expected to be aborted only if the task itself is aborted. In this scenario the following statement is expected to be noop.
        taskStateMachine.failed(new TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected buffer state: " + bufferState));
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("taskId", taskId)
                .add("remainingDrivers", status.getRemainingDriver())
                .add("unpartitionedSplitAssignments", unpartitionedSplitAssignments)
                .toString();
    }

    private void checkHoldsLock()
    {
        // This method serves a similar purpose at runtime as GuardedBy on method serves during static analysis.
        // This method should not have significant performance impact. If it does, it may be reasonably to remove this method.
        // This intentionally does not use checkState.
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(format("Thread must hold a lock on the %s", getClass().getSimpleName()));
        }
    }

    // Splits for a particular plan node
    @NotThreadSafe
    private static class PendingSplitsForPlanNode
    {
        private Set<ScheduledSplit> splits = new HashSet<>();
        private SplitsState state = ADDING_SPLITS;
        private boolean noMoreSplits;

        public void setNoMoreSplits()
        {
            if (noMoreSplits) {
                return;
            }
            noMoreSplits = true;
            if (state == ADDING_SPLITS) {
                state = NO_MORE_SPLITS;
            }
        }

        public SplitsState getState()
        {
            return state;
        }

        public void addSplit(ScheduledSplit scheduledSplit)
        {
            checkState(state == ADDING_SPLITS);
            splits.add(scheduledSplit);
        }

        public Set<ScheduledSplit> removeAllSplits()
        {
            checkState(state == ADDING_SPLITS || state == NO_MORE_SPLITS);
            Set<ScheduledSplit> result = splits;
            splits = new HashSet<>();
            return result;
        }

        public void markAsCleanedUp()
        {
            checkState(splits.isEmpty());
            checkState(state == NO_MORE_SPLITS);
            state = FINISHED;
        }
    }

    enum SplitsState
    {
        ADDING_SPLITS,
        // All splits have been received from scheduler.
        // No more splits will be added to the pendingSplits set.
        NO_MORE_SPLITS,
        // All splits has been turned into DriverSplitRunner.
        FINISHED,
    }

    private class DriverSplitRunnerFactory
    {
        private final DriverFactory driverFactory;
        private final PipelineContext pipelineContext;
        private boolean closed;

        private DriverSplitRunnerFactory(DriverFactory driverFactory, boolean partitioned)
        {
            this.driverFactory = driverFactory;
            this.pipelineContext = taskContext.addPipelineContext(driverFactory.getPipelineId(), driverFactory.isInputDriver(), driverFactory.isOutputDriver(), partitioned);
        }

        // TODO: split this method into two: createPartitionedDriverRunner and createUnpartitionedDriverRunner.
        // The former will take two arguments, and the latter will take one. This will simplify the signature quite a bit.
        public DriverSplitRunner createDriverRunner(@Nullable ScheduledSplit partitionedSplit)
        {
            status.incrementPendingCreation(pipelineContext.getPipelineId());
            // create driver context immediately so the driver existence is recorded in the stats
            // the number of drivers is used to balance work across nodes
            long splitWeight = partitionedSplit == null ? 0 : partitionedSplit.getSplit().getSplitWeight().getRawValue();
            DriverContext driverContext = pipelineContext.addDriverContext(splitWeight);
            return new DriverSplitRunner(this, driverContext, partitionedSplit);
        }

        public Driver createDriver(DriverContext driverContext, @Nullable ScheduledSplit partitionedSplit)
        {
            Driver driver = driverFactory.createDriver(driverContext);

            // record driver so other threads add unpartitioned sources can see the driver
            // NOTE: this MUST be done before reading unpartitionedSources, so we see a consistent view of the unpartitioned sources
            drivers.add(new WeakReference<>(driver));

            if (partitionedSplit != null) {
                // TableScanOperator requires partitioned split to be added before the first call to process
                driver.updateSplitAssignment(new SplitAssignment(partitionedSplit.getPlanNodeId(), ImmutableSet.of(partitionedSplit), true));
            }

            // add unpartitioned sources
            Optional<PlanNodeId> sourceId = driver.getSourceId();
            if (sourceId.isPresent()) {
                SplitAssignment splitAssignment = unpartitionedSplitAssignments.get(sourceId.get());
                if (splitAssignment != null) {
                    driver.updateSplitAssignment(splitAssignment);
                }
            }

            status.decrementPendingCreation(pipelineContext.getPipelineId());
            closeDriverFactoryIfFullyCreated();

            return driver;
        }

        public void noMoreDriverRunner()
        {
            status.setNoMoreDriverRunner(pipelineContext.getPipelineId());
            closeDriverFactoryIfFullyCreated();
        }

        public boolean isNoMoreDriverRunner()
        {
            return status.isNoMoreDriverRunners(pipelineContext.getPipelineId());
        }

        public void closeDriverFactoryIfFullyCreated()
        {
            if (closed) {
                return;
            }
            if (!isNoMoreDriverRunner() || status.getPendingCreation(pipelineContext.getPipelineId()) != 0) {
                return;
            }
            driverFactory.noMoreDrivers();
            closed = true;
        }

        public OptionalInt getDriverInstances()
        {
            return driverFactory.getDriverInstances();
        }

        public void splitsAdded(int count, long weightSum)
        {
            pipelineContext.splitsAdded(count, weightSum);
        }
    }

    private static class DriverSplitRunner
            implements SplitRunner
    {
        private final DriverSplitRunnerFactory driverSplitRunnerFactory;
        private final DriverContext driverContext;

        @GuardedBy("this")
        private boolean closed;

        @Nullable
        private final ScheduledSplit partitionedSplit;

        @GuardedBy("this")
        private Driver driver;

        private DriverSplitRunner(DriverSplitRunnerFactory driverSplitRunnerFactory, DriverContext driverContext, @Nullable ScheduledSplit partitionedSplit)
        {
            this.driverSplitRunnerFactory = requireNonNull(driverSplitRunnerFactory, "driverSplitRunnerFactory is null");
            this.driverContext = requireNonNull(driverContext, "driverContext is null");
            this.partitionedSplit = partitionedSplit;
        }

        public synchronized DriverContext getDriverContext()
        {
            if (driver == null) {
                return null;
            }
            return driver.getDriverContext();
        }

        @Override
        public synchronized boolean isFinished()
        {
            if (closed) {
                return true;
            }

            return driver != null && driver.isFinished();
        }

        @Override
        public ListenableFuture<Void> processFor(Duration duration)
        {
            Driver driver;
            synchronized (this) {
                // if close() was called before we get here, there's not point in even creating the driver
                if (closed) {
                    return immediateVoidFuture();
                }

                if (this.driver == null) {
                    this.driver = driverSplitRunnerFactory.createDriver(driverContext, partitionedSplit);
                }

                driver = this.driver;
            }

            return driver.processForDuration(duration);
        }

        @Override
        public String getInfo()
        {
            return (partitionedSplit == null) ? "" : partitionedSplit.getSplit().getInfo().toString();
        }

        @Override
        public void close()
        {
            Driver driver;
            synchronized (this) {
                closed = true;
                driver = this.driver;
            }

            if (driver != null) {
                driver.close();
            }
        }
    }

    private static final class CheckTaskCompletionOnBufferFinish
            implements StateChangeListener<BufferState>
    {
        private final WeakReference<SqlTaskExecution> sqlTaskExecutionReference;

        public CheckTaskCompletionOnBufferFinish(SqlTaskExecution sqlTaskExecution)
        {
            // we are only checking for completion of the task, so don't hold up GC if the task is dead
            this.sqlTaskExecutionReference = new WeakReference<>(sqlTaskExecution);
        }

        @Override
        public void stateChanged(BufferState newState)
        {
            if (newState.isTerminal()) {
                SqlTaskExecution sqlTaskExecution = sqlTaskExecutionReference.get();
                if (sqlTaskExecution != null) {
                    sqlTaskExecution.checkTaskCompletion();
                }
            }
        }
    }

    @ThreadSafe
    private static class Status
    {
        // no more driver runner: true if no more DriverSplitRunners will be created.
        // pending creation: number of created DriverSplitRunners that haven't created underlying Driver.
        // remaining driver: number of created Drivers that haven't yet finished.

        @GuardedBy("this")
        private final int pipelineWithTaskLifeCycleCount;

        // For these 3 perX fields, they are populated lazily. If enumeration operations on the
        // map can lead to side effects, no new entries can be created after such enumeration has
        // happened. Otherwise, the order of entry creation and the enumeration operation will
        // lead to different outcome.
        @GuardedBy("this")
        private final Map<Integer, PerPipelineStatus> perPipeline;
        @GuardedBy("this")
        int pipelinesWithNoMoreDriverRunners;

        @GuardedBy("this")
        private int overallRemainingDriver;

        public Status(Set<Integer> pipelineIds)
        {
            int pipelineWithTaskLifeCycleCount = 0;
            ImmutableMap.Builder<Integer, PerPipelineStatus> perPipeline = ImmutableMap.builder();
            for (int pipelineId : pipelineIds) {
                perPipeline.put(pipelineId, new PerPipelineStatus());
                pipelineWithTaskLifeCycleCount++;
            }
            this.pipelineWithTaskLifeCycleCount = pipelineWithTaskLifeCycleCount;
            this.perPipeline = perPipeline.buildOrThrow();
        }

        public synchronized void setNoMoreDriverRunner(int pipelineId)
        {
            per(pipelineId).noMoreDriverRunners = true;
            pipelinesWithNoMoreDriverRunners++;
        }

        public synchronized void incrementPendingCreation(int pipelineId)
        {
            per(pipelineId).pendingCreation++;
        }

        public synchronized void decrementPendingCreation(int pipelineId)
        {
            per(pipelineId).pendingCreation--;
        }

        public synchronized void incrementRemainingDriver()
        {
            checkState(!(pipelinesWithNoMoreDriverRunners == pipelineWithTaskLifeCycleCount), "Cannot increment remainingDriver. NoMoreSplits is set.");
            overallRemainingDriver++;
        }

        public synchronized void decrementRemainingDriver()
        {
            checkState(overallRemainingDriver > 0, "Cannot decrement remainingDriver. Value is 0.");
            overallRemainingDriver--;
        }

        public synchronized int getPendingCreation(int pipelineId)
        {
            return per(pipelineId).pendingCreation;
        }

        public synchronized int getRemainingDriver()
        {
            return overallRemainingDriver;
        }

        public synchronized boolean isNoMoreDriverRunners(int pipelineId)
        {
            return per(pipelineId).noMoreDriverRunners;
        }

        @GuardedBy("this")
        private PerPipelineStatus per(int pipelineId)
        {
            return perPipeline.get(pipelineId);
        }
    }

    private static class PerPipelineStatus
    {
        int pendingCreation;
        boolean noMoreDriverRunners;
    }
}
