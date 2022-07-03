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
package io.trino.testing;

import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.sql.planner.Plan;
import io.trino.sql.planner.planprinter.PlanPrinter;

import java.util.function.Function;

import static io.trino.sql.planner.LogicalPlanner.Stage.OPTIMIZED_AND_VALIDATED;
import static org.testng.Assert.assertEquals;

public class PlanDeterminismChecker
{
    private static final int MINIMUM_SUBSEQUENT_SAME_PLANS = 10;

    private final LocalQueryRunner localQueryRunner;
    private final Function<String, String> planEquivalenceFunction;

    public PlanDeterminismChecker(LocalQueryRunner localQueryRunner)
    {
        this(localQueryRunner, Function.identity());
    }

    public PlanDeterminismChecker(LocalQueryRunner localQueryRunner, Function<String, String> planEquivalenceFunction)
    {
        this.localQueryRunner = localQueryRunner;
        this.planEquivalenceFunction = planEquivalenceFunction;
    }

    public void checkPlanIsDeterministic(String sql)
    {
        checkPlanIsDeterministic(localQueryRunner.getDefaultSession(), sql);
    }

    public void checkPlanIsDeterministic(Session session, String sql)
    {
        String previous = planEquivalenceFunction.apply(getPlanText(session, sql));
        for (int attempt = 1; attempt < MINIMUM_SUBSEQUENT_SAME_PLANS; attempt++) {
            String current = planEquivalenceFunction.apply(getPlanText(session, sql));
            assertEquals(previous, current);
        }
    }

    private String getPlanText(Session session, String sql)
    {
        return localQueryRunner.inTransaction(session, transactionSession -> {
            Plan plan = localQueryRunner.createPlan(transactionSession, sql, OPTIMIZED_AND_VALIDATED, WarningCollector.NOOP);
            return PlanPrinter.textLogicalPlan(
                    plan.getRoot(),
                    plan.getTypes(),
                    localQueryRunner.getMetadata(),
                    localQueryRunner.getFunctionManager(),
                    plan.getStatsAndCosts(),
                    transactionSession,
                    0,
                    false);
        });
    }
}
