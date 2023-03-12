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
import io.trino.sql.planner.Symbol;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Join;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.NullLiteral;

import javax.annotation.concurrent.Immutable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * For every row from {@link #input} a {@link #subquery} relation is calculated.
 * Then input row is cross joined with subquery relation and returned as a result.
 * <p>
 * INNER - does not return any row for input row when subquery relation is empty
 * LEFT - does return input completed with NULL values when subquery relation is empty
 */
@Immutable
public class CorrelatedJoinNode
        extends PlanNode
{
    public enum Type
    {
        INNER(JoinNode.Type.INNER),
        LEFT(JoinNode.Type.LEFT),
        RIGHT(JoinNode.Type.RIGHT),
        FULL(JoinNode.Type.FULL);

        Type(JoinNode.Type joinNodeType)
        {
            this.joinNodeType = joinNodeType;
        }

        private final JoinNode.Type joinNodeType;

        public JoinNode.Type toJoinNodeType()
        {
            return joinNodeType;
        }

        public static Type typeConvert(Join.Type joinType)
        {
            return switch (joinType) {
                case CROSS, IMPLICIT, INNER -> Type.INNER;
                case LEFT -> Type.LEFT;
                case RIGHT -> Type.RIGHT;
                case FULL -> Type.FULL;
            };
        }
    }

    private final PlanNode input;
    private final PlanNode subquery;

    /**
     * Correlation symbols, returned from input (outer plan) used in subquery (inner plan)
     */
    private final List<Symbol> correlation;
    private final Type type;
    private final Expression filter;

    /**
     * HACK!
     * Used for error reporting in case this ApplyNode is not supported
     */
    private final Node originSubquery;

    @JsonCreator
    public CorrelatedJoinNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("input") PlanNode input,
            @JsonProperty("subquery") PlanNode subquery,
            @JsonProperty("correlation") List<Symbol> correlation,
            @JsonProperty("type") Type type,
            @JsonProperty("filter") Expression filter,
            @JsonProperty("originSubquery") Node originSubquery)
    {
        super(id);
        requireNonNull(input, "input is null");
        requireNonNull(subquery, "subquery is null");
        requireNonNull(correlation, "correlation is null");
        requireNonNull(filter, "filter is null");
        // The condition doesn't guarantee that filter is of type boolean, but was found to be a practical way to identify
        // places where CorrelatedJoinNode could be created without appropriate coercions.
        checkArgument(!(filter instanceof NullLiteral), "Filter must be an expression of boolean type: %s", filter);
        requireNonNull(originSubquery, "originSubquery is null");

        checkArgument(input.getOutputSymbols().containsAll(correlation), "Input does not contain symbols from correlation");

        this.input = input;
        this.subquery = subquery;
        this.correlation = ImmutableList.copyOf(correlation);
        this.type = type;
        this.filter = filter;
        this.originSubquery = originSubquery;
    }

    @JsonProperty("input")
    public PlanNode getInput()
    {
        return input;
    }

    @JsonProperty("subquery")
    public PlanNode getSubquery()
    {
        return subquery;
    }

    @JsonProperty("correlation")
    public List<Symbol> getCorrelation()
    {
        return correlation;
    }

    @JsonProperty("type")
    public Type getType()
    {
        return type;
    }

    @JsonProperty("filter")
    public Expression getFilter()
    {
        return filter;
    }

    @JsonProperty("originSubquery")
    public Node getOriginSubquery()
    {
        return originSubquery;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(input, subquery);
    }

    @Override
    @JsonProperty("outputSymbols")
    public List<Symbol> getOutputSymbols()
    {
        return ImmutableList.<Symbol>builder()
                .addAll(input.getOutputSymbols())
                .addAll(subquery.getOutputSymbols())
                .build();
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        checkArgument(newChildren.size() == 2, "expected newChildren to contain 2 nodes");
        return new CorrelatedJoinNode(getId(), newChildren.get(0), newChildren.get(1), correlation, type, filter, originSubquery);
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitCorrelatedJoin(this, context);
    }
}
