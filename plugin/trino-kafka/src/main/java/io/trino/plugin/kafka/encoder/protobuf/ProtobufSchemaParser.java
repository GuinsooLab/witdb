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
package io.trino.plugin.kafka.encoder.protobuf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.trino.decoder.protobuf.ProtobufRowDecoder;
import io.trino.plugin.kafka.KafkaTopicFieldDescription;
import io.trino.plugin.kafka.KafkaTopicFieldGroup;
import io.trino.plugin.kafka.schema.confluent.SchemaParser;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class ProtobufSchemaParser
        implements SchemaParser
{
    private static final String TIMESTAMP_TYPE_NAME = "google.protobuf.Timestamp";
    private final TypeManager typeManager;

    @Inject
    public ProtobufSchemaParser(TypeManager typeManager)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    @Override
    public KafkaTopicFieldGroup parse(ConnectorSession session, String subject, ParsedSchema parsedSchema)
    {
        ProtobufSchema protobufSchema = (ProtobufSchema) parsedSchema;
        return new KafkaTopicFieldGroup(
                ProtobufRowDecoder.NAME,
                Optional.empty(),
                Optional.of(subject),
                protobufSchema.toDescriptor().getFields().stream()
                        .map(field -> new KafkaTopicFieldDescription(
                                field.getName(),
                                getType(field, ImmutableList.of()),
                                field.getName(),
                                null,
                                null,
                                null,
                                false))
                        .collect(toImmutableList()));
    }

    private Type getType(FieldDescriptor fieldDescriptor, List<FieldAndType> processedMessages)
    {
        Type baseType = switch (fieldDescriptor.getJavaType()) {
            case BOOLEAN -> BOOLEAN;
            case INT -> INTEGER;
            case LONG -> BIGINT;
            case FLOAT -> REAL;
            case DOUBLE -> DOUBLE;
            case BYTE_STRING -> VARBINARY;
            case STRING, ENUM -> createUnboundedVarcharType();
            case MESSAGE -> getTypeForMessage(fieldDescriptor, processedMessages);
        };

        // Protobuf does not support adding repeated label for map type but schema registry incorrectly adds it
        if (fieldDescriptor.isRepeated() && !fieldDescriptor.isMapField()) {
            return new ArrayType(baseType);
        }
        return baseType;
    }

    private Type getTypeForMessage(FieldDescriptor fieldDescriptor, List<FieldAndType> processedMessages)
    {
        Descriptor descriptor = fieldDescriptor.getMessageType();
        if (descriptor.getFullName().equals(TIMESTAMP_TYPE_NAME)) {
            return createTimestampType(6);
        }

        // We MUST check just the type names since same type can be present with different field names which is also recursive
        Set<String> processedMessagesFullTypeNames = processedMessages.stream()
                .map(FieldAndType::fullTypeName)
                .collect(toImmutableSet());
        if (processedMessagesFullTypeNames.contains(descriptor.getFullName())) {
            throw new TrinoException(NOT_SUPPORTED, "Protobuf schema containing fields with self-reference are not supported because they cannot be mapped to a Trino type: %s"
                    .formatted(Streams.concat(processedMessages.stream(), Stream.of(new FieldAndType(fieldDescriptor)))
                            .map(FieldAndType::toString)
                            .collect(joining(" > "))));
        }
        List<FieldAndType> newProcessedMessages = ImmutableList.<FieldAndType>builderWithExpectedSize(processedMessages.size() + 1)
                .addAll(processedMessages)
                .add(new FieldAndType(fieldDescriptor))
                .build();

        if (fieldDescriptor.isMapField()) {
            return new MapType(
                    getType(descriptor.findFieldByNumber(1), newProcessedMessages),
                    getType(descriptor.findFieldByNumber(2), newProcessedMessages),
                    typeManager.getTypeOperators());
        }
        return RowType.from(
                descriptor.getFields().stream()
                        .map(field -> RowType.field(field.getName(), getType(field, newProcessedMessages)))
                        .collect(toImmutableList()));
    }

    public record FieldAndType(String fullFieldName, String fullTypeName)
    {
        public FieldAndType(FieldDescriptor fieldDescriptor)
        {
            this(fieldDescriptor.getFullName(), fieldDescriptor.getMessageType().getFullName());
        }

        @Override
        public String toString()
        {
            return fullFieldName + ": " + fullTypeName;
        }
    }
}
