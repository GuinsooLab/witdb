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
package io.trino.transaction;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.connector.CatalogName;
import io.trino.metadata.CatalogInfo;
import io.trino.metadata.CatalogMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;

import java.util.List;
import java.util.Optional;

/**
 * Used on workers.
 */
public class NoOpTransactionManager
        implements TransactionManager
{
    @Override
    public boolean transactionExists(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public TransactionInfo getTransactionInfo(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<TransactionInfo> getAllTransactionInfos()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public TransactionId beginTransaction(boolean autoCommitContext)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public TransactionId beginTransaction(IsolationLevel isolationLevel, boolean readOnly, boolean autoCommitContext)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CatalogInfo> getCatalogs(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CatalogName> getCatalogName(TransactionId transactionId, String catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CatalogMetadata> getOptionalCatalogMetadata(TransactionId transactionId, String catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogMetadata getCatalogMetadata(TransactionId transactionId, CatalogName catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, CatalogName catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, String catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConnectorTransactionHandle getConnectorTransaction(TransactionId transactionId, CatalogName catalogName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkAndSetActive(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void trySetActive(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void trySetInactive(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Void> asyncCommit(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Void> asyncAbort(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fail(TransactionId transactionId)
    {
        throw new UnsupportedOperationException();
    }
}
