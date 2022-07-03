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
package io.trino.sql.planner.assertions;

import io.trino.Session;
import io.trino.cost.StatsProvider;
import io.trino.metadata.Metadata;
import io.trino.sql.planner.assertions.PlanMatchPattern.Ordering;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.SortNode;

import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static io.trino.sql.planner.assertions.MatchResult.NO_MATCH;
import static io.trino.sql.planner.assertions.Util.orderingSchemeMatches;
import static java.util.Objects.requireNonNull;

final class SortMatcher
        implements Matcher
{
    private final List<Ordering> orderBy;

    public SortMatcher(List<Ordering> orderBy)
    {
        this.orderBy = requireNonNull(orderBy, "orderBy is null");
    }

    @Override
    public boolean shapeMatches(PlanNode node)
    {
        return node instanceof SortNode;
    }

    @Override
    public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
    {
        checkState(shapeMatches(node), "Plan testing framework error: shapeMatches returned false in detailMatches in %s", this.getClass().getName());
        SortNode sortNode = (SortNode) node;

        if (!orderingSchemeMatches(orderBy, sortNode.getOrderingScheme(), symbolAliases)) {
            return NO_MATCH;
        }

        return MatchResult.match();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("orderBy", orderBy)
                .toString();
    }
}
