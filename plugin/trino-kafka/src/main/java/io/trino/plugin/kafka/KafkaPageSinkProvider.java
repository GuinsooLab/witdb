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
package io.trino.plugin.kafka;

import com.google.common.collect.ImmutableList;
import io.trino.plugin.kafka.encoder.DispatchingRowEncoderFactory;
import io.trino.plugin.kafka.encoder.EncoderColumnHandle;
import io.trino.plugin.kafka.encoder.RowEncoder;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorPageSink;
import io.trino.spi.connector.ConnectorPageSinkProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTransactionHandle;

import javax.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import static io.trino.plugin.kafka.KafkaErrorCode.KAFKA_SCHEMA_ERROR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class KafkaPageSinkProvider
        implements ConnectorPageSinkProvider
{
    private final DispatchingRowEncoderFactory encoderFactory;
    private final KafkaProducerFactory producerFactory;

    @Inject
    public KafkaPageSinkProvider(DispatchingRowEncoderFactory encoderFactory, KafkaProducerFactory producerFactory)
    {
        this.encoderFactory = requireNonNull(encoderFactory, "encoderFactory is null");
        this.producerFactory = requireNonNull(producerFactory, "producerFactory is null");
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorOutputTableHandle tableHandle)
    {
        throw new UnsupportedOperationException("Table creation is not supported by the kafka connector");
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorInsertTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        KafkaTableHandle handle = (KafkaTableHandle) tableHandle;

        ImmutableList.Builder<EncoderColumnHandle> keyColumns = ImmutableList.builder();
        ImmutableList.Builder<EncoderColumnHandle> messageColumns = ImmutableList.builder();
        handle.getColumns().forEach(col -> {
            if (col.isInternal()) {
                throw new IllegalArgumentException(format("unexpected internal column '%s'", col.getName()));
            }
            if (col.isKeyCodec()) {
                keyColumns.add(col);
            }
            else {
                messageColumns.add(col);
            }
        });

        RowEncoder keyEncoder = encoderFactory.create(
                session,
                handle.getKeyDataFormat(),
                getDataSchema(handle.getKeyDataSchemaLocation()),
                keyColumns.build());

        RowEncoder messageEncoder = encoderFactory.create(
                session,
                handle.getMessageDataFormat(),
                getDataSchema(handle.getMessageDataSchemaLocation()),
                messageColumns.build());

        return new KafkaPageSink(
                handle.getTopicName(),
                handle.getColumns(),
                keyEncoder,
                messageEncoder,
                producerFactory,
                session);
    }

    private static Optional<String> getDataSchema(Optional<String> dataSchemaLocation)
    {
        return dataSchemaLocation.map(location -> {
            try {
                return Files.readString(Paths.get(location));
            }
            catch (IOException e) {
                throw new TrinoException(KAFKA_SCHEMA_ERROR, format("Unable to read data schema at '%s'", location), e);
            }
        });
    }
}
