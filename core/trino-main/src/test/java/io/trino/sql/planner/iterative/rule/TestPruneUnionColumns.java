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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.Assignments;
import org.testng.annotations.Test;

import static io.trino.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.trino.sql.planner.assertions.PlanMatchPattern.union;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;

public class TestPruneUnionColumns
        extends BaseRuleTest
{
    @Test
    public void testPruneInputColumns()
    {
        tester().assertThat(new PruneUnionColumns())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    return p.project(
                            Assignments.of(),
                            p.union(
                                    ImmutableListMultimap.of(a, b, a, c),
                                    ImmutableList.of(
                                            p.values(b),
                                            p.values(c))));
                })
                .matches(
                        strictProject(
                                ImmutableMap.of(),
                                union(
                                        values("b"),
                                        values("c"))
                                        .withExactOutputs()));
    }

    @Test
    public void testAllOutputsReferenced()
    {
        tester().assertThat(new PruneUnionColumns())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    return p.project(
                            Assignments.identity(a),
                            p.enforceSingleRow(p.values(a)));
                })
                .doesNotFire();
    }
}
