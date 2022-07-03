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

import com.google.common.collect.ImmutableSet;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.ProjectNode;

import static io.trino.sql.planner.plan.Patterns.project;

/**
 * Removes projection nodes that only perform non-renaming identity projections
 */
public class RemoveRedundantIdentityProjections
        implements Rule<ProjectNode>
{
    private static final Pattern<ProjectNode> PATTERN = project()
            .matching(ProjectNode::isIdentity)
            // only drop this projection if it does not constrain the outputs
            // of its child
            .matching(RemoveRedundantIdentityProjections::outputsSameAsSource);

    private static boolean outputsSameAsSource(ProjectNode node)
    {
        return ImmutableSet.copyOf(node.getOutputSymbols()).equals(ImmutableSet.copyOf(node.getSource().getOutputSymbols()));
    }

    @Override
    public Pattern<ProjectNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ProjectNode project, Captures captures, Context context)
    {
        return Result.ofPlanNode(project.getSource());
    }
}
