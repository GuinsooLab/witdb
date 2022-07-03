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
package io.trino.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.trino.sql.planner.OrderingScheme;
import io.trino.sql.planner.Symbol;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class SortNode
        extends PlanNode
{
    private final PlanNode source;
    private final OrderingScheme orderingScheme;
    private final boolean partial;

    @JsonCreator
    public SortNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("source") PlanNode source,
            @JsonProperty("orderingScheme") OrderingScheme orderingScheme,
            @JsonProperty("partial") boolean partial)
    {
        super(id);

        requireNonNull(source, "source is null");
        requireNonNull(orderingScheme, "orderingScheme is null");

        this.source = source;
        this.orderingScheme = orderingScheme;
        this.partial = partial;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(source);
    }

    @JsonProperty("source")
    public PlanNode getSource()
    {
        return source;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return source.getOutputSymbols();
    }

    @JsonProperty
    public boolean isPartial()
    {
        return partial;
    }

    @JsonProperty("orderingScheme")
    public OrderingScheme getOrderingScheme()
    {
        return orderingScheme;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitSort(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new SortNode(getId(), Iterables.getOnlyElement(newChildren), orderingScheme, partial);
    }
}
