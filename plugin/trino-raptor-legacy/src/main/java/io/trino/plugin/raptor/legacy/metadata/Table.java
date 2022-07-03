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

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.getBoxedLong;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.getOptionalInt;
import static io.trino.plugin.raptor.legacy.util.DatabaseUtil.getOptionalLong;
import static java.util.Objects.requireNonNull;

public final class Table
{
    private final long tableId;
    private final Optional<Long> distributionId;
    private final Optional<String> distributionName;
    private final OptionalInt bucketCount;
    private final OptionalLong temporalColumnId;
    private final boolean organized;

    public Table(
            long tableId,
            Optional<Long> distributionId,
            Optional<String> distributionName,
            OptionalInt bucketCount,
            OptionalLong temporalColumnId,
            boolean organized)
    {
        this.tableId = tableId;
        this.distributionId = requireNonNull(distributionId, "distributionId is null");
        this.distributionName = requireNonNull(distributionName, "distributionName is null");
        this.bucketCount = requireNonNull(bucketCount, "bucketCount is null");
        this.temporalColumnId = requireNonNull(temporalColumnId, "temporalColumnId is null");
        this.organized = organized;
    }

    public long getTableId()
    {
        return tableId;
    }

    public Optional<Long> getDistributionId()
    {
        return distributionId;
    }

    public Optional<String> getDistributionName()
    {
        return distributionName;
    }

    public OptionalInt getBucketCount()
    {
        return bucketCount;
    }

    public OptionalLong getTemporalColumnId()
    {
        return temporalColumnId;
    }

    public boolean isOrganized()
    {
        return organized;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("tableId", tableId)
                .add("distributionId", distributionId.orElse(null))
                .add("bucketCount", bucketCount.isPresent() ? bucketCount.getAsInt() : null)
                .add("temporalColumnId", temporalColumnId.isPresent() ? temporalColumnId.getAsLong() : null)
                .add("organized", organized)
                .omitNullValues()
                .toString();
    }

    public static class TableMapper
            implements RowMapper<Table>
    {
        @Override
        public Table map(ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return new Table(
                    r.getLong("table_id"),
                    Optional.ofNullable(getBoxedLong(r, "distribution_id")),
                    Optional.ofNullable(r.getString("distribution_name")),
                    getOptionalInt(r, "bucket_count"),
                    getOptionalLong(r, "temporal_column_id"),
                    r.getBoolean("organization_enabled"));
        }
    }
}
