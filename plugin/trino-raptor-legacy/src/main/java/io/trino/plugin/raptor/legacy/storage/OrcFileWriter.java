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
package io.trino.plugin.raptor.legacy.storage;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.trino.hive.orc.NullMemoryManager;
import io.trino.plugin.raptor.legacy.util.SyncingFileSystem;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.classloader.ThreadContextClassLoader;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeId;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.IOConstants;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.SettableStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.util.VersionInfo;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.hadoop.ConfigurationInstantiator.newEmptyConfiguration;
import static io.trino.plugin.raptor.legacy.RaptorErrorCode.RAPTOR_ERROR;
import static io.trino.plugin.raptor.legacy.storage.Row.extractRow;
import static io.trino.plugin.raptor.legacy.storage.StorageType.arrayOf;
import static io.trino.plugin.raptor.legacy.storage.StorageType.mapOf;
import static io.trino.plugin.raptor.legacy.util.Types.isArrayType;
import static io.trino.plugin.raptor.legacy.util.Types.isMapType;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.ql.exec.FileSinkOperator.RecordWriter;
import static org.apache.hadoop.hive.ql.io.orc.CompressionKind.SNAPPY;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.LIST;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.MAP;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.PRIMITIVE;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardListObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardMapObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory.getPrimitiveTypeInfo;

public class OrcFileWriter
        implements Closeable
{
    static {
        // make sure Hadoop version is loaded from correct class loader
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(VersionInfo.class.getClassLoader())) {
            ShimLoader.getHadoopShims();
        }
    }

    private static final Configuration CONFIGURATION = newEmptyConfiguration();
    private static final Constructor<? extends RecordWriter> WRITER_CONSTRUCTOR = getOrcWriterConstructor();
    private static final JsonCodec<OrcFileMetadata> METADATA_CODEC = jsonCodec(OrcFileMetadata.class);

    private final List<Type> columnTypes;

    private final OrcSerde serializer;
    private final RecordWriter recordWriter;
    private final SettableStructObjectInspector tableInspector;
    private final List<StructField> structFields;
    private final Object orcRow;

    private boolean closed;
    private long rowCount;
    private long uncompressedSize;

    public OrcFileWriter(List<Long> columnIds, List<Type> columnTypes, File target)
    {
        this(columnIds, columnTypes, target, true);
    }

    @VisibleForTesting
    OrcFileWriter(List<Long> columnIds, List<Type> columnTypes, File target, boolean writeMetadata)
    {
        this.columnTypes = ImmutableList.copyOf(requireNonNull(columnTypes, "columnTypes is null"));
        checkArgument(columnIds.size() == columnTypes.size(), "ids and types mismatch");
        checkArgument(isUnique(columnIds), "ids must be unique");

        List<StorageType> storageTypes = ImmutableList.copyOf(toStorageTypes(columnTypes));
        Iterable<String> hiveTypeNames = storageTypes.stream().map(StorageType::getHiveTypeName).collect(toList());
        List<String> columnNames = columnIds.stream()
                .map(Objects::toString)
                .collect(toImmutableList());

        Properties properties = new Properties();
        properties.setProperty(IOConstants.COLUMNS, Joiner.on(',').join(columnNames));
        properties.setProperty(IOConstants.COLUMNS_TYPES, Joiner.on(':').join(hiveTypeNames));

        serializer = createSerializer(properties);
        recordWriter = createRecordWriter(new Path(target.toURI()), columnIds, columnTypes, writeMetadata);

        tableInspector = getStandardStructObjectInspector(columnNames, getJavaObjectInspectors(storageTypes));
        structFields = ImmutableList.copyOf(tableInspector.getAllStructFieldRefs());
        orcRow = tableInspector.create();
    }

    public void appendPages(List<Page> pages)
    {
        for (Page page : pages) {
            for (int position = 0; position < page.getPositionCount(); position++) {
                appendRow(extractRow(page, position, columnTypes));
            }
        }
    }

    public void appendPages(List<Page> inputPages, int[] pageIndexes, int[] positionIndexes)
    {
        checkArgument(pageIndexes.length == positionIndexes.length, "pageIndexes and positionIndexes do not match");
        for (int i = 0; i < pageIndexes.length; i++) {
            Page page = inputPages.get(pageIndexes[i]);
            appendRow(extractRow(page, positionIndexes[i], columnTypes));
        }
    }

    public void appendRow(Row row)
    {
        List<Object> columns = row.getColumns();
        checkArgument(columns.size() == columnTypes.size());
        for (int channel = 0; channel < columns.size(); channel++) {
            tableInspector.setStructFieldData(orcRow, structFields.get(channel), columns.get(channel));
        }
        try {
            recordWriter.write(serializer.serialize(orcRow, tableInspector));
        }
        catch (IOException e) {
            throw new TrinoException(RAPTOR_ERROR, "Failed to write record", e);
        }
        rowCount++;
        uncompressedSize += row.getSizeInBytes();
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;

        try {
            recordWriter.close(false);
        }
        catch (IOException e) {
            throw new TrinoException(RAPTOR_ERROR, "Failed to close writer", e);
        }
    }

    public long getRowCount()
    {
        return rowCount;
    }

    public long getUncompressedSize()
    {
        return uncompressedSize;
    }

    private static OrcSerde createSerializer(Properties properties)
    {
        OrcSerde serde = new OrcSerde();
        serde.initialize(CONFIGURATION, properties);
        return serde;
    }

    private static RecordWriter createRecordWriter(Path target, List<Long> columnIds, List<Type> columnTypes, boolean writeMetadata)
    {
        try (FileSystem fileSystem = new SyncingFileSystem(CONFIGURATION)) {
            OrcFile.WriterOptions options = OrcFile.writerOptions(CONFIGURATION)
                    .memory(new NullMemoryManager())
                    .fileSystem(fileSystem)
                    .compress(SNAPPY);

            if (writeMetadata) {
                options.callback(createFileMetadataCallback(columnIds, columnTypes));
            }

            return WRITER_CONSTRUCTOR.newInstance(target, options);
        }
        catch (ReflectiveOperationException | IOException e) {
            throw new TrinoException(RAPTOR_ERROR, "Failed to create writer", e);
        }
    }

    private static OrcFile.WriterCallback createFileMetadataCallback(List<Long> columnIds, List<Type> columnTypes)
    {
        return new OrcFile.WriterCallback()
        {
            @Override
            public void preStripeWrite(OrcFile.WriterContext context)
            {}

            @Override
            public void preFooterWrite(OrcFile.WriterContext context)
            {
                ImmutableMap.Builder<Long, TypeId> columnTypesMap = ImmutableMap.builder();
                for (int i = 0; i < columnIds.size(); i++) {
                    columnTypesMap.put(columnIds.get(i), columnTypes.get(i).getTypeId());
                }
                byte[] bytes = METADATA_CODEC.toJsonBytes(new OrcFileMetadata(columnTypesMap.buildOrThrow()));
                context.getWriter().addUserMetadata(OrcFileMetadata.KEY, ByteBuffer.wrap(bytes));
            }
        };
    }

    private static Constructor<? extends RecordWriter> getOrcWriterConstructor()
    {
        try {
            String writerClassName = OrcOutputFormat.class.getName() + "$OrcRecordWriter";
            Constructor<? extends RecordWriter> constructor = OrcOutputFormat.class.getClassLoader()
                    .loadClass(writerClassName).asSubclass(RecordWriter.class)
                    .getDeclaredConstructor(Path.class, OrcFile.WriterOptions.class);
            constructor.setAccessible(true);
            return constructor;
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<ObjectInspector> getJavaObjectInspectors(List<StorageType> types)
    {
        return types.stream()
                .map(StorageType::getHiveTypeName)
                .map(TypeInfoUtils::getTypeInfoFromTypeString)
                .map(OrcFileWriter::getJavaObjectInspector)
                .collect(toList());
    }

    private static ObjectInspector getJavaObjectInspector(TypeInfo typeInfo)
    {
        Category category = typeInfo.getCategory();
        if (category == PRIMITIVE) {
            return getPrimitiveJavaObjectInspector(getPrimitiveTypeInfo(typeInfo.getTypeName()));
        }
        if (category == LIST) {
            ListTypeInfo listTypeInfo = (ListTypeInfo) typeInfo;
            return getStandardListObjectInspector(getJavaObjectInspector(listTypeInfo.getListElementTypeInfo()));
        }
        if (category == MAP) {
            MapTypeInfo mapTypeInfo = (MapTypeInfo) typeInfo;
            return getStandardMapObjectInspector(
                    getJavaObjectInspector(mapTypeInfo.getMapKeyTypeInfo()),
                    getJavaObjectInspector(mapTypeInfo.getMapValueTypeInfo()));
        }
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "Unhandled storage type: " + category);
    }

    private static <T> boolean isUnique(Collection<T> items)
    {
        return new HashSet<>(items).size() == items.size();
    }

    private static List<StorageType> toStorageTypes(List<Type> columnTypes)
    {
        return columnTypes.stream().map(OrcFileWriter::toStorageType).collect(toList());
    }

    private static StorageType toStorageType(Type type)
    {
        if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            return StorageType.decimal(decimalType.getPrecision(), decimalType.getScale());
        }
        Class<?> javaType = type.getJavaType();
        if (javaType == boolean.class) {
            return StorageType.BOOLEAN;
        }
        if (javaType == long.class) {
            return StorageType.LONG;
        }
        if (javaType == double.class) {
            return StorageType.DOUBLE;
        }
        if (javaType == Slice.class) {
            if (type instanceof VarcharType) {
                return StorageType.STRING;
            }
            if (type.equals(VarbinaryType.VARBINARY)) {
                return StorageType.BYTES;
            }
        }
        if (isArrayType(type)) {
            return arrayOf(toStorageType(type.getTypeParameters().get(0)));
        }
        if (isMapType(type)) {
            return mapOf(toStorageType(type.getTypeParameters().get(0)), toStorageType(type.getTypeParameters().get(1)));
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported type: " + type);
    }
}
