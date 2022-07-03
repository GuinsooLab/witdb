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
package io.trino.operator.exchange;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.operator.exchange.PageReference.PageReleasedListener;
import io.trino.spi.Page;

import java.util.function.LongConsumer;

import static java.util.Objects.requireNonNull;

public class PassthroughExchanger
        implements LocalExchanger
{
    private final LocalExchangeSource localExchangeSource;
    private final LocalExchangeMemoryManager bufferMemoryManager;
    private final LongConsumer memoryTracker;
    private final PageReleasedListener onPageReleased;

    public PassthroughExchanger(LocalExchangeSource localExchangeSource, long bufferMaxMemory, LongConsumer memoryTracker)
    {
        this.localExchangeSource = requireNonNull(localExchangeSource, "localExchangeSource is null");
        this.memoryTracker = requireNonNull(memoryTracker, "memoryTracker is null");
        bufferMemoryManager = new LocalExchangeMemoryManager(bufferMaxMemory);
        onPageReleased = releasedSizeInBytes -> {
            this.bufferMemoryManager.updateMemoryUsage(-releasedSizeInBytes);
            this.memoryTracker.accept(-releasedSizeInBytes);
        };
    }

    @Override
    public void accept(Page page)
    {
        PageReference pageReference = new PageReference(page, 1, onPageReleased);
        long retainedSizeInBytes = pageReference.getRetainedSizeInBytes();
        bufferMemoryManager.updateMemoryUsage(retainedSizeInBytes);
        memoryTracker.accept(retainedSizeInBytes);

        localExchangeSource.addPage(pageReference);
    }

    @Override
    public ListenableFuture<Void> waitForWriting()
    {
        return bufferMemoryManager.getNotFullFuture();
    }

    @Override
    public void finish()
    {
        localExchangeSource.finish();
    }
}
