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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.ThreadPoolExecutorMBean;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.airlift.stats.CounterStat;
import io.airlift.stats.GcMonitor;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.collect.cache.NonEvictableLoadingCache;
import io.trino.event.SplitMonitor;
import io.trino.exchange.ExchangeManagerRegistry;
import io.trino.execution.DynamicFiltersCollector.VersionedDynamicFilterDomains;
import io.trino.execution.StateMachine.StateChangeListener;
import io.trino.execution.buffer.BufferResult;
import io.trino.execution.buffer.OutputBuffers;
import io.trino.execution.buffer.OutputBuffers.OutputBufferId;
import io.trino.execution.executor.TaskExecutor;
import io.trino.memory.LocalMemoryManager;
import io.trino.memory.NodeMemoryConfig;
import io.trino.memory.QueryContext;
import io.trino.operator.RetryPolicy;
import io.trino.spi.QueryId;
import io.trino.spi.TrinoException;
import io.trino.spi.VersionEmbedder;
import io.trino.spi.predicate.Domain;
import io.trino.spiller.LocalSpillManager;
import io.trino.spiller.NodeSpillConfig;
import io.trino.sql.planner.LocalExecutionPlanner;
import io.trino.sql.planner.PlanFragment;
import io.trino.sql.planner.plan.DynamicFilterId;
import org.joda.time.DateTime;
import org.weakref.jmx.Flatten;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.trino.SystemSessionProperties.getQueryMaxMemoryPerNode;
import static io.trino.SystemSessionProperties.getRetryPolicy;
import static io.trino.SystemSessionProperties.resourceOvercommit;
import static io.trino.collect.cache.SafeCaches.buildNonEvictableCache;
import static io.trino.execution.SqlTask.createSqlTask;
import static io.trino.operator.RetryPolicy.TASK;
import static io.trino.spi.StandardErrorCode.ABANDONED_TASK;
import static io.trino.spi.StandardErrorCode.SERVER_SHUTTING_DOWN;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;

public class SqlTaskManager
        implements Closeable
{
    private static final Logger log = Logger.get(SqlTaskManager.class);

    private final VersionEmbedder versionEmbedder;
    private final ExecutorService taskNotificationExecutor;
    private final ThreadPoolExecutorMBean taskNotificationExecutorMBean;

    private final ScheduledExecutorService taskManagementExecutor;
    private final ScheduledExecutorService driverYieldExecutor;

    private final Duration infoCacheTime;
    private final Duration clientTimeout;

    private final NonEvictableLoadingCache<QueryId, QueryContext> queryContexts;
    private final NonEvictableLoadingCache<TaskId, SqlTask> tasks;

    private final SqlTaskIoStats cachedStats = new SqlTaskIoStats();
    private final SqlTaskIoStats finishedTaskStats = new SqlTaskIoStats();

    private final long queryMaxMemoryPerNode;

    private final CounterStat failedTasks = new CounterStat();

    @Inject
    public SqlTaskManager(
            VersionEmbedder versionEmbedder,
            LocalExecutionPlanner planner,
            LocationFactory locationFactory,
            TaskExecutor taskExecutor,
            SplitMonitor splitMonitor,
            NodeInfo nodeInfo,
            LocalMemoryManager localMemoryManager,
            TaskManagementExecutor taskManagementExecutor,
            TaskManagerConfig config,
            NodeMemoryConfig nodeMemoryConfig,
            LocalSpillManager localSpillManager,
            NodeSpillConfig nodeSpillConfig,
            GcMonitor gcMonitor,
            ExchangeManagerRegistry exchangeManagerRegistry)
    {
        requireNonNull(nodeInfo, "nodeInfo is null");
        requireNonNull(config, "config is null");
        infoCacheTime = config.getInfoMaxAge();
        clientTimeout = config.getClientTimeout();

        DataSize maxBufferSize = config.getSinkMaxBufferSize();
        DataSize maxBroadcastBufferSize = config.getSinkMaxBroadcastBufferSize();

        this.versionEmbedder = requireNonNull(versionEmbedder, "versionEmbedder is null");
        taskNotificationExecutor = newFixedThreadPool(config.getTaskNotificationThreads(), threadsNamed("task-notification-%s"));
        taskNotificationExecutorMBean = new ThreadPoolExecutorMBean((ThreadPoolExecutor) taskNotificationExecutor);

        this.taskManagementExecutor = requireNonNull(taskManagementExecutor, "taskManagementExecutor cannot be null").getExecutor();
        this.driverYieldExecutor = newScheduledThreadPool(config.getTaskYieldThreads(), threadsNamed("task-yield-%s"));

        SqlTaskExecutionFactory sqlTaskExecutionFactory = new SqlTaskExecutionFactory(taskNotificationExecutor, taskExecutor, planner, splitMonitor, config);

        DataSize maxQueryMemoryPerNode = nodeMemoryConfig.getMaxQueryMemoryPerNode();
        DataSize maxQuerySpillPerNode = nodeSpillConfig.getQueryMaxSpillPerNode();

        queryMaxMemoryPerNode = maxQueryMemoryPerNode.toBytes();

        queryContexts = buildNonEvictableCache(CacheBuilder.newBuilder().weakValues(), CacheLoader.from(
                queryId -> createQueryContext(queryId, localMemoryManager, localSpillManager, gcMonitor, maxQueryMemoryPerNode, maxQuerySpillPerNode)));

        tasks = buildNonEvictableCache(CacheBuilder.newBuilder(), CacheLoader.from(
                taskId -> createSqlTask(
                        taskId,
                        locationFactory.createLocalTaskLocation(taskId),
                        nodeInfo.getNodeId(),
                        queryContexts.getUnchecked(taskId.getQueryId()),
                        sqlTaskExecutionFactory,
                        taskNotificationExecutor,
                        sqlTask -> finishedTaskStats.merge(sqlTask.getIoStats()),
                        maxBufferSize,
                        maxBroadcastBufferSize,
                        requireNonNull(exchangeManagerRegistry, "exchangeManagerRegistry is null"),
                        failedTasks)));
    }

    private QueryContext createQueryContext(
            QueryId queryId,
            LocalMemoryManager localMemoryManager,
            LocalSpillManager localSpillManager,
            GcMonitor gcMonitor,
            DataSize maxQueryUserMemoryPerNode,
            DataSize maxQuerySpillPerNode)
    {
        return new QueryContext(
                queryId,
                maxQueryUserMemoryPerNode,
                localMemoryManager.getMemoryPool(),
                gcMonitor,
                taskNotificationExecutor,
                driverYieldExecutor,
                maxQuerySpillPerNode,
                localSpillManager.getSpillSpaceTracker());
    }

    @PostConstruct
    public void start()
    {
        taskManagementExecutor.scheduleWithFixedDelay(() -> {
            try {
                removeOldTasks();
            }
            catch (Throwable e) {
                log.warn(e, "Error removing old tasks");
            }
            try {
                failAbandonedTasks();
            }
            catch (Throwable e) {
                log.warn(e, "Error canceling abandoned tasks");
            }
        }, 200, 200, TimeUnit.MILLISECONDS);

        taskManagementExecutor.scheduleWithFixedDelay(() -> {
            try {
                updateStats();
            }
            catch (Throwable e) {
                log.warn(e, "Error updating stats");
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    @Override
    public void close()
    {
        boolean taskCanceled = false;
        for (SqlTask task : tasks.asMap().values()) {
            if (task.getTaskState().isDone()) {
                continue;
            }
            task.failed(new TrinoException(SERVER_SHUTTING_DOWN, format("Server is shutting down. Task %s has been canceled", task.getTaskId())));
            taskCanceled = true;
        }
        if (taskCanceled) {
            try {
                TimeUnit.SECONDS.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        taskNotificationExecutor.shutdownNow();
    }

    @Managed
    @Flatten
    public SqlTaskIoStats getIoStats()
    {
        return cachedStats;
    }

    @Managed(description = "Task notification executor")
    @Nested
    public ThreadPoolExecutorMBean getTaskNotificationExecutor()
    {
        return taskNotificationExecutorMBean;
    }

    @Managed(description = "Failed tasks counter")
    @Nested
    public CounterStat getFailedTasks()
    {
        return failedTasks;
    }

    public List<SqlTask> getAllTasks()
    {
        return ImmutableList.copyOf(tasks.asMap().values());
    }

    /**
     * Gets all of the currently tracked tasks.  This will included
     * uninitialized, running, and completed tasks.
     */
    public List<TaskInfo> getAllTaskInfo()
    {
        return tasks.asMap().values().stream()
                .map(SqlTask::getTaskInfo)
                .collect(toImmutableList());
    }

    /**
     * Gets the info for the specified task.  If the task has not been created
     * yet, an uninitialized task is created and the info is returned.
     * <p>
     * NOTE: this design assumes that only tasks that will eventually exist are
     * queried.
     */
    public TaskInfo getTaskInfo(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.getTaskInfo();
    }

    /**
     * Gets the status for the specified task.
     */
    public TaskStatus getTaskStatus(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.getTaskStatus();
    }

    /**
     * Gets future info for the task after the state changes from
     * {@code current state}. If the task has not been created yet, an
     * uninitialized task is created and the future is returned.  If the task
     * is already in a final state, the info is returned immediately.
     * <p>
     * NOTE: this design assumes that only tasks that will eventually exist are
     * queried.
     */
    public ListenableFuture<TaskInfo> getTaskInfo(TaskId taskId, long currentVersion)
    {
        requireNonNull(taskId, "taskId is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.getTaskInfo(currentVersion);
    }

    /**
     * Gets the unique instance id of a task.  This can be used to detect a task
     * that was destroyed and recreated.
     */
    public String getTaskInstanceId(TaskId taskId)
    {
        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.getTaskInstanceId();
    }

    /**
     * Gets future status for the task after the state changes from
     * {@code current state}. If the task has not been created yet, an
     * uninitialized task is created and the future is returned.  If the task
     * is already in a final state, the status is returned immediately.
     * <p>
     * NOTE: this design assumes that only tasks that will eventually exist are
     * queried.
     */
    public ListenableFuture<TaskStatus> getTaskStatus(TaskId taskId, long currentVersion)
    {
        requireNonNull(taskId, "taskId is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.getTaskStatus(currentVersion);
    }

    public VersionedDynamicFilterDomains acknowledgeAndGetNewDynamicFilterDomains(TaskId taskId, long currentDynamicFiltersVersion)
    {
        requireNonNull(taskId, "taskId is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        sqlTask.recordHeartbeat();
        return sqlTask.acknowledgeAndGetNewDynamicFilterDomains(currentDynamicFiltersVersion);
    }

    /**
     * Updates the task plan, splitAssignments and output buffers.  If the task does not
     * already exist, it is created and then updated.
     */
    public TaskInfo updateTask(
            Session session,
            TaskId taskId,
            Optional<PlanFragment> fragment,
            List<SplitAssignment> splitAssignments,
            OutputBuffers outputBuffers,
            Map<DynamicFilterId, Domain> dynamicFilterDomains)
    {
        try {
            return versionEmbedder.embedVersion(() -> doUpdateTask(session, taskId, fragment, splitAssignments, outputBuffers, dynamicFilterDomains)).call();
        }
        catch (Exception e) {
            throwIfUnchecked(e);
            // impossible, doUpdateTask does not throw checked exceptions
            throw new RuntimeException(e);
        }
    }

    private TaskInfo doUpdateTask(
            Session session,
            TaskId taskId,
            Optional<PlanFragment> fragment,
            List<SplitAssignment> splitAssignments,
            OutputBuffers outputBuffers,
            Map<DynamicFilterId, Domain> dynamicFilterDomains)
    {
        requireNonNull(session, "session is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(fragment, "fragment is null");
        requireNonNull(splitAssignments, "splitAssignments is null");
        requireNonNull(outputBuffers, "outputBuffers is null");

        SqlTask sqlTask = tasks.getUnchecked(taskId);
        QueryContext queryContext = sqlTask.getQueryContext();
        if (!queryContext.isMemoryLimitsInitialized()) {
            RetryPolicy retryPolicy = getRetryPolicy(session);
            if (retryPolicy == TASK) {
                // Memory limit for fault tolerant queries should only be enforced by the MemoryPool.
                // LowMemoryKiller is responsible for freeing up the MemoryPool if necessary.
                queryContext.initializeMemoryLimits(false, /* unlimited */ Long.MAX_VALUE);
            }
            else {
                long sessionQueryMaxMemoryPerNode = getQueryMaxMemoryPerNode(session).toBytes();

                // Session properties are only allowed to decrease memory limits, not increase them
                queryContext.initializeMemoryLimits(
                        resourceOvercommit(session),
                        min(sessionQueryMaxMemoryPerNode, queryMaxMemoryPerNode));
            }
        }

        sqlTask.recordHeartbeat();
        return sqlTask.updateTask(session, fragment, splitAssignments, outputBuffers, dynamicFilterDomains);
    }

    /**
     * Gets results from a task either immediately or in the future.  If the
     * task or buffer has not been created yet, an uninitialized task is
     * created and a future is returned.
     * <p>
     * NOTE: this design assumes that only tasks and buffers that will
     * eventually exist are queried.
     */
    public ListenableFuture<BufferResult> getTaskResults(TaskId taskId, OutputBufferId bufferId, long startingSequenceId, DataSize maxSize)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(bufferId, "bufferId is null");
        checkArgument(startingSequenceId >= 0, "startingSequenceId is negative");
        requireNonNull(maxSize, "maxSize is null");

        return tasks.getUnchecked(taskId).getTaskResults(bufferId, startingSequenceId, maxSize);
    }

    /**
     * Acknowledges previously received results.
     */
    public void acknowledgeTaskResults(TaskId taskId, OutputBufferId bufferId, long sequenceId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(bufferId, "bufferId is null");
        checkArgument(sequenceId >= 0, "sequenceId is negative");

        tasks.getUnchecked(taskId).acknowledgeTaskResults(bufferId, sequenceId);
    }

    /**
     * Aborts a result buffer for a task.  If the task or buffer has not been
     * created yet, an uninitialized task is created and a the buffer is
     * aborted.
     * <p>
     * NOTE: this design assumes that only tasks and buffers that will
     * eventually exist are queried.
     */
    public TaskInfo destroyTaskResults(TaskId taskId, OutputBufferId bufferId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(bufferId, "bufferId is null");

        return tasks.getUnchecked(taskId).destroyTaskResults(bufferId);
    }

    /**
     * Cancels a task.  If the task does not already exist, it is created and then
     * canceled.
     */
    public TaskInfo cancelTask(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");

        return tasks.getUnchecked(taskId).cancel();
    }

    /**
     * Aborts a task.  If the task does not already exist, it is created and then
     * aborted.
     */
    public TaskInfo abortTask(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");

        return tasks.getUnchecked(taskId).abort();
    }

    /**
     * Fail a task.  If the task does not already exist, it is created and then
     * failed.
     */
    public TaskInfo failTask(TaskId taskId, Throwable failure)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(failure, "failure is null");

        return tasks.getUnchecked(taskId).failed(failure);
    }

    @VisibleForTesting
    void removeOldTasks()
    {
        DateTime oldestAllowedTask = DateTime.now().minus(infoCacheTime.toMillis());
        tasks.asMap().values().stream()
                .map(SqlTask::getTaskInfo)
                .filter(Objects::nonNull)
                .forEach(taskInfo -> {
                    TaskId taskId = taskInfo.getTaskStatus().getTaskId();
                    try {
                        DateTime endTime = taskInfo.getStats().getEndTime();
                        if (endTime != null && endTime.isBefore(oldestAllowedTask)) {
                            // The removal here is concurrency safe with respect to any concurrent loads: the cache has no expiration,
                            // the taskId is in the cache, so there mustn't be an ongoing load.
                            tasks.asMap().remove(taskId);
                        }
                    }
                    catch (RuntimeException e) {
                        log.warn(e, "Error while inspecting age of complete task %s", taskId);
                    }
                });
    }

    private void failAbandonedTasks()
    {
        DateTime now = DateTime.now();
        DateTime oldestAllowedHeartbeat = now.minus(clientTimeout.toMillis());
        for (SqlTask sqlTask : tasks.asMap().values()) {
            try {
                TaskInfo taskInfo = sqlTask.getTaskInfo();
                TaskStatus taskStatus = taskInfo.getTaskStatus();
                if (taskStatus.getState().isDone()) {
                    continue;
                }
                DateTime lastHeartbeat = taskInfo.getLastHeartbeat();
                if (lastHeartbeat != null && lastHeartbeat.isBefore(oldestAllowedHeartbeat)) {
                    log.info("Failing abandoned task %s", taskStatus.getTaskId());
                    sqlTask.failed(new TrinoException(ABANDONED_TASK, format("Task %s has not been accessed since %s: currentTime %s", taskStatus.getTaskId(), lastHeartbeat, now)));
                }
            }
            catch (RuntimeException e) {
                log.warn(e, "Error while inspecting age of task %s", sqlTask.getTaskId());
            }
        }
    }

    //
    // Jmxutils only calls nested getters once, so we are forced to maintain a single
    // instance and periodically recalculate the stats.
    //
    private void updateStats()
    {
        SqlTaskIoStats tempIoStats = new SqlTaskIoStats();
        tempIoStats.merge(finishedTaskStats);

        // there is a race here between task completion, which merges stats into
        // finishedTaskStats, and getting the stats from the task.  Since we have
        // already merged the final stats, we could miss the stats from this task
        // which would result in an under-count, but we will not get an over-count.
        tasks.asMap().values().stream()
                .filter(task -> !task.getTaskState().isDone())
                .forEach(task -> tempIoStats.merge(task.getIoStats()));

        cachedStats.resetTo(tempIoStats);
    }

    /**
     * Adds a state change listener to the specified task.
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    public void addStateChangeListener(TaskId taskId, StateChangeListener<TaskState> stateChangeListener)
    {
        requireNonNull(taskId, "taskId is null");
        tasks.getUnchecked(taskId).addStateChangeListener(stateChangeListener);
    }

    /**
     * Add a listener that notifies about failures of any source tasks for a given task
     */
    public void addSourceTaskFailureListener(TaskId taskId, TaskFailureListener listener)
    {
        tasks.getUnchecked(taskId).addSourceTaskFailureListener(listener);
    }

    /**
     * Return trace token for a given task (see Session#traceToken)
     */
    public Optional<String> getTraceToken(TaskId taskId)
    {
        return tasks.getUnchecked(taskId).getTraceToken();
    }

    @VisibleForTesting
    public QueryContext getQueryContext(QueryId queryId)

    {
        return queryContexts.getUnchecked(queryId);
    }
}
