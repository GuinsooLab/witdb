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
package io.trino.cost;

import io.trino.Session;
import io.trino.cost.ComposableStatsCalculator.Rule;
import io.trino.matching.Pattern;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.planner.iterative.Lookup;
import io.trino.sql.planner.plan.OutputNode;

import java.util.Optional;

import static io.trino.sql.planner.plan.Patterns.output;

public class OutputStatsRule
        implements Rule<OutputNode>
{
    private static final Pattern<OutputNode> PATTERN = output();

    @Override
    public Pattern<OutputNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(OutputNode node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types)
    {
        return Optional.of(sourceStats.getStats(node.getSource()));
    }
}
