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

import com.google.common.base.VerifyException;
import io.trino.plugin.raptor.legacy.RaptorTableHandle;
import io.trino.plugin.raptor.legacy.metadata.MetadataDao;
import io.trino.plugin.raptor.legacy.metadata.TableColumn;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.FixedPageSource;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.Type;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.maxColumn;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.minColumn;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.shardIndexTable;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.metadataError;
import static io.trino.spi.connector.SystemTable.Distribution.SINGLE_COORDINATOR;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class ColumnRangesSystemTable
        implements SystemTable
{
    private static final String MIN_COLUMN_SUFFIX = "_min";
    private static final String MAX_COLUMN_SUFFIX = "_max";
    private static final String COLUMN_RANGES_TABLE_SUFFIX = "$column_ranges";

    private final Jdbi dbi;
    private final RaptorTableHandle sourceTable;
    private final List<TableColumn> indexedRaptorColumns;
    private final ConnectorTableMetadata tableMetadata;

    public ColumnRangesSystemTable(RaptorTableHandle sourceTable, Jdbi dbi)
    {
        this.sourceTable = requireNonNull(sourceTable, "sourceTable is null");
        this.dbi = requireNonNull(dbi, "dbi is null");

        this.indexedRaptorColumns = dbi.onDemand(MetadataDao.class)
                .listTableColumns(sourceTable.getTableId()).stream()
                .filter(column -> isIndexedType(column.getDataType()))
                .collect(toImmutableList());
        List<ColumnMetadata> systemTableColumns = indexedRaptorColumns.stream()
                .flatMap(column -> Stream.of(
                        new ColumnMetadata(column.getColumnName() + MIN_COLUMN_SUFFIX, column.getDataType()),
                        new ColumnMetadata(column.getColumnName() + MAX_COLUMN_SUFFIX, column.getDataType())))
                .collect(toImmutableList());
        SchemaTableName tableName = new SchemaTableName(sourceTable.getSchemaName(), sourceTable.getTableName() + COLUMN_RANGES_TABLE_SUFFIX);
        this.tableMetadata = new ConnectorTableMetadata(tableName, systemTableColumns);
    }

    public static Optional<SchemaTableName> getSourceTable(SchemaTableName tableName)
    {
        if (tableName.getTableName().endsWith(COLUMN_RANGES_TABLE_SUFFIX) &&
                !tableName.getTableName().equals(COLUMN_RANGES_TABLE_SUFFIX)) {
            int tableNameLength = tableName.getTableName().length() - COLUMN_RANGES_TABLE_SUFFIX.length();
            return Optional.of(new SchemaTableName(
                    tableName.getSchemaName(),
                    tableName.getTableName().substring(0, tableNameLength)));
        }
        return Optional.empty();
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
        String metadataSqlQuery = getColumnRangesMetadataSqlQuery(sourceTable, indexedRaptorColumns);
        List<Type> columnTypes = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getType)
                .collect(toImmutableList());

        PageListBuilder pageListBuilder = new PageListBuilder(columnTypes);

        try (Connection connection = dbi.open().getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(metadataSqlQuery)) {
            if (resultSet.next()) {
                pageListBuilder.beginRow();
                for (int i = 0; i < columnTypes.size(); ++i) {
                    BlockBuilder blockBuilder = pageListBuilder.nextBlockBuilder();
                    Type columnType = columnTypes.get(i);
                    if (columnType.equals(BIGINT) || columnType.equals(DATE)) {
                        long value = resultSet.getLong(i + 1);
                        if (!resultSet.wasNull()) {
                            columnType.writeLong(blockBuilder, value);
                        }
                        else {
                            blockBuilder.appendNull();
                        }
                    }
                    else if (columnType.equals(TIMESTAMP_MILLIS)) {
                        long value = resultSet.getLong(i + 1);
                        if (!resultSet.wasNull()) {
                            columnType.writeLong(blockBuilder, value * MICROSECONDS_PER_MILLISECOND);
                        }
                        else {
                            blockBuilder.appendNull();
                        }
                    }
                    else if (columnType.equals(BOOLEAN)) {
                        boolean value = resultSet.getBoolean(i + 1);
                        if (!resultSet.wasNull()) {
                            BOOLEAN.writeBoolean(blockBuilder, value);
                        }
                        else {
                            blockBuilder.appendNull();
                        }
                    }
                    else {
                        throw new VerifyException("Unknown or unsupported column type: " + columnType);
                    }
                }
            }
        }
        catch (SQLException | JdbiException e) {
            throw metadataError(e);
        }

        return new FixedPageSource(pageListBuilder.build());
    }

    private static boolean isIndexedType(Type type)
    {
        // We only consider the following types in the column_ranges system table
        // Exclude INTEGER because we don't collect column stats for INTEGER type.
        // Exclude DOUBLE because Java double is not completely compatible with MySQL double
        // Exclude VARCHAR because they can be truncated
        return type.equals(BOOLEAN) || type.equals(BIGINT) || type.equals(DATE) || type.equals(TIMESTAMP_MILLIS);
    }

    private static String getColumnRangesMetadataSqlQuery(RaptorTableHandle raptorTableHandle, List<TableColumn> raptorColumns)
    {
        String columns = raptorColumns.stream()
                .flatMap(column -> Stream.of(
                        format("min(%s)", minColumn(column.getColumnId())),
                        format("max(%s)", maxColumn(column.getColumnId()))))
                .collect(joining(", "));
        return format("SELECT %s FROM %s", columns, shardIndexTable(raptorTableHandle.getTableId()));
    }
}
