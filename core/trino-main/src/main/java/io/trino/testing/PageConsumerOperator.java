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
package io.trino.testing;

import io.trino.execution.buffer.PagesSerdeFactory;
import io.trino.operator.DriverContext;
import io.trino.operator.Operator;
import io.trino.operator.OperatorContext;
import io.trino.operator.OperatorFactory;
import io.trino.operator.OutputFactory;
import io.trino.spi.Page;
import io.trino.spi.type.Type;
import io.trino.sql.planner.plan.PlanNodeId;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class PageConsumerOperator
        implements Operator
{
    public static class PageConsumerOutputFactory
            implements OutputFactory
    {
        private final Function<List<Type>, Consumer<Page>> pageConsumerFactory;

        public PageConsumerOutputFactory(Function<List<Type>, Consumer<Page>> pageConsumerFactory)
        {
            this.pageConsumerFactory = requireNonNull(pageConsumerFactory, "pageConsumerFactory is null");
        }

        @Override
        public OperatorFactory createOutputOperator(int operatorId, PlanNodeId planNodeId, List<Type> types, Function<Page, Page> pagePreprocessor, PagesSerdeFactory serdeFactory)
        {
            return new PageConsumerOperatorFactory(operatorId, planNodeId, pageConsumerFactory.apply(types), pagePreprocessor);
        }
    }

    public static class PageConsumerOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Consumer<Page> pageConsumer;
        private final Function<Page, Page> pagePreprocessor;
        private boolean closed;

        public PageConsumerOperatorFactory(int operatorId, PlanNodeId planNodeId, Consumer<Page> pageConsumer, Function<Page, Page> pagePreprocessor)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.pageConsumer = requireNonNull(pageConsumer, "pageConsumer is null");
            this.pagePreprocessor = requireNonNull(pagePreprocessor, "pagePreprocessor is null");
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, PageConsumerOperator.class.getSimpleName());
            return new PageConsumerOperator(operatorContext, pageConsumer, pagePreprocessor);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new PageConsumerOperatorFactory(operatorId, planNodeId, pageConsumer, pagePreprocessor);
        }
    }

    private final OperatorContext operatorContext;
    private final Consumer<Page> pageConsumer;
    private final Function<Page, Page> pagePreprocessor;
    private boolean finished;
    private boolean closed;

    public PageConsumerOperator(OperatorContext operatorContext, Consumer<Page> pageConsumer, Function<Page, Page> pagePreprocessor)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.pageConsumer = requireNonNull(pageConsumer, "pageConsumer is null");
        this.pagePreprocessor = requireNonNull(pagePreprocessor, "pagePreprocessor is null");
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finished = true;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    @Override
    public boolean needsInput()
    {
        return !finished;
    }

    @Override
    public void addInput(Page page)
    {
        requireNonNull(page, "page is null");
        checkState(!finished, "operator finished");

        page = pagePreprocessor.apply(page);
        pageConsumer.accept(page);
        operatorContext.recordOutput(page.getSizeInBytes(), page.getPositionCount());
    }

    @Override
    public Page getOutput()
    {
        return null;
    }

    @Override
    public void close()
    {
        closed = true;
    }
}
