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
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.IntersectNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.QualifiedName;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.planner.plan.Patterns.Intersect.distinct;
import static io.trino.sql.planner.plan.Patterns.intersect;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static java.util.Objects.requireNonNull;

/**
 * Implement INTERSECT ALL using union, window and filter.
 * <p>
 * Transforms:
 * <pre>
 * - Intersect all
 *   output: a, b
 *     - Source1 (a1, b1)
 *     - Source2 (a2, b2)
 *     - Source3 (a3, b3)
 * </pre>
 * Into:
 * <pre>
 * - Project (prune helper symbols)
 *   output: a, b
 *     - Filter (row_number <= least(least(count1, count2), count3))
 *         - Window (partition by a, b)
 *           count1 <- count(marker1)
 *           count2 <- count(marker2)
 *           count3 <- count(marker3)
 *           row_number <- row_number()
 *               - Union
 *                 output: a, b, marker1, marker2, marker3
 *                   - Project (marker1 <- true, marker2 <- null, marker3 <- null)
 *                       - Source1 (a1, b1)
 *                   - Project (marker1 <- null, marker2 <- true, marker3 <- null)
 *                       - Source2 (a2, b2)
 *                   - Project (marker1 <- null, marker2 <- null, marker3 <- true)
 *                       - Source3 (a3, b3)
 * </pre>
 */
public class ImplementIntersectAll
        implements Rule<IntersectNode>
{
    private static final Pattern<IntersectNode> PATTERN = intersect()
            .with(distinct().equalTo(false));

    private final Metadata metadata;

    public ImplementIntersectAll(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Pattern<IntersectNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(IntersectNode node, Captures captures, Context context)
    {
        SetOperationNodeTranslator translator = new SetOperationNodeTranslator(context.getSession(), metadata, context.getSymbolAllocator(), context.getIdAllocator());
        SetOperationNodeTranslator.TranslationResult result = translator.makeSetContainmentPlanForAll(node);

        // compute expected multiplicity for every row
        checkState(result.getCountSymbols().size() > 0, "IntersectNode translation result has no count symbols");
        ResolvedFunction least = metadata.resolveFunction(context.getSession(), QualifiedName.of("least"), fromTypes(BIGINT, BIGINT));

        Expression minCount = result.getCountSymbols().get(0).toSymbolReference();
        for (int i = 1; i < result.getCountSymbols().size(); i++) {
            minCount = new FunctionCall(least.toQualifiedName(), ImmutableList.of(minCount, result.getCountSymbols().get(i).toSymbolReference()));
        }

        // filter rows so that expected number of rows remains
        Expression removeExtraRows = new ComparisonExpression(LESS_THAN_OR_EQUAL, result.getRowNumberSymbol().toSymbolReference(), minCount);
        FilterNode filter = new FilterNode(
                context.getIdAllocator().getNextId(),
                result.getPlanNode(),
                removeExtraRows);

        // prune helper symbols
        ProjectNode project = new ProjectNode(
                context.getIdAllocator().getNextId(),
                filter,
                Assignments.identity(node.getOutputSymbols()));

        return Result.ofPlanNode(project);
    }
}
