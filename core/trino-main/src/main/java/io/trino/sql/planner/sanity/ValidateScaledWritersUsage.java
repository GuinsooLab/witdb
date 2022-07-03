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
package io.trino.sql.planner.sanity;

import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.sql.PlannerContext;
import io.trino.sql.planner.PartitioningHandle;
import io.trino.sql.planner.TypeAnalyzer;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.planner.plan.ExchangeNode;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.PlanVisitor;
import io.trino.sql.planner.plan.TableWriterNode;
import io.trino.sql.planner.sanity.PlanSanityChecker.Checker;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.sql.planner.SystemPartitioningHandle.SCALED_WRITER_DISTRIBUTION;
import static java.util.Objects.requireNonNull;

/**
 * When a SCALED_WRITER_DISTRIBUTION is chosen as partitioning method then target writer should support for it.
 * This validator ensure that.
 */
public class ValidateScaledWritersUsage
        implements Checker
{
    @Override
    public void validate(
            PlanNode planNode,
            Session session,
            PlannerContext plannerContext,
            TypeAnalyzer typeAnalyzer,
            TypeProvider types,
            WarningCollector warningCollector)
    {
        planNode.accept(new Visitor(session, plannerContext), null);
    }

    private static class Visitor
            extends PlanVisitor<List<PartitioningHandle>, Void>
    {
        private final Session session;
        private final PlannerContext plannerContext;

        private Visitor(Session session, PlannerContext plannerContext)
        {
            this.session = requireNonNull(session, "session is null");
            this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
        }

        @Override
        protected List<PartitioningHandle> visitPlan(PlanNode node, Void context)
        {
            return collectPartitioningHandles(node.getSources());
        }

        @Override
        public List<PartitioningHandle> visitTableWriter(TableWriterNode node, Void context)
        {
            List<PartitioningHandle> children = collectPartitioningHandles(node.getSources());
            boolean anyScaledWriterDistribution = children.stream().anyMatch(partitioningHandle -> partitioningHandle == SCALED_WRITER_DISTRIBUTION);
            TableWriterNode.WriterTarget target = node.getTarget();
            checkState(!anyScaledWriterDistribution || target.supportsReportingWrittenBytes(plannerContext.getMetadata(), session),
                    "The partitioning scheme is set to SCALED_WRITER_DISTRIBUTION but writer target %s does support for it", target);
            return children;
        }

        @Override
        public List<PartitioningHandle> visitExchange(ExchangeNode node, Void context)
        {
            return ImmutableList.<PartitioningHandle>builder()
                    .add(node.getPartitioningScheme().getPartitioning().getHandle())
                    .addAll(collectPartitioningHandles(node.getSources()))
                    .build();
        }

        private List<PartitioningHandle> collectPartitioningHandles(List<PlanNode> nodes)
        {
            return nodes.stream()
                    .map(node -> node.accept(this, null))
                    .flatMap(List::stream)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }
}
