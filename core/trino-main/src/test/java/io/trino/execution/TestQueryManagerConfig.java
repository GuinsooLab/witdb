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

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.operator.RetryPolicy;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.KILOBYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.execution.QueryManagerConfig.AVAILABLE_HEAP_MEMORY;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestQueryManagerConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(QueryManagerConfig.class)
                .setMinQueryExpireAge(new Duration(15, MINUTES))
                .setMaxQueryHistory(100)
                .setMaxQueryLength(1_000_000)
                .setMaxStageCount(150)
                .setStageCountWarningThreshold(50)
                .setClientTimeout(new Duration(5, MINUTES))
                .setScheduleSplitBatchSize(1000)
                .setMinScheduleSplitBatchSize(100)
                .setMaxConcurrentQueries(1000)
                .setMaxQueuedQueries(5000)
                .setMaxHashPartitionCount(100)
                .setMinHashPartitionCount(4)
                .setQueryManagerExecutorPoolSize(5)
                .setQueryExecutorPoolSize(1000)
                .setMaxStateMachineCallbackThreads(5)
                .setRemoteTaskMinErrorDuration(new Duration(5, MINUTES))
                .setRemoteTaskMaxErrorDuration(new Duration(5, MINUTES))
                .setRemoteTaskMaxCallbackThreads(1000)
                .setQueryExecutionPolicy("phased")
                .setQueryMaxRunTime(new Duration(100, DAYS))
                .setQueryMaxExecutionTime(new Duration(100, DAYS))
                .setQueryMaxPlanningTime(new Duration(10, MINUTES))
                .setQueryMaxCpuTime(new Duration(1_000_000_000, DAYS))
                .setQueryMaxScanPhysicalBytes(null)
                .setRequiredWorkers(1)
                .setRequiredWorkersMaxWait(new Duration(5, MINUTES))
                .setRetryPolicy(RetryPolicy.NONE)
                .setQueryRetryAttempts(4)
                .setTaskRetryAttemptsPerTask(4)
                .setRetryInitialDelay(new Duration(10, SECONDS))
                .setRetryMaxDelay(new Duration(1, MINUTES))
                .setRetryDelayScaleFactor(2.0)
                .setMaxTasksWaitingForExecutionPerQuery(10)
                .setMaxTasksWaitingForNodePerStage(5)
                .setEnabledAdaptiveTaskRequestSize(true)
                .setMaxRemoteTaskRequestSize(DataSize.of(8, DataSize.Unit.MEGABYTE))
                .setRemoteTaskRequestSizeHeadroom(DataSize.of(2, DataSize.Unit.MEGABYTE))
                .setRemoteTaskGuaranteedSplitPerTask(3)
                .setFaultTolerantExecutionTargetTaskInputSize(DataSize.of(4, GIGABYTE))
                .setFaultTolerantExecutionTargetTaskSplitCount(64)
                .setFaultTolerantExecutionMaxTaskSplitCount(256)
                .setFaultTolerantExecutionTaskDescriptorStorageMaxMemory(DataSize.ofBytes(Math.round(AVAILABLE_HEAP_MEMORY * 0.15)))
                .setFaultTolerantExecutionPartitionCount(50)
                .setFaultTolerantExecutionForcePreferredWritePartitioningEnabled(true));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("query.client.timeout", "10s")
                .put("query.min-expire-age", "30s")
                .put("query.max-history", "10")
                .put("query.max-length", "10000")
                .put("query.max-stage-count", "12345")
                .put("query.stage-count-warning-threshold", "12300")
                .put("query.schedule-split-batch-size", "99")
                .put("query.min-schedule-split-batch-size", "9")
                .put("query.max-concurrent-queries", "10")
                .put("query.max-queued-queries", "15")
                .put("query.max-hash-partition-count", "16")
                .put("query.min-hash-partition-count", "2")
                .put("query.manager-executor-pool-size", "11")
                .put("query.executor-pool-size", "111")
                .put("query.max-state-machine-callback-threads", "112")
                .put("query.remote-task.min-error-duration", "30s")
                .put("query.remote-task.max-error-duration", "60s")
                .put("query.remote-task.max-callback-threads", "10")
                .put("query.execution-policy", "foo-bar-execution-policy")
                .put("query.max-run-time", "2h")
                .put("query.max-execution-time", "3h")
                .put("query.max-planning-time", "1h")
                .put("query.max-cpu-time", "2d")
                .put("query.max-scan-physical-bytes", "1kB")
                .put("query-manager.required-workers", "333")
                .put("query-manager.required-workers-max-wait", "33m")
                .put("retry-policy", "QUERY")
                .put("query-retry-attempts", "0")
                .put("task-retry-attempts-per-task", "9")
                .put("retry-initial-delay", "1m")
                .put("retry-max-delay", "1h")
                .put("retry-delay-scale-factor", "2.3")
                .put("max-tasks-waiting-for-execution-per-query", "22")
                .put("max-tasks-waiting-for-node-per-stage", "3")
                .put("query.remote-task.enable-adaptive-request-size", "false")
                .put("query.remote-task.max-request-size", "10MB")
                .put("query.remote-task.request-size-headroom", "1MB")
                .put("query.remote-task.guaranteed-splits-per-task", "5")
                .put("fault-tolerant-execution-target-task-input-size", "222MB")
                .put("fault-tolerant-execution-target-task-split-count", "3")
                .put("fault-tolerant-execution-max-task-split-count", "22")
                .put("fault-tolerant-execution-task-descriptor-storage-max-memory", "3GB")
                .put("fault-tolerant-execution-partition-count", "123")
                .put("experimental.fault-tolerant-execution-force-preferred-write-partitioning-enabled", "false")
                .buildOrThrow();

        QueryManagerConfig expected = new QueryManagerConfig()
                .setMinQueryExpireAge(new Duration(30, SECONDS))
                .setMaxQueryHistory(10)
                .setMaxQueryLength(10000)
                .setMaxStageCount(12345)
                .setStageCountWarningThreshold(12300)
                .setClientTimeout(new Duration(10, SECONDS))
                .setScheduleSplitBatchSize(99)
                .setMinScheduleSplitBatchSize(9)
                .setMaxConcurrentQueries(10)
                .setMaxQueuedQueries(15)
                .setMaxHashPartitionCount(16)
                .setMinHashPartitionCount(2)
                .setQueryManagerExecutorPoolSize(11)
                .setQueryExecutorPoolSize(111)
                .setMaxStateMachineCallbackThreads(112)
                .setRemoteTaskMinErrorDuration(new Duration(60, SECONDS))
                .setRemoteTaskMaxErrorDuration(new Duration(60, SECONDS))
                .setRemoteTaskMaxCallbackThreads(10)
                .setQueryExecutionPolicy("foo-bar-execution-policy")
                .setQueryMaxRunTime(new Duration(2, HOURS))
                .setQueryMaxExecutionTime(new Duration(3, HOURS))
                .setQueryMaxPlanningTime(new Duration(1, HOURS))
                .setQueryMaxCpuTime(new Duration(2, DAYS))
                .setQueryMaxScanPhysicalBytes(DataSize.of(1, KILOBYTE))
                .setRequiredWorkers(333)
                .setRequiredWorkersMaxWait(new Duration(33, MINUTES))
                .setRetryPolicy(RetryPolicy.QUERY)
                .setQueryRetryAttempts(0)
                .setTaskRetryAttemptsPerTask(9)
                .setRetryInitialDelay(new Duration(1, MINUTES))
                .setRetryMaxDelay(new Duration(1, HOURS))
                .setRetryDelayScaleFactor(2.3)
                .setMaxTasksWaitingForExecutionPerQuery(22)
                .setMaxTasksWaitingForNodePerStage(3)
                .setEnabledAdaptiveTaskRequestSize(false)
                .setMaxRemoteTaskRequestSize(DataSize.of(10, DataSize.Unit.MEGABYTE))
                .setRemoteTaskRequestSizeHeadroom(DataSize.of(1, DataSize.Unit.MEGABYTE))
                .setRemoteTaskGuaranteedSplitPerTask(5)
                .setFaultTolerantExecutionTargetTaskInputSize(DataSize.of(222, MEGABYTE))
                .setFaultTolerantExecutionTargetTaskSplitCount(3)
                .setFaultTolerantExecutionMaxTaskSplitCount(22)
                .setFaultTolerantExecutionTaskDescriptorStorageMaxMemory(DataSize.of(3, GIGABYTE))
                .setFaultTolerantExecutionPartitionCount(123)
                .setFaultTolerantExecutionForcePreferredWritePartitioningEnabled(false);

        assertFullMapping(properties, expected);
    }
}
