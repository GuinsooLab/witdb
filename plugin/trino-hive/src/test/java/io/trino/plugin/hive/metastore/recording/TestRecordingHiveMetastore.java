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
package io.trino.plugin.hive.metastore.recording;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.airlift.json.ObjectMapperProvider;
import io.airlift.slice.Slices;
import io.airlift.units.Duration;
import io.trino.plugin.base.TypeDeserializer;
import io.trino.plugin.hive.HiveBasicStatistics;
import io.trino.plugin.hive.HiveBucketProperty;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.PartitionStatistics;
import io.trino.plugin.hive.RecordingMetastoreConfig;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.Database;
import io.trino.plugin.hive.metastore.HiveColumnStatistics;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.HivePrincipal;
import io.trino.plugin.hive.metastore.HivePrivilegeInfo;
import io.trino.plugin.hive.metastore.HivePrivilegeInfo.HivePrivilege;
import io.trino.plugin.hive.metastore.IntegerStatistics;
import io.trino.plugin.hive.metastore.Partition;
import io.trino.plugin.hive.metastore.SortingColumn;
import io.trino.plugin.hive.metastore.SortingColumn.Order;
import io.trino.plugin.hive.metastore.Storage;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.plugin.hive.metastore.Table;
import io.trino.plugin.hive.metastore.UnimplementedHiveMetastore;
import io.trino.plugin.hive.util.HiveBlockEncodingSerde;
import io.trino.spi.block.Block;
import io.trino.spi.block.TestingBlockJsonSerde;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.RoleGrant;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.type.TestingTypeManager;
import io.trino.spi.type.Type;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.trino.plugin.hive.HiveBasicStatistics.createEmptyStatistics;
import static io.trino.plugin.hive.util.HiveBucketing.BucketingVersion.BUCKETING_V1;
import static io.trino.spi.security.PrincipalType.USER;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static org.testng.Assert.assertEquals;

public class TestRecordingHiveMetastore
{
    private static final Database DATABASE = new Database(
            "database",
            Optional.of("location"),
            Optional.of("owner"),
            Optional.of(USER),
            Optional.of("comment"),
            ImmutableMap.of("param", "value"));
    private static final Column TABLE_COLUMN = new Column(
            "column",
            HiveType.HIVE_INT,
            Optional.of("comment"));
    private static final Storage TABLE_STORAGE = new Storage(
            StorageFormat.create("serde", "input", "output"),
            Optional.of("location"),
            Optional.of(new HiveBucketProperty(ImmutableList.of("column"), BUCKETING_V1, 10, ImmutableList.of(new SortingColumn("column", Order.ASCENDING)))),
            true,
            ImmutableMap.of("param", "value2"));
    private static final Table TABLE = new Table(
            "database",
            "table",
            Optional.of("owner"),
            "table_type",
            TABLE_STORAGE,
            ImmutableList.of(TABLE_COLUMN),
            ImmutableList.of(TABLE_COLUMN),
            ImmutableMap.of("param", "value3"),
            Optional.of("original_text"),
            Optional.of("expanded_text"),
            OptionalLong.empty());
    private static final Partition PARTITION = new Partition(
            "database",
            "table",
            ImmutableList.of("value"),
            TABLE_STORAGE,
            ImmutableList.of(TABLE_COLUMN),
            ImmutableMap.of("param", "value4"));
    private static final Partition OTHER_PARTITION = new Partition(
            "database",
            "table",
            ImmutableList.of("other_value"),
            TABLE_STORAGE,
            ImmutableList.of(TABLE_COLUMN),
            ImmutableMap.of("param", "value4"));
    private static final PartitionStatistics PARTITION_STATISTICS = new PartitionStatistics(
            new HiveBasicStatistics(10, 11, 10000, 10001),
            ImmutableMap.of("column", new HiveColumnStatistics(
                    Optional.of(new IntegerStatistics(
                            OptionalLong.of(-100),
                            OptionalLong.of(102))),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.of(1234),
                    OptionalLong.of(1235),
                    OptionalLong.of(1),
                    OptionalLong.of(8))));
    private static final HivePrivilegeInfo PRIVILEGE_INFO = new HivePrivilegeInfo(HivePrivilege.SELECT, true, new HivePrincipal(USER, "grantor"), new HivePrincipal(USER, "grantee"));
    private static final RoleGrant ROLE_GRANT = new RoleGrant(new TrinoPrincipal(USER, "grantee"), "role", true);
    private static final List<String> PARTITION_COLUMN_NAMES = ImmutableList.of(TABLE_COLUMN.getName());
    private static final Domain PARTITION_COLUMN_EQUAL_DOMAIN = Domain.singleValue(createUnboundedVarcharType(), Slices.utf8Slice("value1"));
    private static final TupleDomain<String> TUPLE_DOMAIN = TupleDomain.withColumnDomains(ImmutableMap.of(TABLE_COLUMN.getName(), PARTITION_COLUMN_EQUAL_DOMAIN));

    @Test
    public void testRecordingHiveMetastore()
            throws IOException
    {
        RecordingMetastoreConfig recordingConfig = new RecordingMetastoreConfig()
                .setRecordingPath(File.createTempFile("recording_test", "json").getAbsolutePath())
                .setRecordingDuration(new Duration(10, TimeUnit.MINUTES));
        JsonCodec<HiveMetastoreRecording.Recording> jsonCodec = createJsonCodec();
        HiveMetastoreRecording recording = new HiveMetastoreRecording(recordingConfig, jsonCodec);
        RecordingHiveMetastore recordingHiveMetastore = new RecordingHiveMetastore(new TestingHiveMetastore(), recording);
        validateMetadata(recordingHiveMetastore);
        recordingHiveMetastore.dropDatabase("other_database", true);
        recording.writeRecording();

        RecordingMetastoreConfig replayingConfig = recordingConfig
                .setReplay(true);

        recording = new HiveMetastoreRecording(replayingConfig, jsonCodec);
        recordingHiveMetastore = new RecordingHiveMetastore(new UnimplementedHiveMetastore(), recording);
        recording.loadRecording();
        validateMetadata(recordingHiveMetastore);
        validatePartitionSubset(recordingHiveMetastore);
    }

    public static JsonCodec<HiveMetastoreRecording.Recording> createJsonCodec()
    {
        ObjectMapperProvider objectMapperProvider = new ObjectMapperProvider();
        TypeDeserializer typeDeserializer = new TypeDeserializer(new TestingTypeManager());
        objectMapperProvider.setJsonDeserializers(
                ImmutableMap.of(
                        Block.class, new TestingBlockJsonSerde.Deserializer(new HiveBlockEncodingSerde()),
                        Type.class, typeDeserializer));
        objectMapperProvider.setJsonSerializers(ImmutableMap.of(Block.class, new TestingBlockJsonSerde.Serializer(new HiveBlockEncodingSerde())));
        JsonCodec<HiveMetastoreRecording.Recording> jsonCodec = new JsonCodecFactory(objectMapperProvider).jsonCodec(HiveMetastoreRecording.Recording.class);
        return jsonCodec;
    }

    private void validateMetadata(HiveMetastore hiveMetastore)
    {
        assertEquals(hiveMetastore.getDatabase("database"), Optional.of(DATABASE));
        assertEquals(hiveMetastore.getAllDatabases(), ImmutableList.of("database"));
        assertEquals(hiveMetastore.getTable("database", "table"), Optional.of(TABLE));
        assertEquals(hiveMetastore.getTableStatistics(TABLE), PARTITION_STATISTICS);
        assertEquals(hiveMetastore.getPartitionStatistics(TABLE, ImmutableList.of(PARTITION, OTHER_PARTITION)), ImmutableMap.of(
                "column=value", PARTITION_STATISTICS,
                "column=other_value", PARTITION_STATISTICS));
        assertEquals(hiveMetastore.getAllTables("database"), ImmutableList.of("table"));
        assertEquals(hiveMetastore.getTablesWithParameter("database", "param", "value3"), ImmutableList.of("table"));
        assertEquals(hiveMetastore.getAllViews("database"), ImmutableList.of());
        assertEquals(hiveMetastore.getPartition(TABLE, ImmutableList.of("value")), Optional.of(PARTITION));
        assertEquals(hiveMetastore.getPartitionNamesByFilter("database", "table", PARTITION_COLUMN_NAMES, TupleDomain.all()), Optional.of(ImmutableList.of("value")));
        assertEquals(hiveMetastore.getPartitionNamesByFilter("database", "table", PARTITION_COLUMN_NAMES, TUPLE_DOMAIN), Optional.of(ImmutableList.of("value")));
        assertEquals(hiveMetastore.getPartitionsByNames(TABLE, ImmutableList.of("column=value", "column=other_value")), ImmutableMap.of(
                "column=value", Optional.of(PARTITION),
                "column=other_value", Optional.of(OTHER_PARTITION)));
        assertEquals(hiveMetastore.listTablePrivileges("database", "table", Optional.of("owner"), Optional.of(new HivePrincipal(USER, "user"))), ImmutableSet.of(PRIVILEGE_INFO));
        assertEquals(hiveMetastore.listRoles(), ImmutableSet.of("role"));
        assertEquals(hiveMetastore.listRoleGrants(new HivePrincipal(USER, "user")), ImmutableSet.of(ROLE_GRANT));
        assertEquals(hiveMetastore.listGrantedPrincipals("role"), ImmutableSet.of(ROLE_GRANT));
    }

    private void validatePartitionSubset(HiveMetastore hiveMetastore)
    {
        assertEquals(hiveMetastore.getPartitionStatistics(TABLE, ImmutableList.of(PARTITION)), ImmutableMap.of("column=value", PARTITION_STATISTICS));
        assertEquals(hiveMetastore.getPartitionStatistics(TABLE, ImmutableList.of(OTHER_PARTITION)), ImmutableMap.of("column=other_value", PARTITION_STATISTICS));
        assertEquals(hiveMetastore.getPartitionsByNames(TABLE, ImmutableList.of("column=value")), ImmutableMap.of("column=value", Optional.of(PARTITION)));
        assertEquals(hiveMetastore.getPartitionsByNames(TABLE, ImmutableList.of("column=other_value")), ImmutableMap.of("column=other_value", Optional.of(OTHER_PARTITION)));
    }

    private static class TestingHiveMetastore
            extends UnimplementedHiveMetastore
    {
        @Override
        public Optional<Database> getDatabase(String databaseName)
        {
            if (databaseName.equals("database")) {
                return Optional.of(DATABASE);
            }

            return Optional.empty();
        }

        @Override
        public List<String> getAllDatabases()
        {
            return ImmutableList.of("database");
        }

        @Override
        public Optional<Table> getTable(String databaseName, String tableName)
        {
            if (databaseName.equals("database") && tableName.equals("table")) {
                return Optional.of(TABLE);
            }

            return Optional.empty();
        }

        @Override
        public PartitionStatistics getTableStatistics(Table table)
        {
            if (table.getDatabaseName().equals("database") && table.getTableName().equals("table")) {
                return PARTITION_STATISTICS;
            }

            return new PartitionStatistics(createEmptyStatistics(), ImmutableMap.of());
        }

        @Override
        public Map<String, PartitionStatistics> getPartitionStatistics(Table table, List<Partition> partitions)
        {
            ImmutableMap.Builder<String, PartitionStatistics> result = ImmutableMap.builder();
            if (table.getDatabaseName().equals("database") && table.getTableName().equals("table")) {
                if (partitions.stream().anyMatch(partition -> partition.getValues().get(0).equals("value"))) {
                    result.put("column=value", PARTITION_STATISTICS);
                }
                if (partitions.stream().anyMatch(partition -> partition.getValues().get(0).equals("other_value"))) {
                    result.put("column=other_value", PARTITION_STATISTICS);
                }
            }
            return result.buildOrThrow();
        }

        @Override
        public List<String> getAllTables(String databaseName)
        {
            if (databaseName.equals("database")) {
                return ImmutableList.of("table");
            }

            return ImmutableList.of();
        }

        @Override
        public List<String> getTablesWithParameter(String databaseName, String parameterKey, String parameterValue)
        {
            if (databaseName.equals("database") && parameterKey.equals("param") && parameterValue.equals("value3")) {
                return ImmutableList.of("table");
            }
            return ImmutableList.of();
        }

        @Override
        public List<String> getAllViews(String databaseName)
        {
            return ImmutableList.of();
        }

        @Override
        public void dropDatabase(String databaseName, boolean deleteData)
        {
            // noop for test purpose
        }

        @Override
        public Optional<Partition> getPartition(Table table, List<String> partitionValues)
        {
            if (table.getDatabaseName().equals("database") && table.getTableName().equals("table")) {
                if (partitionValues.equals(ImmutableList.of("value"))) {
                    return Optional.of(PARTITION);
                }
                if (partitionValues.equals(ImmutableList.of("other_value"))) {
                    return Optional.of(OTHER_PARTITION);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<List<String>> getPartitionNamesByFilter(String databaseName,
                String tableName,
                List<String> columnNames,
                TupleDomain<String> partitionKeysFilter)
        {
            Domain filterDomain = partitionKeysFilter.getDomains().get().get(TABLE_COLUMN.getName());
            if (databaseName.equals("database") && tableName.equals("table") && (filterDomain == null || filterDomain.equals(PARTITION_COLUMN_EQUAL_DOMAIN))) {
                return Optional.of(ImmutableList.of("value"));
            }

            return Optional.empty();
        }

        @Override
        public Map<String, Optional<Partition>> getPartitionsByNames(Table table, List<String> partitionNames)
        {
            ImmutableMap.Builder<String, Optional<Partition>> result = ImmutableMap.builder();
            if (table.getDatabaseName().equals("database") && table.getTableName().equals("table")) {
                if (partitionNames.contains("column=value")) {
                    result.put("column=value", Optional.of(PARTITION));
                }
                if (partitionNames.contains("column=other_value")) {
                    result.put("column=other_value", Optional.of(OTHER_PARTITION));
                }
            }
            return result.buildOrThrow();
        }

        @Override
        public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, Optional<String> tableOwner, Optional<HivePrincipal> principal)
        {
            if (databaseName.equals("database") && tableName.equals("table") && principal.get().getType() == USER && principal.get().getName().equals("user")) {
                return ImmutableSet.of(PRIVILEGE_INFO);
            }

            return ImmutableSet.of();
        }

        @Override
        public Set<String> listRoles()
        {
            return ImmutableSet.of("role");
        }

        @Override
        public Set<RoleGrant> listGrantedPrincipals(String role)
        {
            return ImmutableSet.of(ROLE_GRANT);
        }

        @Override
        public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
        {
            return ImmutableSet.of(ROLE_GRANT);
        }
    }
}
