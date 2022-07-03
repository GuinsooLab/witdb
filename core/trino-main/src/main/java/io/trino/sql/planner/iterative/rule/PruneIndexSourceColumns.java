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

import com.google.common.collect.Maps;
import io.trino.spi.connector.ColumnHandle;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.IndexSourceNode;
import io.trino.sql.planner.plan.PlanNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.sql.planner.plan.Patterns.indexSource;

public class PruneIndexSourceColumns
        extends ProjectOffPushDownRule<IndexSourceNode>
{
    public PruneIndexSourceColumns()
    {
        super(indexSource());
    }

    @Override
    protected Optional<PlanNode> pushDownProjectOff(Context context, IndexSourceNode indexSourceNode, Set<Symbol> referencedOutputs)
    {
        Set<Symbol> prunedLookupSymbols = indexSourceNode.getLookupSymbols().stream()
                .filter(referencedOutputs::contains)
                .collect(toImmutableSet());

        Map<Symbol, ColumnHandle> prunedAssignments = Maps.filterEntries(
                indexSourceNode.getAssignments(),
                entry -> referencedOutputs.contains(entry.getKey()));

        List<Symbol> prunedOutputList =
                indexSourceNode.getOutputSymbols().stream()
                        .filter(referencedOutputs::contains)
                        .collect(toImmutableList());

        return Optional.of(
                new IndexSourceNode(
                        indexSourceNode.getId(),
                        indexSourceNode.getIndexHandle(),
                        indexSourceNode.getTableHandle(),
                        prunedLookupSymbols,
                        prunedOutputList,
                        prunedAssignments));
    }
}
