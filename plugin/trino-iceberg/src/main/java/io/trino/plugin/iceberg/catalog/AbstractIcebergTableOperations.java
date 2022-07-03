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
package io.trino.plugin.iceberg.catalog;

import io.airlift.log.Logger;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.hive.HiveSchemaUtil;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.util.Tasks;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.hive.HiveType.toHiveType;
import static io.trino.plugin.iceberg.IcebergUtil.getLocationProvider;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static org.apache.iceberg.TableMetadataParser.getFileExtension;
import static org.apache.iceberg.TableProperties.METADATA_COMPRESSION;
import static org.apache.iceberg.TableProperties.METADATA_COMPRESSION_DEFAULT;
import static org.apache.iceberg.TableProperties.WRITE_METADATA_LOCATION;

@NotThreadSafe
public abstract class AbstractIcebergTableOperations
        implements IcebergTableOperations
{
    private static final Logger log = Logger.get(AbstractIcebergTableOperations.class);

    protected static final String METADATA_FOLDER_NAME = "metadata";

    protected static final StorageFormat STORAGE_FORMAT = StorageFormat.create(
            LazySimpleSerDe.class.getName(),
            FileInputFormat.class.getName(),
            FileOutputFormat.class.getName());

    protected final ConnectorSession session;
    protected final String database;
    protected final String tableName;
    protected final Optional<String> owner;
    protected final Optional<String> location;
    protected final FileIO fileIo;

    protected TableMetadata currentMetadata;
    protected String currentMetadataLocation;
    protected boolean shouldRefresh = true;
    protected int version = -1;

    protected AbstractIcebergTableOperations(
            FileIO fileIo,
            ConnectorSession session,
            String database,
            String table,
            Optional<String> owner,
            Optional<String> location)
    {
        this.fileIo = requireNonNull(fileIo, "fileIo is null");
        this.session = requireNonNull(session, "session is null");
        this.database = requireNonNull(database, "database is null");
        this.tableName = requireNonNull(table, "table is null");
        this.owner = requireNonNull(owner, "owner is null");
        this.location = requireNonNull(location, "location is null");
    }

    @Override
    public void initializeFromMetadata(TableMetadata tableMetadata)
    {
        checkState(currentMetadata == null, "already initialized");
        currentMetadata = tableMetadata;
        currentMetadataLocation = tableMetadata.metadataFileLocation();
        shouldRefresh = false;
        version = parseVersion(currentMetadataLocation);
    }

    @Override
    public TableMetadata current()
    {
        if (shouldRefresh) {
            return refresh(false);
        }
        return currentMetadata;
    }

    @Override
    public TableMetadata refresh()
    {
        return refresh(true);
    }

    public TableMetadata refresh(boolean invalidateCaches)
    {
        if (location.isPresent()) {
            refreshFromMetadataLocation(null);
            return currentMetadata;
        }
        refreshFromMetadataLocation(getRefreshedLocation(invalidateCaches));
        return currentMetadata;
    }

    @Override
    public void commit(@Nullable TableMetadata base, TableMetadata metadata)
    {
        requireNonNull(metadata, "metadata is null");

        // if the metadata is already out of date, reject it
        if (!Objects.equals(base, current())) {
            throw new CommitFailedException("Cannot commit: stale table metadata for %s", getSchemaTableName());
        }

        // if the metadata is not changed, return early
        if (Objects.equals(base, metadata)) {
            return;
        }

        if (base == null) {
            commitNewTable(metadata);
        }
        else {
            commitToExistingTable(base, metadata);
        }

        shouldRefresh = true;
    }

    protected abstract String getRefreshedLocation(boolean invalidateCaches);

    protected abstract void commitNewTable(TableMetadata metadata);

    protected abstract void commitToExistingTable(TableMetadata base, TableMetadata metadata);

    @Override
    public FileIO io()
    {
        return fileIo;
    }

    @Override
    public String metadataFileLocation(String filename)
    {
        TableMetadata metadata = current();
        String location;
        if (metadata != null) {
            String writeLocation = metadata.properties().get(WRITE_METADATA_LOCATION);
            if (writeLocation != null) {
                return format("%s/%s", writeLocation, filename);
            }
            location = metadata.location();
        }
        else {
            location = this.location.orElseThrow(() -> new IllegalStateException("Location not set"));
        }
        return format("%s/%s/%s", location, METADATA_FOLDER_NAME, filename);
    }

    @Override
    public LocationProvider locationProvider()
    {
        TableMetadata metadata = current();
        return getLocationProvider(getSchemaTableName(), metadata.location(), metadata.properties());
    }

    protected SchemaTableName getSchemaTableName()
    {
        return new SchemaTableName(database, tableName);
    }

    protected String writeNewMetadata(TableMetadata metadata, int newVersion)
    {
        String newTableMetadataFilePath = newTableMetadataFilePath(metadata, newVersion);
        OutputFile newMetadataLocation = fileIo.newOutputFile(newTableMetadataFilePath);

        // write the new metadata
        TableMetadataParser.write(metadata, newMetadataLocation);

        return newTableMetadataFilePath;
    }

    protected void refreshFromMetadataLocation(String newLocation)
    {
        // use null-safe equality check because new tables have a null metadata location
        if (Objects.equals(currentMetadataLocation, newLocation)) {
            shouldRefresh = false;
            return;
        }

        AtomicReference<TableMetadata> newMetadata = new AtomicReference<>();
        Tasks.foreach(newLocation)
                .retry(20)
                .exponentialBackoff(100, 5000, 600000, 4.0)
                .stopRetryOn(org.apache.iceberg.exceptions.NotFoundException.class) // qualified name, as this is NOT the io.trino.spi.connector.NotFoundException
                .run(metadataLocation -> newMetadata.set(
                        TableMetadataParser.read(fileIo, io().newInputFile(metadataLocation))));

        String newUUID = newMetadata.get().uuid();
        if (currentMetadata != null) {
            checkState(newUUID == null || newUUID.equals(currentMetadata.uuid()),
                    "Table UUID does not match: current=%s != refreshed=%s", currentMetadata.uuid(), newUUID);
        }

        currentMetadata = newMetadata.get();
        currentMetadataLocation = newLocation;
        version = parseVersion(newLocation);
        shouldRefresh = false;
    }

    protected static String newTableMetadataFilePath(TableMetadata meta, int newVersion)
    {
        String codec = meta.property(METADATA_COMPRESSION, METADATA_COMPRESSION_DEFAULT);
        return metadataFileLocation(meta, format("%05d-%s%s", newVersion, randomUUID(), getFileExtension(codec)));
    }

    protected static String metadataFileLocation(TableMetadata metadata, String filename)
    {
        String location = metadata.properties().get(WRITE_METADATA_LOCATION);
        if (location != null) {
            return format("%s/%s", location, filename);
        }
        return format("%s/%s/%s", metadata.location(), METADATA_FOLDER_NAME, filename);
    }

    protected static int parseVersion(String metadataLocation)
    {
        int versionStart = metadataLocation.lastIndexOf('/') + 1; // if '/' isn't found, this will be 0
        int versionEnd = metadataLocation.indexOf('-', versionStart);
        try {
            return parseInt(metadataLocation.substring(versionStart, versionEnd));
        }
        catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn(e, "Unable to parse version from metadata location: %s", metadataLocation);
            return -1;
        }
    }

    protected static List<Column> toHiveColumns(List<NestedField> columns)
    {
        return columns.stream()
                .map(column -> new Column(
                        column.name(),
                        toHiveType(HiveSchemaUtil.convert(column.type())),
                        Optional.empty()))
                .collect(toImmutableList());
    }
}
