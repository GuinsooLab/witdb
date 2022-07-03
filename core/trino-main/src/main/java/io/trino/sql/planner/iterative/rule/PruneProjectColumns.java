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

import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.ProjectNode;

import java.util.Optional;
import java.util.Set;

import static io.trino.sql.planner.plan.Patterns.project;

public class PruneProjectColumns
        extends ProjectOffPushDownRule<ProjectNode>
{
    public PruneProjectColumns()
    {
        super(project());
    }

    @Override
    protected Optional<PlanNode> pushDownProjectOff(
            Context context,
            ProjectNode childProjectNode,
            Set<Symbol> referencedOutputs)
    {
        return Optional.of(
                new ProjectNode(
                        childProjectNode.getId(),
                        childProjectNode.getSource(),
                        childProjectNode.getAssignments().filter(referencedOutputs)));
    }
}
