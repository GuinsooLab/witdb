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
package io.trino.plugin.raptor.legacy.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.jdbi.v3.core.Jdbi;

import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.raptor.legacy.RaptorColumnHandle.isHiddenColumn;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.maxColumn;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.minColumn;
import static io.trino.plugin.raptor.legacy.metadata.DatabaseShardManager.shardIndexTable;
import static io.trino.plugin.raptor.legacy.metadata.ShardPredicate.bindValue;
import static io.trino.plugin.raptor.legacy.storage.ColumnIndexStatsUtils.jdbcType;
import static io.trino.plugin.raptor.legacy.util.ArrayUtil.intArrayToBytes;
import static io.trino.plugin.raptor.legacy.util.UuidUtil.uuidToBytes;

class IndexInserter
        implements AutoCloseable
{
    private final boolean bucketed;
    private final List<ColumnInfo> columns;
    private final Map<Long, Integer> indexes;
    private final Map<Long, JDBCType> types;
    private final PreparedStatement statement;

    public IndexInserter(Connection connection, long tableId, List<ColumnInfo> columns)
            throws SQLException
    {
        this.bucketed = Jdbi.open(connection)
                .select("SELECT distribution_id IS NOT NULL FROM tables WHERE table_id = ?", tableId)
                .mapTo(boolean.class)
                .one();

        ImmutableList.Builder<ColumnInfo> columnBuilder = ImmutableList.builder();
        ImmutableMap.Builder<Long, Integer> indexBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Long, JDBCType> typeBuilder = ImmutableMap.builder();
        StringJoiner nameJoiner = new StringJoiner(", ");
        StringJoiner valueJoiner = new StringJoiner(", ");
        int index = 1;

        nameJoiner.add("shard_id").add("shard_uuid");
        valueJoiner.add("?").add("?").add("?");
        index += 3;

        if (bucketed) {
            nameJoiner.add("bucket_number");
        }
        else {
            nameJoiner.add("node_ids");
        }

        for (ColumnInfo column : columns) {
            JDBCType jdbcType = jdbcType(column.getType());
            if (jdbcType == null) {
                continue;
            }

            long columnId = column.getColumnId();
            if (isHiddenColumn(columnId)) {
                continue;
            }

            columnBuilder.add(column);
            nameJoiner.add(minColumn(columnId));
            nameJoiner.add(maxColumn(columnId));
            valueJoiner.add("?").add("?");

            indexBuilder.put(columnId, index);
            index += 2;

            typeBuilder.put(columnId, jdbcType);
        }

        this.columns = columnBuilder.build();
        this.indexes = indexBuilder.buildOrThrow();
        this.types = typeBuilder.buildOrThrow();

        String sql = "" +
                "INSERT INTO " + shardIndexTable(tableId) + "\n" +
                "(" + nameJoiner + ")\n" +
                "VALUES (" + valueJoiner + ")";

        this.statement = connection.prepareStatement(sql);
    }

    @Override
    public void close()
            throws SQLException
    {
        statement.close();
    }

    public void insert(long shardId, UUID shardUuid, OptionalInt bucketNumber, Set<Integer> nodeIds, List<ColumnStats> stats)
            throws SQLException
    {
        statement.setLong(1, shardId);
        statement.setBytes(2, uuidToBytes(shardUuid));

        if (bucketed) {
            checkArgument(bucketNumber.isPresent(), "shard bucket missing for bucketed table");
            statement.setInt(3, bucketNumber.getAsInt());
        }
        else {
            checkArgument(bucketNumber.isEmpty(), "shard bucket present for non-bucketed table");
            statement.setBytes(3, intArrayToBytes(nodeIds));
        }

        for (ColumnInfo column : columns) {
            int index = indexes.get(column.getColumnId());
            int type = types.get(column.getColumnId()).getVendorTypeNumber();
            statement.setNull(index, type);
            statement.setNull(index + 1, type);
        }

        for (ColumnStats column : stats) {
            if (!indexes.containsKey(column.getColumnId())) {
                // the column no longer exists in the table
                continue;
            }
            int index = indexes.get(column.getColumnId());
            JDBCType type = types.get(column.getColumnId());
            bindValue(statement, type, convert(column.getMin()), index);
            bindValue(statement, type, convert(column.getMax()), index + 1);
        }

        statement.addBatch();
    }

    public void execute()
            throws SQLException
    {
        statement.executeBatch();
    }

    private static Object convert(Object value)
    {
        if (value instanceof String) {
            return utf8Slice((String) value);
        }
        return value;
    }
}
