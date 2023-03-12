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
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.planner.iterative.IterativeOptimizer;
import io.trino.sql.planner.iterative.Lookup;
import io.trino.sql.planner.plan.PlanNode;

public interface StatsCalculator
{
    /**
     * Calculate stats for the {@code node}.
     *
     * @param node The node to compute stats for.
     * @param sourceStats The stats provider for any child nodes' stats, if needed to compute stats for the {@code node}
     * @param lookup Lookup to be used when resolving source nodes, allowing stats calculation to work within {@link IterativeOptimizer}
     * @param types The type provider for all symbols in the scope.
     * @param tableStatsProvider The table stats provider.
     */
    PlanNodeStatsEstimate calculateStats(
            PlanNode node,
            StatsProvider sourceStats,
            Lookup lookup,
            Session session,
            TypeProvider types,
            TableStatsProvider tableStatsProvider);

    static StatsCalculator noopStatsCalculator()
    {
        return (node, sourceStats, lookup, ignore, types, tableStatsProvider) -> PlanNodeStatsEstimate.unknown();
    }
}
