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
package io.trino.plugin.raptor.legacy.systemtables;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.PeekingIterator;
import io.airlift.slice.Slice;
import io.trino.plugin.raptor.legacy.metadata.ColumnMetadataRow;
import io.trino.plugin.raptor.legacy.metadata.ForMetadata;
import io.trino.plugin.raptor.legacy.metadata.MetadataDao;
import io.trino.plugin.raptor.legacy.metadata.TableMetadataRow;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.FixedPageSource;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.TypeManager;
import org.jdbi.v3.core.Jdbi;

import javax.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;

import static com.google.common.collect.Iterators.peekingIterator;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.raptor.legacy.RaptorErrorCode.RAPTOR_CORRUPT_METADATA;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.onDemandDao;
import static io.trino.spi.connector.SystemTable.Distribution.SINGLE_COORDINATOR;
import static io.trino.spi.predicate.TupleDomain.extractFixedValues;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class TableMetadataSystemTable
        implements SystemTable
{
    private static final String TABLE_NAME = "table_name";
    private static final String SCHEMA_NAME = "table_schema";

    private final MetadataDao dao;
    private final ConnectorTableMetadata tableMetadata;

    @Inject
    public TableMetadataSystemTable(@ForMetadata Jdbi dbi, TypeManager typeManager)
    {
        this.dao = onDemandDao(dbi, MetadataDao.class);
        requireNonNull(typeManager, "typeManager is null");

        this.tableMetadata = new ConnectorTableMetadata(
                new SchemaTableName("system", "tables"),
                ImmutableList.of(
                        new ColumnMetadata(SCHEMA_NAME, VARCHAR),
                        new ColumnMetadata(TABLE_NAME, VARCHAR),
                        new ColumnMetadata("temporal_column", VARCHAR),
                        new ColumnMetadata("ordering_columns", new ArrayType(VARCHAR)),
                        new ColumnMetadata("distribution_name", VARCHAR),
                        new ColumnMetadata("bucket_count", BIGINT),
                        new ColumnMetadata("bucketing_columns", new ArrayType(VARCHAR)),
                        new ColumnMetadata("organized", BOOLEAN)));
    }

    @Override
    public Distribution getDistribution()
    {
        return SINGLE_COORDINATOR;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata()
    {
        return tableMetadata;
    }

    @Override
    public ConnectorPageSource pageSource(ConnectorTransactionHandle transactionHandle, ConnectorSession session, TupleDomain<Integer> constraint)
    {
        return new FixedPageSource(buildPages(dao, tableMetadata, constraint));
    }

    private static List<Page> buildPages(MetadataDao dao, ConnectorTableMetadata tableMetadata, TupleDomain<Integer> tupleDomain)
    {
        Map<Integer, NullableValue> domainValues = extractFixedValues(tupleDomain).orElse(ImmutableMap.of());
        String schemaName = getStringValue(domainValues.get(getColumnIndex(tableMetadata, SCHEMA_NAME)));
        String tableName = getStringValue(domainValues.get(getColumnIndex(tableMetadata, TABLE_NAME)));

        PageListBuilder pageBuilder = new PageListBuilder(tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getType)
                .collect(toList()));

        List<TableMetadataRow> tableRows = dao.getTableMetadataRows(schemaName, tableName);
        PeekingIterator<ColumnMetadataRow> columnRowIterator = peekingIterator(dao.getColumnMetadataRows(schemaName, tableName).iterator());

        for (TableMetadataRow tableRow : tableRows) {
            while (columnRowIterator.hasNext() && columnRowIterator.peek().getTableId() < tableRow.getTableId()) {
                columnRowIterator.next();
            }

            String temporalColumnName = null;
            SortedMap<Integer, String> sortColumnNames = new TreeMap<>();
            SortedMap<Integer, String> bucketColumnNames = new TreeMap<>();
            OptionalLong temporalColumnId = tableRow.getTemporalColumnId();
            while (columnRowIterator.hasNext() && columnRowIterator.peek().getTableId() == tableRow.getTableId()) {
                ColumnMetadataRow columnRow = columnRowIterator.next();
                if (temporalColumnId.isPresent() && columnRow.getColumnId() == temporalColumnId.getAsLong()) {
                    temporalColumnName = columnRow.getColumnName();
                }
                OptionalInt sortOrdinalPosition = columnRow.getSortOrdinalPosition();
                if (sortOrdinalPosition.isPresent()) {
                    sortColumnNames.put(sortOrdinalPosition.getAsInt(), columnRow.getColumnName());
                }
                OptionalInt bucketOrdinalPosition = columnRow.getBucketOrdinalPosition();
                if (bucketOrdinalPosition.isPresent()) {
                    bucketColumnNames.put(bucketOrdinalPosition.getAsInt(), columnRow.getColumnName());
                }
            }

            pageBuilder.beginRow();

            // schema_name, table_name
            VARCHAR.writeSlice(pageBuilder.nextBlockBuilder(), utf8Slice(tableRow.getSchemaName()));
            VARCHAR.writeSlice(pageBuilder.nextBlockBuilder(), utf8Slice(tableRow.getTableName()));

            // temporal_column
            if (temporalColumnId.isPresent()) {
                if (temporalColumnName == null) {
                    throw new TrinoException(RAPTOR_CORRUPT_METADATA, format("Table ID %s has corrupt metadata (invalid temporal column ID)", tableRow.getTableId()));
                }
                VARCHAR.writeSlice(pageBuilder.nextBlockBuilder(), utf8Slice(temporalColumnName));
            }
            else {
                pageBuilder.nextBlockBuilder().appendNull();
            }

            // ordering_columns
            writeArray(pageBuilder.nextBlockBuilder(), sortColumnNames.values());

            // distribution_name
            Optional<String> distributionName = tableRow.getDistributionName();
            if (distributionName.isPresent()) {
                VARCHAR.writeSlice(pageBuilder.nextBlockBuilder(), utf8Slice(distributionName.get()));
            }
            else {
                pageBuilder.nextBlockBuilder().appendNull();
            }

            // bucket_count
            OptionalInt bucketCount = tableRow.getBucketCount();
            if (bucketCount.isPresent()) {
                BIGINT.writeLong(pageBuilder.nextBlockBuilder(), bucketCount.getAsInt());
            }
            else {
                pageBuilder.nextBlockBuilder().appendNull();
            }

            // bucketing_columns
            writeArray(pageBuilder.nextBlockBuilder(), bucketColumnNames.values());

            // organized
            BOOLEAN.writeBoolean(pageBuilder.nextBlockBuilder(), tableRow.isOrganized());
        }

        return pageBuilder.build();
    }

    private static void writeArray(BlockBuilder blockBuilder, Collection<String> values)
    {
        if (values.isEmpty()) {
            blockBuilder.appendNull();
        }
        else {
            BlockBuilder array = blockBuilder.beginBlockEntry();
            for (String value : values) {
                VARCHAR.writeSlice(array, utf8Slice(value));
            }
            blockBuilder.closeEntry();
        }
    }

    static int getColumnIndex(ConnectorTableMetadata tableMetadata, String columnName)
    {
        List<ColumnMetadata> columns = tableMetadata.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException(format("Column '%s' not found", columnName));
    }

    static String getStringValue(NullableValue value)
    {
        if ((value == null) || value.isNull()) {
            return null;
        }
        return ((Slice) value.getValue()).toStringUtf8();
    }
}
