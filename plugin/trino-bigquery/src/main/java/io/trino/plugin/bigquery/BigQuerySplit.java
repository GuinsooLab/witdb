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
package io.trino.plugin.bigquery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.trino.spi.HostAddress;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSplit;
import org.openjdk.jol.info.ClassLayout;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.trino.plugin.bigquery.BigQuerySplit.Mode.QUERY;
import static io.trino.plugin.bigquery.BigQuerySplit.Mode.STORAGE;
import static java.util.Objects.requireNonNull;

public class BigQuerySplit
        implements ConnectorSplit
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(BigQuerySplit.class).instanceSize();

    private static final int NO_ROWS_TO_GENERATE = -1;

    private final Mode mode;
    private final String streamName;
    private final String avroSchema;
    private final List<ColumnHandle> columns;
    private final long emptyRowsToGenerate;
    private final Optional<String> filter;

    // do not use directly, it is public only for Jackson
    @JsonCreator
    public BigQuerySplit(
            @JsonProperty("mode") Mode mode,
            @JsonProperty("streamName") String streamName,
            @JsonProperty("avroSchema") String avroSchema,
            @JsonProperty("columns") List<ColumnHandle> columns,
            @JsonProperty("emptyRowsToGenerate") long emptyRowsToGenerate,
            @JsonProperty("filter") Optional<String> filter)
    {
        this.mode = requireNonNull(mode, "mode is null");
        this.streamName = requireNonNull(streamName, "streamName cannot be null");
        this.avroSchema = requireNonNull(avroSchema, "avroSchema cannot be null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns cannot be null"));
        this.emptyRowsToGenerate = emptyRowsToGenerate;
        this.filter = requireNonNull(filter, "filter is null");
    }

    static BigQuerySplit forStream(String streamName, String avroSchema, List<ColumnHandle> columns)
    {
        return new BigQuerySplit(STORAGE, streamName, avroSchema, columns, NO_ROWS_TO_GENERATE, Optional.empty());
    }

    static BigQuerySplit forViewStream(List<ColumnHandle> columns, Optional<String> filter)
    {
        return new BigQuerySplit(QUERY, "", "", columns, NO_ROWS_TO_GENERATE, filter);
    }

    static BigQuerySplit emptyProjection(long numberOfRows)
    {
        return new BigQuerySplit(STORAGE, "", "", ImmutableList.of(), numberOfRows, Optional.empty());
    }

    @JsonProperty
    public Mode getMode()
    {
        return mode;
    }

    @JsonProperty
    public String getStreamName()
    {
        return streamName;
    }

    @JsonProperty
    public String getAvroSchema()
    {
        return avroSchema;
    }

    @JsonProperty
    public List<ColumnHandle> getColumns()
    {
        return columns;
    }

    @JsonProperty
    public long getEmptyRowsToGenerate()
    {
        return emptyRowsToGenerate;
    }

    @JsonProperty
    public Optional<String> getFilter()
    {
        return filter;
    }

    @Override
    public boolean isRemotelyAccessible()
    {
        return true;
    }

    @Override
    public List<HostAddress> getAddresses()
    {
        return ImmutableList.of();
    }

    @Override
    public Object getInfo()
    {
        return this;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + estimatedSizeOf(streamName)
                + estimatedSizeOf(avroSchema)
                + estimatedSizeOf(columns, column -> ((BigQueryColumnHandle) column).getRetainedSizeInBytes());
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BigQuerySplit that = (BigQuerySplit) o;
        return Objects.equals(mode, that.mode) &&
                Objects.equals(streamName, that.streamName) &&
                Objects.equals(avroSchema, that.avroSchema) &&
                Objects.equals(columns, that.columns) &&
                Objects.equals(emptyRowsToGenerate, that.emptyRowsToGenerate);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mode, streamName, avroSchema, columns, emptyRowsToGenerate);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("mode", mode)
                .add("streamName", streamName)
                .add("avroSchema", avroSchema)
                .add("columns", columns)
                .add("emptyRowsToGenerate", emptyRowsToGenerate)
                .toString();
    }

    boolean representsEmptyProjection()
    {
        return emptyRowsToGenerate != NO_ROWS_TO_GENERATE;
    }

    public enum Mode
    {
        STORAGE,
        QUERY,
        /**/;
    }
}
