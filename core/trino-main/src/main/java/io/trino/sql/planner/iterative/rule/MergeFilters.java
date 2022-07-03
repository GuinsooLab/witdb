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

import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.metadata.Metadata;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.FilterNode;

import static io.trino.matching.Capture.newCapture;
import static io.trino.sql.ExpressionUtils.combineConjuncts;
import static io.trino.sql.planner.plan.Patterns.filter;
import static io.trino.sql.planner.plan.Patterns.source;
import static java.util.Objects.requireNonNull;

public class MergeFilters
        implements Rule<FilterNode>
{
    private static final Capture<FilterNode> CHILD = newCapture();

    private static final Pattern<FilterNode> PATTERN = filter()
            .with(source().matching(filter().capturedAs(CHILD)));

    private final Metadata metadata;

    public MergeFilters(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(FilterNode parent, Captures captures, Context context)
    {
        FilterNode child = captures.get(CHILD);

        return Result.ofPlanNode(
                new FilterNode(
                        parent.getId(),
                        child.getSource(),
                        combineConjuncts(metadata, child.getPredicate(), parent.getPredicate())));
    }
}
