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
import io.trino.operator.LimitOperator.LimitOperatorFactory;
import io.trino.spi.Page;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.testing.MaterializedResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.SequencePageBuilder.createSequencePage;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.TestingTaskContext.createTaskContext;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;

@Test(singleThreaded = true)
public class TestLimitOperator
{
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private DriverContext driverContext;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed(getClass().getSimpleName() + "-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed(getClass().getSimpleName() + "-scheduledExecutor-%s"));
        driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testLimitWithPageAlignment()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(2, 6)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 5);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .page(createSequencePage(ImmutableList.of(BIGINT), 3, 1))
                .page(createSequencePage(ImmutableList.of(BIGINT), 2, 4))
                .build();

        OperatorAssertion.assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }

    @Test
    public void testLimitWithBlockView()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(2, 6)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 6);

        List<Page> expected = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(1, 6)
                .build();

        OperatorAssertion.assertOperatorEquals(operatorFactory, ImmutableList.of(BIGINT), driverContext, input, expected);
    }
}
