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

import com.google.common.collect.ImmutableMap;
import io.trino.metadata.Metadata;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import org.testng.annotations.Test;

import static io.trino.metadata.MetadataManager.createTestMetadataManager;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.iterative.rule.test.PlanBuilder.expression;

public class TestMergeFilters
        extends BaseRuleTest
{
    private final Metadata metadata = createTestMetadataManager();

    @Test
    public void test()
    {
        tester().assertThat(new MergeFilters(metadata))
                .on(p ->
                        p.filter(expression("b > 44"),
                                p.filter(expression("a < 42"),
                                        p.values(p.symbol("a"), p.symbol("b")))))
                .matches(filter("(a < 42) AND (b > 44)", values(ImmutableMap.of("a", 0, "b", 1))));
    }
}
