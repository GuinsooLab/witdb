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
package io.trino.plugin.kinesis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;

import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

/**
 * Class maintains all the properties of Trino Table
 */
public class KinesisTableHandle
        implements ConnectorTableHandle
{
    /**
     * The schema name for this table. Is set through configuration and read
     * using {@link KinesisConfig#getDefaultSchema()}. Usually 'default'.
     */
    private final String schemaName;

    /**
     * The table name used by Trino.
     */
    private final String tableName;

    /**
     * The stream name that is read from Kinesis
     */
    private final String streamName;

    private final String messageDataFormat;

    private final KinesisCompressionCodec compressionCodec;

    @JsonCreator
    public KinesisTableHandle(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("streamName") String streamName,
            @JsonProperty("messageDataFormat") String messageDataFormat,
            @JsonProperty("compressionCodec") KinesisCompressionCodec compressionCodec)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");
        this.streamName = requireNonNull(streamName, "streamName is null");
        this.messageDataFormat = requireNonNull(messageDataFormat, "messageDataFormat is null");
        this.compressionCodec = requireNonNull(compressionCodec, "compressionCodec is null");
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

    @JsonProperty
    public String getStreamName()
    {
        return streamName;
    }

    @JsonProperty
    public String getMessageDataFormat()
    {
        return messageDataFormat;
    }

    @JsonProperty
    public KinesisCompressionCodec getCompressionCodec()
    {
        return compressionCodec;
    }

    public SchemaTableName toSchemaTableName()
    {
        return new SchemaTableName(schemaName, tableName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, tableName, streamName, messageDataFormat, compressionCodec);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        KinesisTableHandle other = (KinesisTableHandle) obj;
        return Objects.equals(this.schemaName, other.schemaName)
                && Objects.equals(this.tableName, other.tableName)
                && Objects.equals(this.streamName, other.streamName)
                && Objects.equals(this.messageDataFormat, other.messageDataFormat)
                && Objects.equals(this.compressionCodec, other.compressionCodec);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("schemaName", schemaName)
                .add("tableName", tableName)
                .add("streamName", streamName)
                .add("messageDataFormat", messageDataFormat)
                .add("compressionCodec", compressionCodec)
                .toString();
    }
}
