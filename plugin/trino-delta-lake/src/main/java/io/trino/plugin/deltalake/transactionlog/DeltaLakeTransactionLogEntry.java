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
package io.trino.plugin.deltalake.transactionlog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import static java.util.Objects.requireNonNull;

public class DeltaLakeTransactionLogEntry
{
    private final TransactionEntry txn;
    private final AddFileEntry add;
    private final RemoveFileEntry remove;
    private final MetadataEntry metaData;
    private final ProtocolEntry protocol;
    private final CommitInfoEntry commitInfo;
    private final CdfFileEntry cdfFileEntry;

    private DeltaLakeTransactionLogEntry(
            TransactionEntry txn,
            AddFileEntry add,
            RemoveFileEntry remove,
            MetadataEntry metaData,
            ProtocolEntry protocol,
            CommitInfoEntry commitInfo,
            CdfFileEntry cdfFileEntry)
    {
        this.txn = txn;
        this.add = add;
        this.remove = remove;
        this.metaData = metaData;
        this.protocol = protocol;
        this.commitInfo = commitInfo;
        this.cdfFileEntry = cdfFileEntry;
    }

    @JsonCreator
    public static DeltaLakeTransactionLogEntry fromJson(
            @JsonProperty("txn") TransactionEntry txn,
            @JsonProperty("add") AddFileEntry add,
            @JsonProperty("remove") RemoveFileEntry remove,
            @JsonProperty("metaData") MetadataEntry metaData,
            @JsonProperty("protocol") ProtocolEntry protocol,
            @JsonProperty("commitInfo") CommitInfoEntry commitInfo,
            @JsonProperty("cdfFileEntry") CdfFileEntry cdfFileEntry)
    {
        return new DeltaLakeTransactionLogEntry(txn, add, remove, metaData, protocol, commitInfo, cdfFileEntry);
    }

    public static DeltaLakeTransactionLogEntry transactionEntry(TransactionEntry transaction)
    {
        requireNonNull(transaction, "transaction is null");
        return new DeltaLakeTransactionLogEntry(transaction, null, null, null, null, null, null);
    }

    public static DeltaLakeTransactionLogEntry commitInfoEntry(CommitInfoEntry commitInfo)
    {
        requireNonNull(commitInfo, "commitInfo is null");
        return new DeltaLakeTransactionLogEntry(null, null, null, null, null, commitInfo, null);
    }

    public static DeltaLakeTransactionLogEntry protocolEntry(ProtocolEntry protocolEntry)
    {
        requireNonNull(protocolEntry, "protocolEntry is null");
        return new DeltaLakeTransactionLogEntry(null, null, null, null, protocolEntry, null, null);
    }

    public static DeltaLakeTransactionLogEntry metadataEntry(MetadataEntry metadataEntry)
    {
        requireNonNull(metadataEntry, "metadataEntry is null");
        return new DeltaLakeTransactionLogEntry(null, null, null, metadataEntry, null, null, null);
    }

    public static DeltaLakeTransactionLogEntry addFileEntry(AddFileEntry addFileEntry)
    {
        requireNonNull(addFileEntry, "addFileEntry is null");
        return new DeltaLakeTransactionLogEntry(null, addFileEntry, null, null, null, null, null);
    }

    public static DeltaLakeTransactionLogEntry removeFileEntry(RemoveFileEntry removeFileEntry)
    {
        requireNonNull(removeFileEntry, "removeFileEntry is null");
        return new DeltaLakeTransactionLogEntry(null, null, removeFileEntry, null, null, null, null);
    }

    public static DeltaLakeTransactionLogEntry cdfFileEntry(CdfFileEntry cdfFileEntry)
    {
        requireNonNull(cdfFileEntry, "cdfFileEntry is null");
        return new DeltaLakeTransactionLogEntry(null, null, null, null, null, null, cdfFileEntry);
    }

    @Nullable
    @JsonProperty
    public TransactionEntry getTxn()
    {
        return txn;
    }

    @Nullable
    @JsonProperty
    public AddFileEntry getAdd()
    {
        return add;
    }

    @Nullable
    @JsonProperty
    public RemoveFileEntry getRemove()
    {
        return remove;
    }

    @Nullable
    @JsonProperty
    public MetadataEntry getMetaData()
    {
        return metaData;
    }

    @Nullable
    @JsonProperty
    public ProtocolEntry getProtocol()
    {
        return protocol;
    }

    @Nullable
    @JsonProperty
    public CommitInfoEntry getCommitInfo()
    {
        return commitInfo;
    }

    @Nullable
    @JsonProperty
    public CdfFileEntry getCDC()
    {
        return cdfFileEntry;
    }

    public DeltaLakeTransactionLogEntry withCommitInfo(CommitInfoEntry commitInfo)
    {
        return new DeltaLakeTransactionLogEntry(txn, add, remove, metaData, protocol, commitInfo, cdfFileEntry);
    }

    @Override
    public String toString()
    {
        return String.format("DeltaLakeTransactionLogEntry{%s, %s, %s, %s, %s, %s, %s}", txn, add, remove, metaData, protocol, commitInfo, cdfFileEntry);
    }
}
