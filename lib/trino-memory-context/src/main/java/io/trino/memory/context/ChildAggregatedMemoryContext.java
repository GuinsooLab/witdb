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
package io.trino.memory.context;

import com.google.common.util.concurrent.ListenableFuture;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

class ChildAggregatedMemoryContext
        extends AbstractAggregatedMemoryContext
{
    private final AbstractAggregatedMemoryContext parentMemoryContext;

    ChildAggregatedMemoryContext(AggregatedMemoryContext parentMemoryContext)
    {
        verify(parentMemoryContext instanceof AbstractAggregatedMemoryContext);
        this.parentMemoryContext = (AbstractAggregatedMemoryContext) requireNonNull(parentMemoryContext, "parentMemoryContext is null");
    }

    @Override
    synchronized ListenableFuture<Void> updateBytes(String allocationTag, long delta)
    {
        checkState(!isClosed(), "ChildAggregatedMemoryContext is already closed");
        // update the parent before updating usedBytes as it may throw a runtime exception (e.g., ExceededMemoryLimitException)
        ListenableFuture<Void> future = parentMemoryContext.updateBytes(allocationTag, delta);
        addBytes(delta);
        return future;
    }

    @Override
    synchronized boolean tryUpdateBytes(String allocationTag, long delta)
    {
        checkState(!isClosed(), "ChildAggregatedMemoryContext is already closed");
        if (parentMemoryContext.tryUpdateBytes(allocationTag, delta)) {
            addBytes(delta);
            return true;
        }
        return false;
    }

    @Override
    void closeContext()
    {
        parentMemoryContext.updateBytes(FORCE_FREE_TAG, -getBytes());
    }
}
