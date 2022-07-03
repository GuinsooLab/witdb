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
package io.trino.plugin.cassandra;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.data.GettableByIndex;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.util.Bytes;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import io.airlift.slice.Slice;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.RowBlockBuilder;
import io.trino.spi.block.SingleRowBlockWriter;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.RealType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.TinyintType;
import io.trino.spi.type.Type;
import io.trino.spi.type.UuidType;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.InetAddresses.toAddrString;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.quoteStringLiteral;
import static io.trino.plugin.cassandra.util.CassandraCqlUtils.quoteStringLiteralForJson;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.TypeUtils.writeNativeValue;
import static io.trino.spi.type.UuidType.javaUuidToTrinoUuid;
import static io.trino.spi.type.UuidType.trinoUuidToJavaUuid;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class CassandraType
{
    public enum Kind
    {
        BOOLEAN,
        TINYINT,
        SMALLINT,
        INT,
        BIGINT,
        FLOAT,
        DOUBLE,
        DECIMAL,
        DATE,
        TIMESTAMP,
        ASCII,
        TEXT,
        VARCHAR,
        BLOB,
        UUID,
        TIMEUUID,
        COUNTER,
        VARINT,
        INET,
        CUSTOM,
        LIST,
        SET,
        MAP,
        TUPLE,
        UDT,
    }

    private final Kind kind;
    private final Type trinoType;
    private final List<CassandraType> argumentTypes;

    public CassandraType(
            Kind kind,
            Type trinoType)
    {
        this(kind, trinoType, ImmutableList.of());
    }

    @JsonCreator
    public CassandraType(
            @JsonProperty("kind") Kind kind,
            @JsonProperty("trinoType") Type trinoType,
            @JsonProperty("argumentTypes") List<CassandraType> argumentTypes)
    {
        this.kind = requireNonNull(kind, "kind is null");
        this.trinoType = requireNonNull(trinoType, "trinoType is null");
        this.argumentTypes = ImmutableList.copyOf(requireNonNull(argumentTypes, "argumentTypes is null"));
    }

    @JsonProperty
    public Kind getKind()
    {
        return kind;
    }

    @JsonProperty
    public Type getTrinoType()
    {
        return trinoType;
    }

    @JsonProperty
    public List<CassandraType> getArgumentTypes()
    {
        return argumentTypes;
    }

    public String getName()
    {
        return kind.name();
    }

    public static Optional<CassandraType> toCassandraType(DataType dataType)
    {
        switch (dataType.getProtocolCode()) {
            case ProtocolConstants.DataType.ASCII:
                return Optional.of(CassandraTypes.ASCII);
            case ProtocolConstants.DataType.BIGINT:
                return Optional.of(CassandraTypes.BIGINT);
            case ProtocolConstants.DataType.BLOB:
                return Optional.of(CassandraTypes.BLOB);
            case ProtocolConstants.DataType.BOOLEAN:
                return Optional.of(CassandraTypes.BOOLEAN);
            case ProtocolConstants.DataType.COUNTER:
                return Optional.of(CassandraTypes.COUNTER);
            case ProtocolConstants.DataType.CUSTOM:
                return Optional.of(CassandraTypes.CUSTOM);
            case ProtocolConstants.DataType.DATE:
                return Optional.of(CassandraTypes.DATE);
            case ProtocolConstants.DataType.DECIMAL:
                return Optional.of(CassandraTypes.DECIMAL);
            case ProtocolConstants.DataType.DOUBLE:
                return Optional.of(CassandraTypes.DOUBLE);
            case ProtocolConstants.DataType.FLOAT:
                return Optional.of(CassandraTypes.FLOAT);
            case ProtocolConstants.DataType.INET:
                return Optional.of(CassandraTypes.INET);
            case ProtocolConstants.DataType.INT:
                return Optional.of(CassandraTypes.INT);
            case ProtocolConstants.DataType.LIST:
                return Optional.of(CassandraTypes.LIST);
            case ProtocolConstants.DataType.MAP:
                return Optional.of(CassandraTypes.MAP);
            case ProtocolConstants.DataType.SET:
                return Optional.of(CassandraTypes.SET);
            case ProtocolConstants.DataType.SMALLINT:
                return Optional.of(CassandraTypes.SMALLINT);
            case ProtocolConstants.DataType.TIMESTAMP:
                return Optional.of(CassandraTypes.TIMESTAMP);
            case ProtocolConstants.DataType.TIMEUUID:
                return Optional.of(CassandraTypes.TIMEUUID);
            case ProtocolConstants.DataType.TINYINT:
                return Optional.of(CassandraTypes.TINYINT);
            case ProtocolConstants.DataType.TUPLE:
                return createTypeForTuple(dataType);
            case ProtocolConstants.DataType.UDT:
                return createTypeForUserType(dataType);
            case ProtocolConstants.DataType.UUID:
                return Optional.of(CassandraTypes.UUID);
            case ProtocolConstants.DataType.VARCHAR:
                return Optional.of(CassandraTypes.VARCHAR);
            case ProtocolConstants.DataType.VARINT:
                return Optional.of(CassandraTypes.VARINT);
            default:
                return Optional.empty();
        }
    }

    private static Optional<CassandraType> createTypeForTuple(DataType dataType)
    {
        TupleType tupleType = (TupleType) dataType;
        List<Optional<CassandraType>> argumentTypesOptionals = tupleType.getComponentTypes().stream()
                .map(CassandraType::toCassandraType)
                .collect(toImmutableList());

        if (argumentTypesOptionals.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }

        List<CassandraType> argumentTypes = argumentTypesOptionals.stream()
                .map(Optional::get)
                .collect(toImmutableList());

        RowType trinoType = RowType.anonymous(
                argumentTypes.stream()
                        .map(CassandraType::getTrinoType)
                        .collect(toImmutableList()));

        return Optional.of(new CassandraType(Kind.TUPLE, trinoType, argumentTypes));
    }

    private static Optional<CassandraType> createTypeForUserType(DataType dataType)
    {
        UserDefinedType userDefinedType = (UserDefinedType) dataType;
        // Using ImmutableMap is important as we exploit the fact that entries iteration order matches the order of putting values via builder
        ImmutableMap.Builder<String, CassandraType> argumentTypes = ImmutableMap.builder();

        List<CqlIdentifier> fieldNames = userDefinedType.getFieldNames();
        List<DataType> fieldTypes = userDefinedType.getFieldTypes();
        if (fieldNames.size() != fieldTypes.size()) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, format("Mismatch between the number of field names (%s) and the number of field types (%s) for the data type %s", fieldNames.size(), fieldTypes.size(), dataType));
        }
        for (int i = 0; i < fieldNames.size(); i++) {
            Optional<CassandraType> cassandraType = CassandraType.toCassandraType(fieldTypes.get(i));
            if (cassandraType.isEmpty()) {
                return Optional.empty();
            }
            argumentTypes.put(fieldNames.get(i).toString(), cassandraType.get());
        }

        RowType trinoType = RowType.from(
                argumentTypes.buildOrThrow().entrySet().stream()
                        .map(field -> new RowType.Field(Optional.of(field.getKey()), field.getValue().getTrinoType()))
                        .collect(toImmutableList()));

        return Optional.of(new CassandraType(Kind.UDT, trinoType, argumentTypes.buildOrThrow().values().stream().collect(toImmutableList())));
    }

    public NullableValue getColumnValue(Row row, int position)
    {
        return getColumnValue(row, position, () -> row.getColumnDefinitions().get(position).getType());
    }

    public NullableValue getColumnValue(GettableByIndex row, int position, Supplier<DataType> dataTypeSupplier)
    {
        if (row.isNull(position)) {
            return NullableValue.asNull(trinoType);
        }

        switch (kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                return NullableValue.of(trinoType, utf8Slice(row.getString(position)));
            case INT:
                return NullableValue.of(trinoType, (long) row.getInt(position));
            case SMALLINT:
                return NullableValue.of(trinoType, (long) row.getShort(position));
            case TINYINT:
                return NullableValue.of(trinoType, (long) row.getByte(position));
            case BIGINT:
            case COUNTER:
                return NullableValue.of(trinoType, row.getLong(position));
            case BOOLEAN:
                return NullableValue.of(trinoType, row.getBoolean(position));
            case DOUBLE:
                return NullableValue.of(trinoType, row.getDouble(position));
            case FLOAT:
                return NullableValue.of(trinoType, (long) floatToRawIntBits(row.getFloat(position)));
            case DECIMAL:
                return NullableValue.of(trinoType, row.getBigDecimal(position).doubleValue());
            case UUID:
            case TIMEUUID:
                return NullableValue.of(trinoType, javaUuidToTrinoUuid(row.getUuid(position)));
            case TIMESTAMP:
                return NullableValue.of(trinoType, packDateTimeWithZone(row.getInstant(position).toEpochMilli(), TimeZoneKey.UTC_KEY));
            case DATE:
                return NullableValue.of(trinoType, row.getLocalDate(position).toEpochDay());
            case INET:
                return NullableValue.of(trinoType, utf8Slice(toAddrString(row.getInetAddress(position))));
            case VARINT:
                return NullableValue.of(trinoType, utf8Slice(row.getBigInteger(position).toString()));
            case BLOB:
            case CUSTOM:
                return NullableValue.of(trinoType, wrappedBuffer(row.getBytesUnsafe(position)));
            case SET:
                return NullableValue.of(trinoType, utf8Slice(buildArrayValueFromSetType(row, position, dataTypeSupplier.get())));
            case LIST:
                return NullableValue.of(trinoType, utf8Slice(buildArrayValueFromListType(row, position, dataTypeSupplier.get())));
            case MAP:
                return NullableValue.of(trinoType, utf8Slice(buildMapValue(row, position, dataTypeSupplier.get())));
            case TUPLE:
                return NullableValue.of(trinoType, buildTupleValue(row, position));
            case UDT:
                return NullableValue.of(trinoType, buildUserTypeValue(row, position));
        }
        throw new IllegalStateException("Handling of type " + this + " is not implemented");
    }

    private static String buildMapValue(GettableByIndex row, int position, DataType dataType)
    {
        checkArgument(dataType instanceof MapType, "Expected to deal with an instance of %s class, got: %s", MapType.class, dataType);
        MapType mapType = (MapType) dataType;
        return buildMapValue((Map<?, ?>) row.getObject(position), mapType.getKeyType(), mapType.getValueType());
    }

    private static String buildMapValue(Map<?, ?> cassandraMap, DataType keyType, DataType valueType)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (Map.Entry<?, ?> entry : cassandraMap.entrySet()) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append(objectToJson(entry.getKey(), keyType));
            sb.append(":");
            sb.append(objectToJson(entry.getValue(), valueType));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildArrayValueFromSetType(GettableByIndex row, int position, DataType type)
    {
        checkArgument(type instanceof SetType, "Expected to deal with an instance of %s class, got: %s", SetType.class, type);
        SetType setType = (SetType) type;
        return buildArrayValue((Collection<?>) row.getObject(position), setType.getElementType());
    }

    private static String buildArrayValueFromListType(GettableByIndex row, int position, DataType type)
    {
        checkArgument(type instanceof ListType, "Expected to deal with an instance of %s class, got: %s", ListType.class, type);
        ListType listType = (ListType) type;
        return buildArrayValue((Collection<?>) row.getObject(position), listType.getElementType());
    }

    @VisibleForTesting
    static String buildArrayValue(Collection<?> cassandraCollection, DataType elementType)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (Object value : cassandraCollection) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append(objectToJson(value, elementType));
        }
        sb.append("]");
        return sb.toString();
    }

    private Block buildTupleValue(GettableByIndex row, int position)
    {
        verify(this.kind == Kind.TUPLE, "Not a TUPLE type");
        TupleValue tupleValue = row.getTupleValue(position);
        RowBlockBuilder blockBuilder = (RowBlockBuilder) this.trinoType.createBlockBuilder(null, 1);
        SingleRowBlockWriter singleRowBlockWriter = blockBuilder.beginBlockEntry();
        int tuplePosition = 0;
        for (CassandraType argumentType : this.getArgumentTypes()) {
            int finalTuplePosition = tuplePosition;
            NullableValue value = argumentType.getColumnValue(tupleValue, tuplePosition, () -> tupleValue.getType().getComponentTypes().get(finalTuplePosition));
            writeNativeValue(argumentType.getTrinoType(), singleRowBlockWriter, value.getValue());
            tuplePosition++;
        }
        // can I just return singleRowBlockWriter here? It extends AbstractSingleRowBlock and tests pass.
        blockBuilder.closeEntry();
        return (Block) this.trinoType.getObject(blockBuilder, 0);
    }

    private Block buildUserTypeValue(GettableByIndex row, int position)
    {
        verify(this.kind == Kind.UDT, "Not a user defined type: %s", this.kind);
        UdtValue udtValue = row.getUdtValue(position);
        RowBlockBuilder blockBuilder = (RowBlockBuilder) this.trinoType.createBlockBuilder(null, 1);
        SingleRowBlockWriter singleRowBlockWriter = blockBuilder.beginBlockEntry();
        int tuplePosition = 0;
        List<DataType> udtTypeFieldTypes = udtValue.getType().getFieldTypes();
        for (CassandraType argumentType : this.getArgumentTypes()) {
            int finalTuplePosition = tuplePosition;
            NullableValue value = argumentType.getColumnValue(udtValue, tuplePosition, () -> udtTypeFieldTypes.get(finalTuplePosition));
            writeNativeValue(argumentType.getTrinoType(), singleRowBlockWriter, value.getValue());
            tuplePosition++;
        }

        blockBuilder.closeEntry();
        return (Block) this.trinoType.getObject(blockBuilder, 0);
    }

    // TODO unify with toCqlLiteral
    public String getColumnValueForCql(Row row, int position)
    {
        if (row.isNull(position)) {
            return null;
        }

        switch (kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                return quoteStringLiteral(row.getString(position));
            case INT:
                return Integer.toString(row.getInt(position));
            case SMALLINT:
                return Short.toString(row.getShort(position));
            case TINYINT:
                return Byte.toString(row.getByte(position));
            case BIGINT:
            case COUNTER:
                return Long.toString(row.getLong(position));
            case BOOLEAN:
                return Boolean.toString(row.getBool(position));
            case DOUBLE:
                return Double.toString(row.getDouble(position));
            case FLOAT:
                return Float.toString(row.getFloat(position));
            case DECIMAL:
                return row.getBigDecimal(position).toString();
            case UUID:
            case TIMEUUID:
                return row.getUuid(position).toString();
            case TIMESTAMP:
                return Long.toString(row.getInstant(position).toEpochMilli());
            case DATE:
                return quoteStringLiteral(row.getLocalDate(position).toString());
            case INET:
                return quoteStringLiteral(toAddrString(row.getInetAddress(position)));
            case VARINT:
                return row.getBigInteger(position).toString();
            case BLOB:
            case CUSTOM:
                return Bytes.toHexString(row.getBytesUnsafe(position));

            case LIST:
            case SET:
            case MAP:
            case TUPLE:
            case UDT:
                // unsupported
                break;
        }
        throw new IllegalStateException("Handling of type " + this + " is not implemented");
    }

    // TODO unify with getColumnValueForCql
    public String toCqlLiteral(Object trinoNativeValue)
    {
        if (kind == Kind.DATE) {
            LocalDate date = LocalDate.ofEpochDay(toIntExact((long) trinoNativeValue));
            return quoteStringLiteral(date.toString());
        }
        if (kind == Kind.TIMESTAMP) {
            return String.valueOf(unpackMillisUtc((Long) trinoNativeValue));
        }

        String value;
        if (trinoNativeValue instanceof Slice) {
            value = ((Slice) trinoNativeValue).toStringUtf8();
        }
        else {
            value = trinoNativeValue.toString();
        }

        switch (kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                return quoteStringLiteral(value);
            case INET:
                // remove '/' in the string. e.g. /127.0.0.1
                return quoteStringLiteral(value.substring(1));
            default:
                return value;
        }
    }

    private static String objectToJson(Object cassandraValue, DataType dataType)
    {
        CassandraType cassandraType = toCassandraType(dataType)
                .orElseThrow(() -> new IllegalStateException("Unsupported type: " + dataType));

        switch (cassandraType.kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
            case UUID:
            case TIMEUUID:
            case TIMESTAMP:
            case DATE:
            case INET:
            case VARINT:
            case TUPLE:
            case UDT:
                return quoteStringLiteralForJson(cassandraValue.toString());

            case BLOB:
            case CUSTOM:
                return quoteStringLiteralForJson(Bytes.toHexString((ByteBuffer) cassandraValue));

            case SMALLINT:
            case TINYINT:
            case INT:
            case BIGINT:
            case COUNTER:
            case BOOLEAN:
            case DOUBLE:
            case FLOAT:
            case DECIMAL:
                return cassandraValue.toString();
            case LIST:
                checkArgument(dataType instanceof ListType, "Expected to deal with an instance of %s class, got: %s", ListType.class, dataType);
                ListType listType = (ListType) dataType;
                return buildArrayValue((Collection<?>) cassandraValue, listType.getElementType());
            case SET:
                checkArgument(dataType instanceof SetType, "Expected to deal with an instance of %s class, got: %s", SetType.class, dataType);
                SetType setType = (SetType) dataType;
                return buildArrayValue((Collection<?>) cassandraValue, setType.getElementType());
            case MAP:
                checkArgument(dataType instanceof MapType, "Expected to deal with an instance of %s class, got: %s", MapType.class, dataType);
                MapType mapType = (MapType) dataType;
                return buildMapValue((Map<?, ?>) cassandraValue, mapType.getKeyType(), mapType.getValueType());
        }
        throw new IllegalStateException("Unsupported type: " + cassandraType);
    }

    public Object getJavaValue(Object trinoNativeValue)
    {
        switch (kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
                return ((Slice) trinoNativeValue).toStringUtf8();
            case BIGINT:
            case BOOLEAN:
            case DOUBLE:
            case COUNTER:
                return trinoNativeValue;
            case INET:
                return InetAddresses.forString(((Slice) trinoNativeValue).toStringUtf8());
            case INT:
            case SMALLINT:
            case TINYINT:
                return ((Long) trinoNativeValue).intValue();
            case FLOAT:
                // conversion can result in precision lost
                return intBitsToFloat(((Long) trinoNativeValue).intValue());
            case DECIMAL:
                // conversion can result in precision lost
                // Trino uses double for decimal, so to keep the floating point precision, convert it to string.
                // Otherwise partition id doesn't match
                return new BigDecimal(trinoNativeValue.toString());
            case TIMESTAMP:
                return Instant.ofEpochMilli(unpackMillisUtc((Long) trinoNativeValue));
            case DATE:
                return LocalDate.ofEpochDay(((Long) trinoNativeValue).intValue());
            case UUID:
            case TIMEUUID:
                return trinoUuidToJavaUuid((Slice) trinoNativeValue);
            case BLOB:
            case CUSTOM:
            case TUPLE:
            case UDT:
                return ((Slice) trinoNativeValue).toStringUtf8();
            case VARINT:
                return new BigInteger(((Slice) trinoNativeValue).toStringUtf8());
            case SET:
            case LIST:
            case MAP:
        }
        throw new IllegalStateException("Back conversion not implemented for " + this);
    }

    public boolean isSupportedPartitionKey()
    {
        switch (kind) {
            case ASCII:
            case TEXT:
            case VARCHAR:
            case BIGINT:
            case BOOLEAN:
            case DOUBLE:
            case INET:
            case INT:
            case TINYINT:
            case SMALLINT:
            case FLOAT:
            case DECIMAL:
            case DATE:
            case TIMESTAMP:
            case UUID:
            case TIMEUUID:
                return true;
            case COUNTER:
            case BLOB:
            case CUSTOM:
            case VARINT:
            case SET:
            case LIST:
            case MAP:
            case TUPLE:
            case UDT:
            default:
                return false;
        }
    }

    public static boolean isFullySupported(DataType dataType)
    {
        if (toCassandraType(dataType).isEmpty()) {
            return false;
        }

        if (dataType instanceof UserDefinedType) {
            return ((UserDefinedType) dataType).getFieldTypes().stream()
                    .allMatch(CassandraType::isFullySupported);
        }

        if (dataType instanceof MapType) {
            MapType mapType = (MapType) dataType;
            return Arrays.stream(new DataType[] {mapType.getKeyType(), mapType.getValueType()})
                    .allMatch(CassandraType::isFullySupported);
        }

        if (dataType instanceof ListType) {
            return CassandraType.isFullySupported(((ListType) dataType).getElementType());
        }

        if (dataType instanceof TupleType) {
            return ((TupleType) dataType).getComponentTypes().stream()
                    .allMatch(CassandraType::isFullySupported);
        }

        if (dataType instanceof SetType) {
            return CassandraType.isFullySupported(((SetType) dataType).getElementType());
        }

        return true;
    }

    public static CassandraType toCassandraType(Type type, ProtocolVersion protocolVersion)
    {
        if (type.equals(BooleanType.BOOLEAN)) {
            return CassandraTypes.BOOLEAN;
        }
        if (type.equals(BigintType.BIGINT)) {
            return CassandraTypes.BIGINT;
        }
        if (type.equals(IntegerType.INTEGER)) {
            return CassandraTypes.INT;
        }
        if (type.equals(SmallintType.SMALLINT)) {
            return CassandraTypes.SMALLINT;
        }
        if (type.equals(TinyintType.TINYINT)) {
            return CassandraTypes.TINYINT;
        }
        if (type.equals(DoubleType.DOUBLE)) {
            return CassandraTypes.DOUBLE;
        }
        if (type.equals(RealType.REAL)) {
            return CassandraTypes.FLOAT;
        }
        if (type instanceof VarcharType) {
            return CassandraTypes.TEXT;
        }
        if (type.equals(DateType.DATE)) {
            return protocolVersion.getCode() <= ProtocolVersion.V3.getCode()
                    ? CassandraTypes.TEXT
                    : CassandraTypes.DATE;
        }
        if (type.equals(VarbinaryType.VARBINARY)) {
            return CassandraTypes.BLOB;
        }
        if (type.equals(TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS)) {
            return CassandraTypes.TIMESTAMP;
        }
        if (type.equals(UuidType.UUID)) {
            return CassandraTypes.UUID;
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported type: " + type);
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
        CassandraType that = (CassandraType) o;
        return kind == that.kind && Objects.equals(trinoType, that.trinoType) && Objects.equals(argumentTypes, that.argumentTypes);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(kind, trinoType, argumentTypes);
    }

    @Override
    public String toString()
    {
        String result = format("%s(%s", kind, trinoType);
        if (!argumentTypes.isEmpty()) {
            result += "; " + argumentTypes;
        }
        result += ")";
        return result;
    }
}
