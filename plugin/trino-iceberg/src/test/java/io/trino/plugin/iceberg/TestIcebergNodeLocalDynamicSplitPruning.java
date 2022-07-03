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
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.JsonCodecFactory;
import io.airlift.testing.TempFile;
import io.trino.connector.CatalogName;
import io.trino.metadata.TableHandle;
import io.trino.operator.GroupByHashPageIndexerFactory;
import io.trino.orc.OrcWriteValidation;
import io.trino.orc.OrcWriter;
import io.trino.orc.OrcWriterOptions;
import io.trino.orc.OrcWriterStats;
import io.trino.orc.OutputStreamOrcDataSink;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.HiveTransactionHandle;
import io.trino.plugin.hive.NodeVersion;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.orc.OrcReaderConfig;
import io.trino.plugin.hive.orc.OrcWriterConfig;
import io.trino.plugin.hive.parquet.ParquetReaderConfig;
import io.trino.plugin.hive.parquet.ParquetWriterConfig;
import io.trino.spi.Page;
import io.trino.spi.SplitWeight;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.RetryMode;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.Type;
import io.trino.sql.gen.JoinCompiler;
import io.trino.testing.TestingConnectorSession;
import io.trino.type.BlockTypeOperators;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.types.Types;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.trino.orc.metadata.CompressionKind.NONE;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.HiveType.HIVE_INT;
import static io.trino.plugin.hive.HiveType.HIVE_STRING;
import static io.trino.plugin.iceberg.ColumnIdentity.TypeCategory.PRIMITIVE;
import static io.trino.plugin.iceberg.IcebergFileFormat.ORC;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class TestIcebergNodeLocalDynamicSplitPruning
{
    private static final String ICEBERG_CATALOG_NAME = "iceberg";
    private static final String SCHEMA_NAME = "test";
    private static final String TABLE_NAME = "test";
    private static final Column KEY_COLUMN = new Column("a_integer", HIVE_INT, Optional.empty());
    private static final ColumnIdentity KEY_COLUMN_IDENTITY = new ColumnIdentity(1, KEY_COLUMN.getName(), PRIMITIVE, ImmutableList.of());
    private static final IcebergColumnHandle KEY_ICEBERG_COLUMN_HANDLE = new IcebergColumnHandle(KEY_COLUMN_IDENTITY, INTEGER, ImmutableList.of(), INTEGER, Optional.empty());
    private static final int KEY_COLUMN_VALUE = 42;
    private static final Column DATA_COLUMN = new Column("a_varchar", HIVE_STRING, Optional.empty());
    private static final ColumnIdentity DATA_COLUMN_IDENTITY = new ColumnIdentity(2, DATA_COLUMN.getName(), PRIMITIVE, ImmutableList.of());
    private static final IcebergColumnHandle DATA_ICEBERG_COLUMN_HANDLE = new IcebergColumnHandle(DATA_COLUMN_IDENTITY, VARCHAR, ImmutableList.of(), VARCHAR, Optional.empty());
    private static final String DATA_COLUMN_VALUE = "hello world";
    private static final Schema TABLE_SCHEMA = new Schema(
            optional(KEY_COLUMN_IDENTITY.getId(), KEY_COLUMN.getName(), Types.IntegerType.get()),
            optional(DATA_COLUMN_IDENTITY.getId(), DATA_COLUMN.getName(), Types.StringType.get()));
    private static final OrcReaderConfig ORC_READER_CONFIG = new OrcReaderConfig();
    private static final OrcWriterConfig ORC_WRITER_CONFIG = new OrcWriterConfig();
    private static final ParquetReaderConfig PARQUET_READER_CONFIG = new ParquetReaderConfig();
    private static final ParquetWriterConfig PARQUET_WRITER_CONFIG = new ParquetWriterConfig();

    @Test
    public void testDynamicSplitPruning()
            throws IOException
    {
        IcebergConfig icebergConfig = new IcebergConfig();
        HiveTransactionHandle transaction = new HiveTransactionHandle(false);
        try (TempFile tempFile = new TempFile()) {
            writeOrcContent(tempFile.file());

            try (ConnectorPageSource emptyPageSource = createTestingPageSource(transaction, icebergConfig, tempFile.file(), getDynamicFilter(getTupleDomainForSplitPruning()))) {
                assertNull(emptyPageSource.getNextPage());
            }

            try (ConnectorPageSource nonEmptyPageSource = createTestingPageSource(transaction, icebergConfig, tempFile.file(), getDynamicFilter(getNonSelectiveTupleDomain()))) {
                Page page = nonEmptyPageSource.getNextPage();
                assertNotNull(page);
                assertEquals(page.getBlock(0).getPositionCount(), 1);
                assertEquals(page.getBlock(0).getInt(0, 0), KEY_COLUMN_VALUE);
                assertEquals(page.getBlock(1).getPositionCount(), 1);
                assertEquals(page.getBlock(1).getSlice(0, 0, page.getBlock(1).getSliceLength(0)).toStringUtf8(), DATA_COLUMN_VALUE);
            }
        }
    }

    private static void writeOrcContent(File file)
            throws IOException
    {
        List<String> columnNames = ImmutableList.of(KEY_COLUMN.getName(), DATA_COLUMN.getName());
        List<Type> types = ImmutableList.of(INTEGER, VARCHAR);

        try (OutputStream out = new FileOutputStream(file);
                OrcWriter writer = new OrcWriter(
                        new OutputStreamOrcDataSink(out),
                        columnNames,
                        types,
                        TypeConverter.toOrcType(TABLE_SCHEMA),
                        NONE,
                        new OrcWriterOptions(),
                        ImmutableMap.of(),
                        true,
                        OrcWriteValidation.OrcWriteValidationMode.BOTH,
                        new OrcWriterStats())) {
            BlockBuilder keyBuilder = INTEGER.createBlockBuilder(null, 1);
            INTEGER.writeLong(keyBuilder, KEY_COLUMN_VALUE);
            BlockBuilder dataBuilder = VARCHAR.createBlockBuilder(null, 1);
            VARCHAR.writeString(dataBuilder, DATA_COLUMN_VALUE);
            writer.write(new Page(keyBuilder.build(), dataBuilder.build()));
        }
    }

    private static ConnectorPageSource createTestingPageSource(HiveTransactionHandle transaction, IcebergConfig icebergConfig, File outputFile, DynamicFilter dynamicFilter)
    {
        IcebergSplit split = new IcebergSplit(
                "file:///" + outputFile.getAbsolutePath(),
                0,
                outputFile.length(),
                outputFile.length(),
                0, // This is incorrect, but the value is only used for delete operations
                ORC,
                ImmutableList.of(),
                PartitionSpecParser.toJson(PartitionSpec.unpartitioned()),
                PartitionData.toJson(new PartitionData(new Object[] {})),
                ImmutableList.of(),
                SplitWeight.standard());

        TableHandle tableHandle = new TableHandle(
                new CatalogName(ICEBERG_CATALOG_NAME),
                new IcebergTableHandle(
                        SCHEMA_NAME,
                        TABLE_NAME,
                        TableType.DATA,
                        Optional.empty(),
                        SchemaParser.toJson(TABLE_SCHEMA),
                        PartitionSpecParser.toJson(PartitionSpec.unpartitioned()),
                        2,
                        TupleDomain.withColumnDomains(ImmutableMap.of(KEY_ICEBERG_COLUMN_HANDLE, Domain.singleValue(INTEGER, (long) KEY_COLUMN_VALUE))),
                        TupleDomain.all(),
                        ImmutableSet.of(KEY_ICEBERG_COLUMN_HANDLE),
                        Optional.empty(),
                        outputFile.getParentFile().getAbsolutePath(),
                        ImmutableMap.of(),
                        RetryMode.NO_RETRIES,
                        ImmutableList.of(),
                        false,
                        Optional.empty()),
                transaction);

        FileFormatDataSourceStats stats = new FileFormatDataSourceStats();
        IcebergPageSourceProvider provider = new IcebergPageSourceProvider(
                HDFS_ENVIRONMENT,
                stats,
                ORC_READER_CONFIG,
                PARQUET_READER_CONFIG,
                TESTING_TYPE_MANAGER,
                new HdfsFileIoProvider(HDFS_ENVIRONMENT),
                new JsonCodecFactory().jsonCodec(CommitTaskData.class),
                new IcebergFileWriterFactory(HDFS_ENVIRONMENT, TESTING_TYPE_MANAGER, new NodeVersion("trino_test"), stats, ORC_WRITER_CONFIG),
                new GroupByHashPageIndexerFactory(new JoinCompiler(TESTING_TYPE_MANAGER.getTypeOperators()), new BlockTypeOperators()),
                icebergConfig);

        return provider.createPageSource(
                transaction,
                getSession(icebergConfig),
                split,
                tableHandle.getConnectorHandle(),
                ImmutableList.of(KEY_ICEBERG_COLUMN_HANDLE, DATA_ICEBERG_COLUMN_HANDLE),
                dynamicFilter);
    }

    private static TupleDomain<ColumnHandle> getTupleDomainForSplitPruning()
    {
        return TupleDomain.withColumnDomains(
                ImmutableMap.of(
                        KEY_ICEBERG_COLUMN_HANDLE,
                        Domain.singleValue(INTEGER, 1L)));
    }

    private static TupleDomain<ColumnHandle> getNonSelectiveTupleDomain()
    {
        return TupleDomain.withColumnDomains(
                ImmutableMap.of(
                        KEY_ICEBERG_COLUMN_HANDLE,
                        Domain.singleValue(INTEGER, (long) KEY_COLUMN_VALUE)));
    }

    private static TestingConnectorSession getSession(IcebergConfig icebergConfig)
    {
        return TestingConnectorSession.builder()
                .setPropertyMetadata(new IcebergSessionProperties(icebergConfig, ORC_READER_CONFIG, ORC_WRITER_CONFIG, PARQUET_READER_CONFIG, PARQUET_WRITER_CONFIG).getSessionProperties())
                .build();
    }

    private static DynamicFilter getDynamicFilter(TupleDomain<ColumnHandle> tupleDomain)
    {
        return new DynamicFilter()
        {
            @Override
            public Set<ColumnHandle> getColumnsCovered()
            {
                return tupleDomain.getDomains().map(Map::keySet)
                        .orElseGet(ImmutableSet::of);
            }

            @Override
            public CompletableFuture<?> isBlocked()
            {
                return completedFuture(null);
            }

            @Override
            public boolean isComplete()
            {
                return true;
            }

            @Override
            public boolean isAwaitable()
            {
                return false;
            }

            @Override
            public TupleDomain<ColumnHandle> getCurrentPredicate()
            {
                return tupleDomain;
            }
        };
    }
}
