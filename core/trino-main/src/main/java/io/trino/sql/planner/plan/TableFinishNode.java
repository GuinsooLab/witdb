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
import io.trino.sql.planner.Symbol;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.sql.planner.plan.TableWriterNode.WriterTarget;
import static java.util.Objects.requireNonNull;

@Immutable
public class TableFinishNode
        extends PlanNode
{
    private final PlanNode source;
    private final WriterTarget target;
    private final Symbol rowCountSymbol;
    private final Optional<StatisticAggregations> statisticsAggregation;
    private final Optional<StatisticAggregationsDescriptor<Symbol>> statisticsAggregationDescriptor;

    @JsonCreator
    public TableFinishNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("source") PlanNode source,
            @JsonProperty("target") WriterTarget target,
            @JsonProperty("rowCountSymbol") Symbol rowCountSymbol,
            @JsonProperty("statisticsAggregation") Optional<StatisticAggregations> statisticsAggregation,
            @JsonProperty("statisticsAggregationDescriptor") Optional<StatisticAggregationsDescriptor<Symbol>> statisticsAggregationDescriptor)
    {
        super(id);

        checkArgument(target != null || source instanceof TableWriterNode);
        this.source = requireNonNull(source, "source is null");
        this.target = requireNonNull(target, "target is null");
        this.rowCountSymbol = requireNonNull(rowCountSymbol, "rowCountSymbol is null");
        this.statisticsAggregation = requireNonNull(statisticsAggregation, "statisticsAggregation is null");
        this.statisticsAggregationDescriptor = requireNonNull(statisticsAggregationDescriptor, "statisticsAggregationDescriptor is null");
        checkArgument(statisticsAggregation.isPresent() == statisticsAggregationDescriptor.isPresent(), "statisticsAggregation and statisticsAggregationDescriptor must both be either present or absent");
    }

    @JsonProperty
    public PlanNode getSource()
    {
        return source;
    }

    @JsonProperty
    public WriterTarget getTarget()
    {
        return target;
    }

    @JsonProperty
    public Symbol getRowCountSymbol()
    {
        return rowCountSymbol;
    }

    @JsonProperty
    public Optional<StatisticAggregations> getStatisticsAggregation()
    {
        return statisticsAggregation;
    }

    @JsonProperty
    public Optional<StatisticAggregationsDescriptor<Symbol>> getStatisticsAggregationDescriptor()
    {
        return statisticsAggregationDescriptor;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(source);
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return ImmutableList.of(rowCountSymbol);
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitTableFinish(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new TableFinishNode(
                getId(),
                Iterables.getOnlyElement(newChildren),
                target,
                rowCountSymbol,
                statisticsAggregation,
                statisticsAggregationDescriptor);
    }
}
