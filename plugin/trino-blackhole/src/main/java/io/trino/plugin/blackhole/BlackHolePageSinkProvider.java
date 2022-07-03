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
package io.trino.plugin.blackhole;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorPageSink;
import io.trino.spi.connector.ConnectorPageSinkProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTransactionHandle;

import static java.util.Objects.requireNonNull;

public class BlackHolePageSinkProvider
        implements ConnectorPageSinkProvider
{
    private final ListeningScheduledExecutorService executorService;

    public BlackHolePageSinkProvider(ListeningScheduledExecutorService executorService)
    {
        this.executorService = requireNonNull(executorService, "executorService is null");
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorOutputTableHandle outputTableHandle)
    {
        BlackHoleOutputTableHandle handle = (BlackHoleOutputTableHandle) outputTableHandle;
        return new BlackHolePageSink(executorService, handle.getPageProcessingDelay());
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorInsertTableHandle insertTableHandle)
    {
        BlackHoleInsertTableHandle handle = (BlackHoleInsertTableHandle) insertTableHandle;
        return new BlackHolePageSink(executorService, handle.getPageProcessingDelay());
    }
}
