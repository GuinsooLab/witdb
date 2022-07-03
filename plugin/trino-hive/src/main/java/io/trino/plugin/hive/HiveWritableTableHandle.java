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
package io.trino.plugin.hive;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.plugin.hive.metastore.HivePageSinkMetadata;
import io.trino.spi.connector.SchemaTableName;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class HiveWritableTableHandle
{
    private final String schemaName;
    private final String tableName;
    private final List<HiveColumnHandle> inputColumns;
    private final HivePageSinkMetadata pageSinkMetadata;
    private final LocationHandle locationHandle;
    private final Optional<HiveBucketProperty> bucketProperty;
    private final HiveStorageFormat tableStorageFormat;
    private final HiveStorageFormat partitionStorageFormat;
    private final AcidTransaction transaction;
    private final boolean retriesEnabled;

    public HiveWritableTableHandle(
            String schemaName,
            String tableName,
            List<HiveColumnHandle> inputColumns,
            HivePageSinkMetadata pageSinkMetadata,
            LocationHandle locationHandle,
            Optional<HiveBucketProperty> bucketProperty,
            HiveStorageFormat tableStorageFormat,
            HiveStorageFormat partitionStorageFormat,
            AcidTransaction transaction,
            boolean retriesEnabled)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");
        this.inputColumns = ImmutableList.copyOf(requireNonNull(inputColumns, "inputColumns is null"));
        this.pageSinkMetadata = requireNonNull(pageSinkMetadata, "pageSinkMetadata is null");
        this.locationHandle = requireNonNull(locationHandle, "locationHandle is null");
        this.bucketProperty = requireNonNull(bucketProperty, "bucketProperty is null");
        this.tableStorageFormat = requireNonNull(tableStorageFormat, "tableStorageFormat is null");
        this.partitionStorageFormat = requireNonNull(partitionStorageFormat, "partitionStorageFormat is null");
        this.transaction = requireNonNull(transaction, "transaction is null");
        this.retriesEnabled = retriesEnabled;
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    @JsonIgnore
    public SchemaTableName getSchemaTableName()
    {
        return new SchemaTableName(schemaName, tableName);
    }

    @JsonProperty
    public List<HiveColumnHandle> getInputColumns()
    {
        return inputColumns;
    }

    @JsonProperty
    public HivePageSinkMetadata getPageSinkMetadata()
    {
        return pageSinkMetadata;
    }

    @JsonProperty
    public LocationHandle getLocationHandle()
    {
        return locationHandle;
    }

    @JsonProperty
    public Optional<HiveBucketProperty> getBucketProperty()
    {
        return bucketProperty;
    }

    @JsonProperty
    public HiveStorageFormat getTableStorageFormat()
    {
        return tableStorageFormat;
    }

    @JsonProperty
    public HiveStorageFormat getPartitionStorageFormat()
    {
        return partitionStorageFormat;
    }

    @JsonProperty
    public AcidTransaction getTransaction()
    {
        return transaction;
    }

    @JsonIgnore
    public boolean isTransactional()
    {
        return transaction.isTransactional();
    }

    @JsonProperty
    public boolean isRetriesEnabled()
    {
        return retriesEnabled;
    }

    @Override
    public String toString()
    {
        return schemaName + "." + tableName;
    }
}
