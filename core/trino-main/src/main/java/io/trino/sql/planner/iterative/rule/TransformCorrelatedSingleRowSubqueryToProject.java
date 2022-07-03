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

import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.CorrelatedJoinNode;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.ValuesNode;

import java.util.List;

import static io.trino.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static io.trino.sql.planner.plan.Patterns.CorrelatedJoin.filter;
import static io.trino.sql.planner.plan.Patterns.correlatedJoin;
import static io.trino.sql.tree.BooleanLiteral.TRUE_LITERAL;

/**
 * This optimizer can rewrite correlated single row subquery to projection in a way described here:
 * From:
 * <pre>
 * - CorrelatedJoin (with correlation list: [A, C])
 *   - (input) plan which produces symbols: [A, B, C]
 *   - (subquery)
 *     - Project (A + C)
 *       - single row VALUES()
 * </pre>
 * to:
 * <pre>
 *   - Project(A, B, C, A + C)
 *       - (input) plan which produces symbols: [A, B, C]
 * </pre>
 */

public class TransformCorrelatedSingleRowSubqueryToProject
        implements Rule<CorrelatedJoinNode>
{
    private static final Pattern<CorrelatedJoinNode> PATTERN = correlatedJoin()
            .with(filter().equalTo(TRUE_LITERAL));

    @Override
    public Pattern<CorrelatedJoinNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(CorrelatedJoinNode parent, Captures captures, Context context)
    {
        List<ValuesNode> values = searchFrom(parent.getSubquery(), context.getLookup())
                .recurseOnlyWhen(ProjectNode.class::isInstance)
                .where(ValuesNode.class::isInstance)
                .findAll();

        if (values.size() != 1 || !isSingleRowValuesWithNoColumns(values.get(0))) {
            return Result.empty();
        }

        List<ProjectNode> subqueryProjections = searchFrom(parent.getSubquery(), context.getLookup())
                .where(node -> node instanceof ProjectNode && !node.getOutputSymbols().equals(parent.getCorrelation()))
                .findAll();

        if (subqueryProjections.size() == 0) {
            return Result.ofPlanNode(parent.getInput());
        }
        if (subqueryProjections.size() == 1) {
            Assignments assignments = Assignments.builder()
                    .putIdentities(parent.getInput().getOutputSymbols())
                    .putAll(subqueryProjections.get(0).getAssignments())
                    .build();
            return Result.ofPlanNode(projectNode(parent.getInput(), assignments, context));
        }

        return Result.empty();
    }

    private ProjectNode projectNode(PlanNode source, Assignments assignments, Context context)
    {
        return new ProjectNode(context.getIdAllocator().getNextId(), source, assignments);
    }

    private static boolean isSingleRowValuesWithNoColumns(ValuesNode values)
    {
        return values.getRowCount() == 1 && values.getOutputSymbols().isEmpty();
    }
}
