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
package io.trino.operator.index;

import com.google.common.collect.ImmutableList;
import io.trino.operator.DriverFactory;
import io.trino.operator.OperatorFactory;
import io.trino.spi.Page;
import io.trino.spi.type.Type;
import io.trino.sql.planner.plan.PlanNodeId;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.operator.index.PageBufferOperator.PageBufferOperatorFactory;
import static io.trino.operator.index.PagesIndexBuilderOperator.PagesIndexBuilderOperatorFactory;
import static java.util.Objects.requireNonNull;

public class IndexBuildDriverFactoryProvider
{
    private final int pipelineId;
    private final int outputOperatorId;
    private final PlanNodeId planNodeId;
    private final boolean inputDriver;
    private final List<OperatorFactory> coreOperatorFactories;
    private final List<Type> outputTypes;
    private final Optional<DynamicTupleFilterFactory> dynamicTupleFilterFactory;

    public IndexBuildDriverFactoryProvider(
            int pipelineId,
            int outputOperatorId,
            PlanNodeId planNodeId,
            boolean inputDriver,
            List<Type> outputTypes,
            List<OperatorFactory> coreOperatorFactories,
            Optional<DynamicTupleFilterFactory> dynamicTupleFilterFactory)
    {
        requireNonNull(planNodeId, "planNodeId is null");
        requireNonNull(outputTypes, "outputTypes is null");
        requireNonNull(coreOperatorFactories, "coreOperatorFactories is null");
        checkArgument(!coreOperatorFactories.isEmpty(), "coreOperatorFactories is empty");
        requireNonNull(dynamicTupleFilterFactory, "dynamicTupleFilterFactory is null");

        this.pipelineId = pipelineId;
        this.outputOperatorId = outputOperatorId;
        this.planNodeId = planNodeId;
        this.inputDriver = inputDriver;
        this.coreOperatorFactories = ImmutableList.copyOf(coreOperatorFactories);
        this.outputTypes = ImmutableList.copyOf(outputTypes);
        this.dynamicTupleFilterFactory = dynamicTupleFilterFactory;
    }

    public int getPipelineId()
    {
        return pipelineId;
    }

    public List<Type> getOutputTypes()
    {
        return outputTypes;
    }

    public DriverFactory createSnapshot(int pipelineId, IndexSnapshotBuilder indexSnapshotBuilder)
    {
        checkArgument(indexSnapshotBuilder.getOutputTypes().equals(outputTypes));
        return new DriverFactory(
                pipelineId,
                inputDriver,
                false,
                ImmutableList.<OperatorFactory>builder()
                        .addAll(coreOperatorFactories)
                        .add(new PagesIndexBuilderOperatorFactory(outputOperatorId, planNodeId, indexSnapshotBuilder, "IndexBuilder"))
                        .build(),
                OptionalInt.empty());
    }

    public DriverFactory createStreaming(PageBuffer pageBuffer, Page indexKeyTuple)
    {
        ImmutableList.Builder<OperatorFactory> operatorFactories = ImmutableList.<OperatorFactory>builder()
                .addAll(coreOperatorFactories);

        if (dynamicTupleFilterFactory.isPresent()) {
            // Bind in a dynamic tuple filter if necessary
            operatorFactories.add(dynamicTupleFilterFactory.get().filterWithTuple(indexKeyTuple));
        }

        operatorFactories.add(new PageBufferOperatorFactory(outputOperatorId, planNodeId, pageBuffer, "IndexBuilder"));

        return new DriverFactory(pipelineId, inputDriver, false, operatorFactories.build(), OptionalInt.empty());
    }
}
