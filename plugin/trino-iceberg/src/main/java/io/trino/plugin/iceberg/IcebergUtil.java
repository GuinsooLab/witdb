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

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;
import io.airlift.slice.Slices;
import io.trino.plugin.iceberg.PartitionTransforms.ColumnTransform;
import io.trino.plugin.iceberg.catalog.IcebergTableOperations;
import io.trino.plugin.iceberg.catalog.IcebergTableOperationsProvider;
import io.trino.plugin.iceberg.catalog.TrinoCatalog;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.function.InvocationConvention;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.UuidType;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.types.Type.PrimitiveType;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StructType;

import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Lists.reverse;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.hive.HiveMetadata.TABLE_COMMENT;
import static io.trino.plugin.iceberg.ColumnIdentity.createColumnIdentity;
import static io.trino.plugin.iceberg.IcebergErrorCode.ICEBERG_INVALID_PARTITION_VALUE;
import static io.trino.plugin.iceberg.IcebergErrorCode.ICEBERG_INVALID_SNAPSHOT_ID;
import static io.trino.plugin.iceberg.IcebergMetadata.ORC_BLOOM_FILTER_COLUMNS_KEY;
import static io.trino.plugin.iceberg.IcebergMetadata.ORC_BLOOM_FILTER_FPP_KEY;
import static io.trino.plugin.iceberg.IcebergTableProperties.FILE_FORMAT_PROPERTY;
import static io.trino.plugin.iceberg.IcebergTableProperties.FORMAT_VERSION_PROPERTY;
import static io.trino.plugin.iceberg.IcebergTableProperties.LOCATION_PROPERTY;
import static io.trino.plugin.iceberg.IcebergTableProperties.ORC_BLOOM_FILTER_COLUMNS;
import static io.trino.plugin.iceberg.IcebergTableProperties.ORC_BLOOM_FILTER_FPP;
import static io.trino.plugin.iceberg.IcebergTableProperties.PARTITIONING_PROPERTY;
import static io.trino.plugin.iceberg.IcebergTableProperties.getOrcBloomFilterColumns;
import static io.trino.plugin.iceberg.IcebergTableProperties.getOrcBloomFilterFpp;
import static io.trino.plugin.iceberg.IcebergTableProperties.getPartitioning;
import static io.trino.plugin.iceberg.IcebergTableProperties.getTableLocation;
import static io.trino.plugin.iceberg.PartitionFields.parsePartitionFields;
import static io.trino.plugin.iceberg.PartitionFields.toPartitionFields;
import static io.trino.plugin.iceberg.TrinoTypes.getNextValue;
import static io.trino.plugin.iceberg.TrinoTypes.getPreviousValue;
import static io.trino.plugin.iceberg.TypeConverter.toIcebergType;
import static io.trino.plugin.iceberg.TypeConverter.toTrinoType;
import static io.trino.plugin.iceberg.util.Timestamps.timestampTzFromMicros;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static io.trino.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.predicate.Utils.nativeValueToBlock;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.Decimals.isLongDecimal;
import static io.trino.spi.type.Decimals.isShortDecimal;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.TimeType.TIME_MICROS;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MICROS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.UuidType.javaUuidToTrinoUuid;
import static java.lang.Double.parseDouble;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.parseFloat;
import static java.lang.Long.parseLong;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static org.apache.iceberg.BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE;
import static org.apache.iceberg.BaseMetastoreTableOperations.TABLE_TYPE_PROP;
import static org.apache.iceberg.LocationProviders.locationsFor;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.apache.iceberg.TableProperties.FORMAT_VERSION;
import static org.apache.iceberg.TableProperties.OBJECT_STORE_PATH;
import static org.apache.iceberg.TableProperties.WRITE_DATA_LOCATION;
import static org.apache.iceberg.TableProperties.WRITE_LOCATION_PROVIDER_IMPL;
import static org.apache.iceberg.TableProperties.WRITE_METADATA_LOCATION;
import static org.apache.iceberg.TableProperties.WRITE_NEW_DATA_LOCATION;
import static org.apache.iceberg.types.Type.TypeID.BINARY;
import static org.apache.iceberg.types.Type.TypeID.FIXED;

public final class IcebergUtil
{
    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z][a-z0-9]*");

    private IcebergUtil() {}

    public static boolean isIcebergTable(io.trino.plugin.hive.metastore.Table table)
    {
        return ICEBERG_TABLE_TYPE_VALUE.equalsIgnoreCase(table.getParameters().get(TABLE_TYPE_PROP));
    }

    public static Table loadIcebergTable(TrinoCatalog catalog, IcebergTableOperationsProvider tableOperationsProvider, ConnectorSession session, SchemaTableName table)
    {
        TableOperations operations = tableOperationsProvider.createTableOperations(
                catalog,
                session,
                table.getSchemaName(),
                table.getTableName(),
                Optional.empty(),
                Optional.empty());
        return new BaseTable(operations, quotedTableName(table));
    }

    public static Table getIcebergTableWithMetadata(
            TrinoCatalog catalog,
            IcebergTableOperationsProvider tableOperationsProvider,
            ConnectorSession session,
            SchemaTableName table,
            TableMetadata tableMetadata)
    {
        IcebergTableOperations operations = tableOperationsProvider.createTableOperations(
                catalog,
                session,
                table.getSchemaName(),
                table.getTableName(),
                Optional.empty(),
                Optional.empty());
        operations.initializeFromMetadata(tableMetadata);
        return new BaseTable(operations, quotedTableName(table));
    }

    public static Map<String, Object> getIcebergTableProperties(Table icebergTable)
    {
        ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        properties.put(FILE_FORMAT_PROPERTY, getFileFormat(icebergTable));
        if (!icebergTable.spec().fields().isEmpty()) {
            properties.put(PARTITIONING_PROPERTY, toPartitionFields(icebergTable.spec()));
        }

        if (!icebergTable.location().isEmpty()) {
            properties.put(LOCATION_PROPERTY, icebergTable.location());
        }

        int formatVersion = ((BaseTable) icebergTable).operations().current().formatVersion();
        properties.put(FORMAT_VERSION_PROPERTY, formatVersion);

        // iceberg ORC format bloom filter properties
        String orcBloomFilterColumns = icebergTable.properties().get(ORC_BLOOM_FILTER_COLUMNS_KEY);
        if (orcBloomFilterColumns != null) {
            properties.put(ORC_BLOOM_FILTER_COLUMNS, Splitter.on(',').trimResults().omitEmptyStrings().splitToList(orcBloomFilterColumns));
        }
        String orcBloomFilterFpp = icebergTable.properties().get(ORC_BLOOM_FILTER_FPP_KEY);
        if (orcBloomFilterFpp != null) {
            properties.put(ORC_BLOOM_FILTER_FPP, Double.parseDouble(orcBloomFilterFpp));
        }

        return properties.buildOrThrow();
    }

    @Deprecated
    public static long resolveSnapshotId(Table table, long snapshotId, boolean allowLegacySnapshotSyntax)
    {
        if (!allowLegacySnapshotSyntax) {
            throw new TrinoException(
                    NOT_SUPPORTED,
                    format(
                            "Failed to access snapshot %s for table %s. This syntax for accessing Iceberg tables is not "
                                    + "supported. Use the AS OF syntax OR set the catalog session property "
                                    + "allow_legacy_snapshot_syntax=true for temporarily restoring previous behavior.",
                            snapshotId,
                            table.name()));
        }

        if (table.snapshot(snapshotId) != null) {
            return snapshotId;
        }

        return reverse(table.history()).stream()
                .filter(entry -> entry.timestampMillis() <= snapshotId)
                .map(HistoryEntry::snapshotId)
                .findFirst()
                .orElseThrow(() -> new TrinoException(ICEBERG_INVALID_SNAPSHOT_ID, format("Invalid snapshot [%s] for table: %s", snapshotId, table)));
    }

    public static List<IcebergColumnHandle> getColumns(Schema schema, TypeManager typeManager)
    {
        return schema.columns().stream()
                .map(column -> getColumnHandle(column, typeManager))
                .collect(toImmutableList());
    }

    public static IcebergColumnHandle getColumnHandle(NestedField column, TypeManager typeManager)
    {
        Type type = toTrinoType(column.type(), typeManager);
        return new IcebergColumnHandle(
                createColumnIdentity(column),
                type,
                ImmutableList.of(),
                type,
                Optional.ofNullable(column.doc()));
    }

    public static Map<PartitionField, Integer> getIdentityPartitions(PartitionSpec partitionSpec)
    {
        // TODO: expose transform information in Iceberg library
        ImmutableMap.Builder<PartitionField, Integer> columns = ImmutableMap.builder();
        for (int i = 0; i < partitionSpec.fields().size(); i++) {
            PartitionField field = partitionSpec.fields().get(i);
            if (field.transform().isIdentity()) {
                columns.put(field, i);
            }
        }
        return columns.buildOrThrow();
    }

    public static Map<Integer, PrimitiveType> primitiveFieldTypes(Schema schema)
    {
        return primitiveFieldTypes(schema.columns())
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    private static Stream<Entry<Integer, PrimitiveType>> primitiveFieldTypes(List<NestedField> nestedFields)
    {
        return nestedFields.stream()
                .flatMap(IcebergUtil::primitiveFieldTypes);
    }

    private static Stream<Entry<Integer, PrimitiveType>> primitiveFieldTypes(NestedField nestedField)
    {
        org.apache.iceberg.types.Type fieldType = nestedField.type();
        if (fieldType.isPrimitiveType()) {
            return Stream.of(Map.entry(nestedField.fieldId(), fieldType.asPrimitiveType()));
        }

        if (fieldType.isNestedType()) {
            return primitiveFieldTypes(fieldType.asNestedType().fields());
        }

        throw new IllegalStateException("Unsupported field type: " + nestedField);
    }

    public static IcebergFileFormat getFileFormat(Table table)
    {
        return getFileFormat(table.properties());
    }

    public static IcebergFileFormat getFileFormat(Map<String, String> storageProperties)
    {
        return IcebergFileFormat.fromIceberg(FileFormat.valueOf(storageProperties
                .getOrDefault(DEFAULT_FILE_FORMAT, DEFAULT_FILE_FORMAT_DEFAULT)
                .toUpperCase(Locale.ENGLISH)));
    }

    public static Optional<String> getTableComment(Table table)
    {
        return Optional.ofNullable(table.properties().get(TABLE_COMMENT));
    }

    public static String quotedTableName(SchemaTableName name)
    {
        return quotedName(name.getSchemaName()) + "." + quotedName(name.getTableName());
    }

    private static String quotedName(String name)
    {
        if (SIMPLE_NAME.matcher(name).matches()) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    public static boolean canEnforceColumnConstraintInAllSpecs(TypeOperators typeOperators, Table table, IcebergColumnHandle columnHandle, Domain domain)
    {
        return table.specs().values().stream()
                .allMatch(spec -> canEnforceConstraintWithinPartitioningSpec(typeOperators, spec, columnHandle, domain));
    }

    private static boolean canEnforceConstraintWithinPartitioningSpec(TypeOperators typeOperators, PartitionSpec spec, IcebergColumnHandle column, Domain domain)
    {
        for (PartitionField field : spec.getFieldsBySourceId(column.getId())) {
            if (canEnforceConstraintWithPartitionField(typeOperators, field, column, domain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canEnforceConstraintWithPartitionField(TypeOperators typeOperators, PartitionField field, IcebergColumnHandle column, Domain domain)
    {
        if (field.transform().toString().equals("void")) {
            // Useless for filtering.
            return false;
        }
        if (field.transform().isIdentity()) {
            // A predicate on an identity partitioning column can always be enforced.
            return true;
        }

        ColumnTransform transform = PartitionTransforms.getColumnTransform(field, column.getType());
        if (transform.preservesNonNull()) {
            // Partitioning transform must return NULL for NULL input.
            // Below we assume it never returns NULL for non-NULL input,
            // so NULL values and non-NULL values are always segregated.
            // In practice, this condition matches the void transform only,
            // which isn't useful for filtering anyway.
            return false;
        }
        ValueSet valueSet = domain.getValues();

        boolean canEnforce = valueSet.getValuesProcessor().transform(
                ranges -> {
                    MethodHandle targetTypeEqualOperator = typeOperators.getEqualOperator(
                            transform.getType(), InvocationConvention.simpleConvention(FAIL_ON_NULL, NEVER_NULL, NEVER_NULL));
                    for (Range range : ranges.getOrderedRanges()) {
                        if (!canEnforceRangeWithPartitioningField(field, transform, range, targetTypeEqualOperator)) {
                            return false;
                        }
                    }
                    return true;
                },
                discreteValues -> false,
                allOrNone -> true);
        return canEnforce;
    }

    private static boolean canEnforceRangeWithPartitioningField(PartitionField field, ColumnTransform transform, Range range, MethodHandle targetTypeEqualOperator)
    {
        if (!transform.isMonotonic()) {
            // E.g. bucketing transform
            return false;
        }
        io.trino.spi.type.Type type = range.getType();
        if (!type.isOrderable()) {
            return false;
        }
        if (!range.isLowUnbounded()) {
            Object boundedValue = range.getLowBoundedValue();
            Optional<Object> adjacentValue = range.isLowInclusive() ? getPreviousValue(type, boundedValue) : getNextValue(type, boundedValue);
            if (adjacentValue.isEmpty() || yieldSamePartitioningValue(field, transform, type, boundedValue, adjacentValue.get(), targetTypeEqualOperator)) {
                return false;
            }
        }
        if (!range.isHighUnbounded()) {
            Object boundedValue = range.getHighBoundedValue();
            Optional<Object> adjacentValue = range.isHighInclusive() ? getNextValue(type, boundedValue) : getPreviousValue(type, boundedValue);
            if (adjacentValue.isEmpty() || yieldSamePartitioningValue(field, transform, type, boundedValue, adjacentValue.get(), targetTypeEqualOperator)) {
                return false;
            }
        }
        return true;
    }

    private static boolean yieldSamePartitioningValue(
            PartitionField field,
            ColumnTransform transform,
            io.trino.spi.type.Type sourceType,
            Object first,
            Object second,
            MethodHandle targetTypeEqualOperator)
    {
        requireNonNull(first, "first is null");
        requireNonNull(second, "second is null");
        Object firstTransformed = transform.getValueTransform().apply(nativeValueToBlock(sourceType, first), 0);
        Object secondTransformed = transform.getValueTransform().apply(nativeValueToBlock(sourceType, second), 0);
        // The pushdown logic assumes NULLs and non-NULLs are segregated, so that we have to think about non-null values only.
        verify(firstTransformed != null && secondTransformed != null, "Transform for %s returned null for non-null input", field);
        try {
            return (boolean) targetTypeEqualOperator.invoke(firstTransformed, secondTransformed);
        }
        catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    public static Object deserializePartitionValue(Type type, String valueString, String name)
    {
        if (valueString == null) {
            return null;
        }

        try {
            if (type.equals(BOOLEAN)) {
                if (valueString.equalsIgnoreCase("true")) {
                    return true;
                }
                if (valueString.equalsIgnoreCase("false")) {
                    return false;
                }
                throw new IllegalArgumentException();
            }
            if (type.equals(INTEGER)) {
                return parseLong(valueString);
            }
            if (type.equals(BIGINT)) {
                return parseLong(valueString);
            }
            if (type.equals(REAL)) {
                return (long) floatToRawIntBits(parseFloat(valueString));
            }
            if (type.equals(DOUBLE)) {
                return parseDouble(valueString);
            }
            if (type.equals(DATE)) {
                return parseLong(valueString);
            }
            if (type.equals(TIME_MICROS)) {
                return parseLong(valueString) * PICOSECONDS_PER_MICROSECOND;
            }
            if (type.equals(TIMESTAMP_MICROS)) {
                return parseLong(valueString);
            }
            if (type.equals(TIMESTAMP_TZ_MICROS)) {
                return timestampTzFromMicros(parseLong(valueString));
            }
            if (type instanceof VarcharType) {
                Slice value = utf8Slice(valueString);
                VarcharType varcharType = (VarcharType) type;
                if (!varcharType.isUnbounded() && SliceUtf8.countCodePoints(value) > varcharType.getBoundedLength()) {
                    throw new IllegalArgumentException();
                }
                return value;
            }
            if (type.equals(VarbinaryType.VARBINARY)) {
                return Slices.wrappedBuffer(Base64.getDecoder().decode(valueString));
            }
            if (type.equals(UuidType.UUID)) {
                return javaUuidToTrinoUuid(UUID.fromString(valueString));
            }
            if (isShortDecimal(type) || isLongDecimal(type)) {
                DecimalType decimalType = (DecimalType) type;
                BigDecimal decimal = new BigDecimal(valueString);
                decimal = decimal.setScale(decimalType.getScale(), BigDecimal.ROUND_UNNECESSARY);
                if (decimal.precision() > decimalType.getPrecision()) {
                    throw new IllegalArgumentException();
                }
                BigInteger unscaledValue = decimal.unscaledValue();
                return isShortDecimal(type) ? unscaledValue.longValue() : Int128.valueOf(unscaledValue);
            }
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(ICEBERG_INVALID_PARTITION_VALUE, format(
                    "Invalid partition value '%s' for %s partition key: %s",
                    valueString,
                    type.getDisplayName(),
                    name));
        }
        // Iceberg tables don't partition by non-primitive-type columns.
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "Invalid partition type " + type.toString());
    }

    /**
     * Returns a map from fieldId to serialized partition value containing entries for all identity partitions.
     * {@code null} partition values are represented with {@link Optional#empty}.
     */
    public static Map<Integer, Optional<String>> getPartitionKeys(FileScanTask scanTask)
    {
        return getPartitionKeys(scanTask.file().partition(), scanTask.spec());
    }

    public static Map<Integer, Optional<String>> getPartitionKeys(StructLike partition, PartitionSpec spec)
    {
        Map<PartitionField, Integer> fieldToIndex = getIdentityPartitions(spec);
        ImmutableMap.Builder<Integer, Optional<String>> partitionKeys = ImmutableMap.builder();

        fieldToIndex.forEach((field, index) -> {
            int id = field.sourceId();
            org.apache.iceberg.types.Type type = spec.schema().findType(id);
            Class<?> javaClass = type.typeId().javaClass();
            Object value = partition.get(index, javaClass);

            if (value == null) {
                partitionKeys.put(id, Optional.empty());
            }
            else {
                String partitionValue;
                if (type.typeId() == FIXED || type.typeId() == BINARY) {
                    // this is safe because Iceberg PartitionData directly wraps the byte array
                    partitionValue = Base64.getEncoder().encodeToString(((ByteBuffer) value).array());
                }
                else {
                    partitionValue = value.toString();
                }
                partitionKeys.put(id, Optional.of(partitionValue));
            }
        });

        return partitionKeys.buildOrThrow();
    }

    public static LocationProvider getLocationProvider(SchemaTableName schemaTableName, String tableLocation, Map<String, String> storageProperties)
    {
        if (storageProperties.containsKey(WRITE_LOCATION_PROVIDER_IMPL)) {
            throw new TrinoException(NOT_SUPPORTED, "Table " + schemaTableName + " specifies " + storageProperties.get(WRITE_LOCATION_PROVIDER_IMPL) +
                    " as a location provider. Writing to Iceberg tables with custom location provider is not supported.");
        }
        return locationsFor(tableLocation, storageProperties);
    }

    public static Schema toIcebergSchema(List<ColumnMetadata> columns)
    {
        List<NestedField> icebergColumns = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (!column.isHidden()) {
                int index = icebergColumns.size();
                org.apache.iceberg.types.Type type = toIcebergType(column.getType());
                NestedField field = NestedField.of(index, column.isNullable(), column.getName(), type, column.getComment());
                icebergColumns.add(field);
            }
        }
        org.apache.iceberg.types.Type icebergSchema = StructType.of(icebergColumns);
        AtomicInteger nextFieldId = new AtomicInteger(1);
        icebergSchema = TypeUtil.assignFreshIds(icebergSchema, nextFieldId::getAndIncrement);
        return new Schema(icebergSchema.asStructType().fields());
    }

    public static Transaction newCreateTableTransaction(TrinoCatalog catalog, ConnectorTableMetadata tableMetadata, ConnectorSession session)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        Schema schema = toIcebergSchema(tableMetadata.getColumns());
        PartitionSpec partitionSpec = parsePartitionFields(schema, getPartitioning(tableMetadata.getProperties()));
        String targetPath = getTableLocation(tableMetadata.getProperties())
                .orElseGet(() -> catalog.defaultTableLocation(session, schemaTableName));

        ImmutableMap.Builder<String, String> propertiesBuilder = ImmutableMap.builder();
        IcebergFileFormat fileFormat = IcebergTableProperties.getFileFormat(tableMetadata.getProperties());
        propertiesBuilder.put(DEFAULT_FILE_FORMAT, fileFormat.toIceberg().toString());
        propertiesBuilder.put(FORMAT_VERSION, Integer.toString(IcebergTableProperties.getFormatVersion(tableMetadata.getProperties())));

        // iceberg ORC format bloom filter properties used by create table
        List<String> columns = getOrcBloomFilterColumns(tableMetadata.getProperties());
        if (!columns.isEmpty()) {
            checkFormatForProperty(fileFormat.toIceberg(), FileFormat.ORC, ORC_BLOOM_FILTER_COLUMNS);
            validateOrcBloomFilterColumns(tableMetadata, columns);
            propertiesBuilder.put(ORC_BLOOM_FILTER_COLUMNS_KEY, Joiner.on(",").join(columns));
            propertiesBuilder.put(ORC_BLOOM_FILTER_FPP_KEY, String.valueOf(getOrcBloomFilterFpp(tableMetadata.getProperties())));
        }

        if (tableMetadata.getComment().isPresent()) {
            propertiesBuilder.put(TABLE_COMMENT, tableMetadata.getComment().get());
        }

        return catalog.newCreateTableTransaction(session, schemaTableName, schema, partitionSpec, targetPath, propertiesBuilder.buildOrThrow());
    }

    public static long getSnapshotIdAsOfTime(Table table, long epochMillis)
    {
        // The order of table.history() elements is not guaranteed, refer: https://github.com/apache/iceberg/issues/3891#issuecomment-1012303069
        return table.history().stream()
                .filter(logEntry -> logEntry.timestampMillis() <= epochMillis)
                .max(comparing(HistoryEntry::timestampMillis))
                .orElseThrow(() -> new TrinoException(INVALID_ARGUMENTS, format("No version history table %s at or before %s", table.name(), Instant.ofEpochMilli(epochMillis))))
                .snapshotId();
    }

    public static void validateTableCanBeDropped(Table table)
    {
        // TODO: support path override in Iceberg table creation: https://github.com/trinodb/trino/issues/8861
        if (table.properties().containsKey(OBJECT_STORE_PATH) ||
                table.properties().containsKey(WRITE_NEW_DATA_LOCATION) ||
                table.properties().containsKey(WRITE_METADATA_LOCATION) ||
                table.properties().containsKey(WRITE_DATA_LOCATION)) {
            throw new TrinoException(NOT_SUPPORTED, "Table contains Iceberg path override properties and cannot be dropped from Trino: " + table.name());
        }
    }

    private static void checkFormatForProperty(FileFormat actualStorageFormat, FileFormat expectedStorageFormat, String propertyName)
    {
        if (actualStorageFormat != expectedStorageFormat) {
            throw new TrinoException(INVALID_TABLE_PROPERTY, format("Cannot specify %s table property for storage format: %s", propertyName, actualStorageFormat));
        }
    }

    private static void validateOrcBloomFilterColumns(ConnectorTableMetadata tableMetadata, List<String> orcBloomFilterColumns)
    {
        Set<String> allColumns = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getName)
                .collect(toImmutableSet());
        if (!allColumns.containsAll(orcBloomFilterColumns)) {
            throw new TrinoException(INVALID_TABLE_PROPERTY, format("Orc bloom filter columns %s not present in schema", Sets.difference(ImmutableSet.copyOf(orcBloomFilterColumns), allColumns)));
        }
    }
}
