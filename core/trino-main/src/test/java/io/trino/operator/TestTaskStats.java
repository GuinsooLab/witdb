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
import com.google.common.collect.ImmutableSet;
import io.airlift.json.JsonCodec;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.operator.TestPipelineStats.assertExpectedPipelineStats;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertEquals;

public class TestTaskStats
{
    public static final TaskStats EXPECTED = new TaskStats(
            new DateTime(1),
            new DateTime(2),
            new DateTime(100),
            new DateTime(101),
            new DateTime(3),
            new Duration(4, NANOSECONDS),
            new Duration(5, NANOSECONDS),

            6,
            7,
            5,
            28L,
            8,
            6,
            29L,
            24,
            10,

            11.0,
            DataSize.ofBytes(12),
            DataSize.ofBytes(120),
            DataSize.ofBytes(13),
            new Duration(15, NANOSECONDS),
            new Duration(16, NANOSECONDS),
            new Duration(18, NANOSECONDS),
            false,
            ImmutableSet.of(),

            DataSize.ofBytes(191),
            201,
            new Duration(15, NANOSECONDS),

            DataSize.ofBytes(192),
            202,

            DataSize.ofBytes(19),
            20,

            DataSize.ofBytes(21),
            22,

            new Duration(271, NANOSECONDS),

            DataSize.ofBytes(23),
            24,

            new Duration(272, NANOSECONDS),

            DataSize.ofBytes(25),
            Optional.of(2),

            26,
            new Duration(27, NANOSECONDS),

            ImmutableList.of(TestPipelineStats.EXPECTED));

    @Test
    public void testJson()
    {
        JsonCodec<TaskStats> codec = JsonCodec.jsonCodec(TaskStats.class);

        String json = codec.toJson(EXPECTED);
        TaskStats actual = codec.fromJson(json);

        assertExpectedTaskStats(actual);
    }

    public static void assertExpectedTaskStats(TaskStats actual)
    {
        assertEquals(actual.getCreateTime(), new DateTime(1, UTC));
        assertEquals(actual.getFirstStartTime(), new DateTime(2, UTC));
        assertEquals(actual.getLastStartTime(), new DateTime(100, UTC));
        assertEquals(actual.getLastEndTime(), new DateTime(101, UTC));
        assertEquals(actual.getEndTime(), new DateTime(3, UTC));
        assertEquals(actual.getElapsedTime(), new Duration(4, NANOSECONDS));
        assertEquals(actual.getQueuedTime(), new Duration(5, NANOSECONDS));

        assertEquals(actual.getTotalDrivers(), 6);
        assertEquals(actual.getQueuedDrivers(), 7);
        assertEquals(actual.getQueuedPartitionedDrivers(), 5);
        assertEquals(actual.getQueuedPartitionedSplitsWeight(), 28L);
        assertEquals(actual.getRunningDrivers(), 8);
        assertEquals(actual.getRunningPartitionedDrivers(), 6);
        assertEquals(actual.getRunningPartitionedSplitsWeight(), 29L);
        assertEquals(actual.getBlockedDrivers(), 24);
        assertEquals(actual.getCompletedDrivers(), 10);

        assertEquals(actual.getCumulativeUserMemory(), 11.0);
        assertEquals(actual.getUserMemoryReservation(), DataSize.ofBytes(12));
        assertEquals(actual.getPeakUserMemoryReservation(), DataSize.ofBytes(120));
        assertEquals(actual.getRevocableMemoryReservation(), DataSize.ofBytes(13));

        assertEquals(actual.getTotalScheduledTime(), new Duration(15, NANOSECONDS));
        assertEquals(actual.getTotalCpuTime(), new Duration(16, NANOSECONDS));
        assertEquals(actual.getTotalBlockedTime(), new Duration(18, NANOSECONDS));

        assertEquals(actual.getPhysicalInputDataSize(), DataSize.ofBytes(191));
        assertEquals(actual.getPhysicalInputPositions(), 201);
        assertEquals(actual.getPhysicalInputReadTime(), new Duration(15, NANOSECONDS));
        assertEquals(actual.getInternalNetworkInputDataSize(), DataSize.ofBytes(192));
        assertEquals(actual.getInternalNetworkInputPositions(), 202);

        assertEquals(actual.getRawInputDataSize(), DataSize.ofBytes(19));
        assertEquals(actual.getRawInputPositions(), 20);

        assertEquals(actual.getProcessedInputDataSize(), DataSize.ofBytes(21));
        assertEquals(actual.getProcessedInputPositions(), 22);

        assertEquals(actual.getInputBlockedTime(), new Duration(271, NANOSECONDS));

        assertEquals(actual.getOutputDataSize(), DataSize.ofBytes(23));
        assertEquals(actual.getOutputPositions(), 24);

        assertEquals(actual.getOutputBlockedTime(), new Duration(272, NANOSECONDS));

        assertEquals(actual.getPhysicalWrittenDataSize(), DataSize.ofBytes(25));

        assertEquals(actual.getPipelines().size(), 1);
        assertExpectedPipelineStats(actual.getPipelines().get(0));
    }
}
