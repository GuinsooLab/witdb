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
package io.trino.sql.planner.iterative.rule;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.ProjectNode;
import org.testng.annotations.Test;

import java.util.function.Predicate;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.sort;
import static io.trino.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.trino.sql.planner.assertions.PlanMatchPattern.topN;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.tree.SortItem.NullOrdering.FIRST;
import static io.trino.sql.tree.SortItem.Ordering.ASCENDING;

public class TestPruneTopNColumns
        extends BaseRuleTest
{
    private static final long COUNT = 10;

    @Test
    public void testNotAllInputsReferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, symbol -> symbol.getName().equals("b")))
                .matches(
                        strictProject(
                                ImmutableMap.of("b", expression("b")),
                                topN(
                                        COUNT,
                                        ImmutableList.of(sort("b", ASCENDING, FIRST)),
                                        strictProject(
                                                ImmutableMap.of("b", expression("b")),
                                                values("a", "b")))));
    }

    @Test
    public void testAllInputsReferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, symbol -> symbol.getName().equals("a")))
                .doesNotFire();
    }

    @Test
    public void testAllOutputsReferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, Predicates.alwaysTrue()))
                .doesNotFire();
    }

    private ProjectNode buildProjectedTopN(PlanBuilder planBuilder, Predicate<Symbol> projectionTopN)
    {
        Symbol a = planBuilder.symbol("a");
        Symbol b = planBuilder.symbol("b");
        return planBuilder.project(
                Assignments.identity(ImmutableList.of(a, b).stream().filter(projectionTopN).collect(toImmutableSet())),
                planBuilder.topN(
                        COUNT,
                        ImmutableList.of(b),
                        planBuilder.values(a, b)));
    }
}
