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
package io.trino.operator.join;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.operator.TaskContext;
import io.trino.spi.type.Type;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Collections.emptyList;

public interface LookupSourceFactory
        extends JoinBridge
{
    List<Type> getTypes();

    List<Type> getOutputTypes();

    ListenableFuture<LookupSourceProvider> createLookupSourceProvider();

    int partitions();

    default ListenableFuture<PartitionedConsumption<Supplier<LookupSource>>> finishProbeOperator(OptionalInt lookupJoinsCount)
    {
        return immediateFuture(new PartitionedConsumption<>(
                1,
                emptyList(),
                i -> {
                    throw new UnsupportedOperationException();
                },
                i -> {},
                i -> {
                    throw new UnsupportedOperationException();
                }));
    }

    /**
     * Can be called only after {@link #createLookupSourceProvider()} is done and all users of {@link LookupSource}-s finished.
     */
    @Override
    OuterPositionIterator getOuterPositionIterator();

    // this is only here for the index lookup source
    default void setTaskContext(TaskContext taskContext) {}

    @Override
    void destroy();

    default ListenableFuture<Void> isDestroyed()
    {
        throw new UnsupportedOperationException();
    }
}
