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

import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.plugin.base.CatalogName;
import io.trino.plugin.hive.HdfsConfig;
import io.trino.plugin.hive.HdfsConfiguration;
import io.trino.plugin.hive.HdfsConfigurationInitializer;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.HiveHdfsConfiguration;
import io.trino.plugin.hive.authentication.NoHdfsAuthentication;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.cache.CachingHiveMetastore;
import io.trino.plugin.iceberg.catalog.IcebergTableOperationsProvider;
import io.trino.plugin.iceberg.catalog.TrinoCatalog;
import io.trino.plugin.iceberg.catalog.file.FileMetastoreTableOperationsProvider;
import io.trino.plugin.iceberg.catalog.hms.TrinoHiveCatalog;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.TestingTypeManager;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.hadoop.HadoopOutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.assertj.core.api.Assertions;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.plugin.hive.HdfsEnvironment.HdfsContext;
import static io.trino.plugin.hive.metastore.file.FileHiveMetastore.createTestingFileHiveMetastore;
import static io.trino.plugin.iceberg.IcebergUtil.loadIcebergTable;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static io.trino.tpch.TpchTable.NATION;
import static java.lang.String.format;
import static org.apache.iceberg.TableProperties.SPLIT_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestIcebergV2
        extends AbstractTestQueryFramework
{
    private HiveMetastore metastore;
    private HdfsEnvironment hdfsEnvironment;
    private java.nio.file.Path tempDir;
    private File metastoreDir;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        HdfsConfig config = new HdfsConfig();
        HdfsConfiguration configuration = new HiveHdfsConfiguration(new HdfsConfigurationInitializer(config), ImmutableSet.of());
        hdfsEnvironment = new HdfsEnvironment(configuration, config, new NoHdfsAuthentication());

        tempDir = Files.createTempDirectory("test_iceberg_v2");
        metastoreDir = tempDir.resolve("iceberg_data").toFile();
        metastore = createTestingFileHiveMetastore(metastoreDir);

        return IcebergQueryRunner.builder()
                .setInitialTables(NATION)
                .setMetastoreDirectory(metastoreDir)
                .build();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws IOException
    {
        deleteRecursively(tempDir, ALLOW_INSECURE);
    }

    @Test
    public void testSettingFormatVersion()
    {
        String tableName = "test_seting_format_version_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 2) AS SELECT * FROM tpch.tiny.nation", 25);
        assertThat(loadTable(tableName).operations().current().formatVersion()).isEqualTo(2);
        assertUpdate("DROP TABLE " + tableName);

        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 1) AS SELECT * FROM tpch.tiny.nation", 25);
        assertThat(loadTable(tableName).operations().current().formatVersion()).isEqualTo(1);
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testDefaultFormatVersion()
    {
        String tableName = "test_default_format_version_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        assertThat(loadTable(tableName).operations().current().formatVersion()).isEqualTo(2);
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testV2TableRead()
    {
        String tableName = "test_v2_table_read" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        updateTableToV2(tableName);
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation");
    }

    @Test
    public void testV2TableWithPositionDelete()
            throws Exception
    {
        String tableName = "test_v2_row_delete" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);

        String dataFilePath = (String) computeActual("SELECT file_path FROM \"" + tableName + "$files\" LIMIT 1").getOnlyValue();

        Path metadataDir = new Path(metastoreDir.toURI());
        String deleteFileName = "delete_file_" + UUID.randomUUID();
        FileSystem fs = hdfsEnvironment.getFileSystem(new HdfsContext(SESSION), metadataDir);

        Path path = new Path(metadataDir, deleteFileName);
        PositionDeleteWriter<Record> writer = Parquet.writeDeletes(HadoopOutputFile.fromPath(path, fs))
                .createWriterFunc(GenericParquetWriter::buildWriter)
                .forTable(icebergTable)
                .overwrite()
                .rowSchema(icebergTable.schema())
                .withSpec(PartitionSpec.unpartitioned())
                .buildPositionWriter();

        try (Closeable ignored = writer) {
            writer.delete(dataFilePath, 0, GenericRecord.create(icebergTable.schema()));
        }

        icebergTable.newRowDelta().addDeletes(writer.toDeleteFile()).commit();
        assertQuery("SELECT count(*) FROM " + tableName, "VALUES 24");
    }

    @Test
    public void testV2TableWithEqualityDelete()
            throws Exception
    {
        String tableName = "test_v2_equality_delete" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);
        writeEqualityDeleteToNationTable(icebergTable, Optional.of(icebergTable.spec()), Optional.of(new PartitionData(new Long[]{1L})));
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        // natiokey is before the equality delete column in the table schema, comment is after
        assertQuery("SELECT nationkey, comment FROM " + tableName, "SELECT nationkey, comment FROM nation WHERE regionkey != 1");
    }

    @Test
    public void testOptimizingV2TableRemovesEqualityDeletesWhenWholeTableIsScanned()
            throws Exception
    {
        String tableName = "test_optimize_table_cleans_equality_delete_file_when_whole_table_is_scanned" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);
        Assertions.assertThat(icebergTable.currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        writeEqualityDeleteToNationTable(icebergTable, Optional.of(icebergTable.spec()), Optional.of(new PartitionData(new Long[]{1L})));
        List<String> initialActiveFiles = getActiveFiles(tableName);
        query("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        // natiokey is before the equality delete column in the table schema, comment is after
        assertQuery("SELECT nationkey, comment FROM " + tableName, "SELECT nationkey, comment FROM nation WHERE regionkey != 1");
        Assertions.assertThat(loadTable(tableName).currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        List<String> updatedFiles = getActiveFiles(tableName);
        Assertions.assertThat(updatedFiles).doesNotContain(initialActiveFiles.toArray(new String[0]));
    }

    @Test
    public void testOptimizingV2TableDoesntRemoveEqualityDeletesWhenOnlyPartOfTheTableIsOptimized()
            throws Exception
    {
        String tableName = "test_optimize_table_with_equality_delete_file_for_different_partition_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);
        Assertions.assertThat(icebergTable.currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        List<String> initialActiveFiles = getActiveFiles(tableName);
        writeEqualityDeleteToNationTable(icebergTable, Optional.of(icebergTable.spec()), Optional.of(new PartitionData(new Long[]{1L})));
        query("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE WHERE regionkey != 1");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        // natiokey is before the equality delete column in the table schema, comment is after
        assertQuery("SELECT nationkey, comment FROM " + tableName, "SELECT nationkey, comment FROM nation WHERE regionkey != 1");
        Assertions.assertThat(loadTable(tableName).currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("1");
        List<String> updatedFiles = getActiveFiles(tableName);
        Assertions.assertThat(updatedFiles).doesNotContain(initialActiveFiles.stream().filter(path -> !path.contains("regionkey=1")).toArray(String[]::new));
    }

    @Test
    public void testOptimizingV2TableWithEmptyPartitionSpec()
            throws Exception
    {
        String tableName = "test_optimize_table_with_global_equality_delete_file_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);
        Assertions.assertThat(icebergTable.currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        writeEqualityDeleteToNationTable(icebergTable);
        List<String> initialActiveFiles = getActiveFiles(tableName);
        query("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        // natiokey is before the equality delete column in the table schema, comment is after
        assertQuery("SELECT nationkey, comment FROM " + tableName, "SELECT nationkey, comment FROM nation WHERE regionkey != 1");
        Assertions.assertThat(loadTable(tableName).currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        List<String> updatedFiles = getActiveFiles(tableName);
        Assertions.assertThat(updatedFiles).doesNotContain(initialActiveFiles.toArray(new String[0]));
    }

    @Test
    public void testOptimizingPartitionsOfV2TableWithGlobalEqualityDeleteFile()
            throws Exception
    {
        String tableName = "test_optimize_partitioned_table_with_global_equality_delete_file_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM tpch.tiny.nation", 25);
        Table icebergTable = updateTableToV2(tableName);
        Assertions.assertThat(icebergTable.currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("0");
        writeEqualityDeleteToNationTable(icebergTable, Optional.of(icebergTable.spec()), Optional.of(new PartitionData(new Long[]{1L, 2L, 3L, 4L})));
        List<String> initialActiveFiles = getActiveFiles(tableName);
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        query("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE WHERE regionkey != 1");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey != 1");
        // natiokey is before the equality delete column in the table schema, comment is after
        assertQuery("SELECT nationkey, comment FROM " + tableName, "SELECT nationkey, comment FROM nation WHERE regionkey != 1");
        Assertions.assertThat(loadTable(tableName).currentSnapshot().summary().get("total-equality-deletes")).isEqualTo("1");
        List<String> updatedFiles = getActiveFiles(tableName);
        Assertions.assertThat(updatedFiles)
                .doesNotContain(initialActiveFiles.stream()
                                .filter(path -> !path.contains("regionkey=1"))
                                .collect(toImmutableList())
                                .toArray(new String[0]));
    }

    @Test
    public void testUpgradeTableToV2FromTrino()
    {
        String tableName = "test_upgrade_table_to_v2_from_trino_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 1) AS SELECT * FROM tpch.tiny.nation", 25);
        assertEquals(loadTable(tableName).operations().current().formatVersion(), 1);
        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES format_version = 2");
        assertEquals(loadTable(tableName).operations().current().formatVersion(), 2);
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation");
    }

    @Test
    public void testDowngradingV2TableToV1Fails()
    {
        String tableName = "test_downgrading_v2_table_to_v1_fails_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 2) AS SELECT * FROM tpch.tiny.nation", 25);
        assertEquals(loadTable(tableName).operations().current().formatVersion(), 2);
        assertThatThrownBy(() -> query("ALTER TABLE " + tableName + " SET PROPERTIES format_version = 1"))
                .hasMessage("Failed to set new property values")
                .getRootCause()
                .hasMessage("Cannot downgrade v2 table to v1");
    }

    @Test
    public void testUpgradingToInvalidVersionFails()
    {
        String tableName = "test_upgrading_to_invalid_version_fails_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 2) AS SELECT * FROM tpch.tiny.nation", 25);
        assertEquals(loadTable(tableName).operations().current().formatVersion(), 2);
        assertThatThrownBy(() -> query("ALTER TABLE " + tableName + " SET PROPERTIES format_version = 42"))
                .hasMessage("Unable to set catalog 'iceberg' table property 'format_version' to [42]: format_version must be between 1 and 2");
    }

    @Test
    public void testUpdatingAllTableProperties()
    {
        String tableName = "test_updating_all_table_properties_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 1, format = 'ORC') AS SELECT * FROM tpch.tiny.nation", 25);
        BaseTable table = loadTable(tableName);
        assertEquals(table.operations().current().formatVersion(), 1);
        assertTrue(table.properties().get(TableProperties.DEFAULT_FILE_FORMAT).equalsIgnoreCase("ORC"));
        assertTrue(table.spec().isUnpartitioned());

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES format_version = 2, partitioning = ARRAY['regionkey'], format = 'PARQUET'");
        table = loadTable(tableName);
        assertEquals(table.operations().current().formatVersion(), 2);
        assertTrue(table.properties().get(TableProperties.DEFAULT_FILE_FORMAT).equalsIgnoreCase("PARQUET"));
        assertTrue(table.spec().isPartitioned());
        List<PartitionField> partitionFields = table.spec().fields();
        assertThat(partitionFields).hasSize(1);
        assertEquals(partitionFields.get(0).name(), "regionkey");
        assertTrue(partitionFields.get(0).transform().isIdentity());
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation");
    }

    @Test
    public void testUnsettingAllTableProperties()
    {
        String tableName = "test_unsetting_all_table_properties_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (format_version = 1, format = 'PARQUET', partitioning = ARRAY['regionkey']) AS SELECT * FROM tpch.tiny.nation", 25);
        BaseTable table = loadTable(tableName);
        assertEquals(table.operations().current().formatVersion(), 1);
        assertTrue(table.properties().get(TableProperties.DEFAULT_FILE_FORMAT).equalsIgnoreCase("PARQUET"));
        assertTrue(table.spec().isPartitioned());
        List<PartitionField> partitionFields = table.spec().fields();
        assertThat(partitionFields).hasSize(1);
        assertEquals(partitionFields.get(0).name(), "regionkey");
        assertTrue(partitionFields.get(0).transform().isIdentity());

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES format_version = DEFAULT, format = DEFAULT, partitioning = DEFAULT");
        table = loadTable(tableName);
        assertEquals(table.operations().current().formatVersion(), 2);
        assertTrue(table.properties().get(TableProperties.DEFAULT_FILE_FORMAT).equalsIgnoreCase("ORC"));
        assertTrue(table.spec().isUnpartitioned());
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation");
    }

    @Test
    public void testDeletingEntireFile()
    {
        String tableName = "test_deleting_entire_file_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation WITH NO DATA", 0);
        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM tpch.tiny.nation WHERE regionkey = 1", "SELECT count(*) FROM nation WHERE regionkey = 1");
        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM tpch.tiny.nation WHERE regionkey != 1", "SELECT count(*) FROM nation WHERE regionkey != 1");

        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(2);
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey <= 2", "SELECT count(*) FROM nation WHERE regionkey <= 2");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey > 2");
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(1);
    }

    @Test
    public void testDeletingEntireFileFromPartitionedTable()
    {
        String tableName = "test_deleting_entire_file_from_partitioned_table_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (a INT, b INT) WITH (partitioning = ARRAY['a'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES (1, 1), (1, 3), (1, 5), (2, 1), (2, 3), (2, 5)", 6);
        assertUpdate("INSERT INTO " + tableName + " VALUES (1, 2), (1, 4), (1, 6), (2, 2), (2, 4), (2, 6)", 6);

        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(4);
        assertUpdate("DELETE FROM " + tableName + " WHERE b % 2 = 0", 6);
        assertQuery("SELECT * FROM " + tableName, "VALUES (1, 1), (1, 3), (1, 5), (2, 1), (2, 3), (2, 5)");
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(2);
    }

    @Test
    public void testDeletingEntireFileWithNonTupleDomainConstraint()
    {
        String tableName = "test_deleting_entire_file_with_non_tuple_domain_constraint" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation WITH NO DATA", 0);
        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM tpch.tiny.nation WHERE regionkey = 1", "SELECT count(*) FROM nation WHERE regionkey = 1");
        assertUpdate("INSERT INTO " + tableName + " SELECT * FROM tpch.tiny.nation WHERE regionkey != 1", "SELECT count(*) FROM nation WHERE regionkey != 1");

        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(2);
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey % 2 = 1", "SELECT count(*) FROM nation WHERE regionkey % 2 = 1");
        assertQuery("SELECT * FROM " + tableName, "SELECT * FROM nation WHERE regionkey % 2 = 0");
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(1);
    }

    @Test
    public void testDeletingEntireFileWithMultipleSplits()
    {
        String tableName = "test_deleting_entire_file_with_multiple_splits" + randomTableSuffix();
        assertUpdate(
                Session.builder(getSession()).setCatalogSessionProperty("iceberg", "orc_writer_max_stripe_rows", "5").build(),
                "CREATE TABLE " + tableName + " WITH (format = 'ORC') AS SELECT * FROM tpch.tiny.nation", 25);
        // Set the split size to a small number of bytes so each ORC stripe gets its own split
        this.loadTable(tableName).updateProperties().set(SPLIT_SIZE, "100").commit();

        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(1);
        // Ensure only one snapshot is committed to the table
        Long initialSnapshotId = (Long) computeActual("SELECT snapshot_id FROM \"" + tableName + "$snapshots\" ORDER BY committed_at DESC LIMIT 1").getOnlyValue();
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey < 10", 25);
        Long parentSnapshotId = (Long) computeActual("SELECT parent_id FROM \"" + tableName + "$snapshots\" ORDER BY committed_at DESC LIMIT 1").getOnlyValue();
        assertEquals(initialSnapshotId, parentSnapshotId);
        assertThat(query("SELECT * FROM " + tableName)).returnsEmptyResult();
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(0);
    }

    @Test
    public void testMultipleDeletes()
    {
        // Deletes only remove entire data files from the table if the whole file is removed in a single operation
        String tableName = "test_multiple_deletes_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT * FROM tpch.tiny.nation", 25);
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(1);
        // Ensure only one snapshot is committed to the table
        Long initialSnapshotId = (Long) computeActual("SELECT snapshot_id FROM \"" + tableName + "$snapshots\" ORDER BY committed_at DESC LIMIT 1").getOnlyValue();
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey % 2 = 1", "SELECT count(*) FROM nation WHERE regionkey % 2 = 1");
        Long parentSnapshotId = (Long) computeActual("SELECT parent_id FROM \"" + tableName + "$snapshots\" ORDER BY committed_at DESC LIMIT 1").getOnlyValue();
        assertEquals(initialSnapshotId, parentSnapshotId);

        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey % 2 = 0", "SELECT count(*) FROM nation WHERE regionkey % 2 = 0");
        assertThat(query("SELECT * FROM " + tableName)).returnsEmptyResult();
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(1);
    }

    @Test
    public void testDeletingEntirePartitionedTable()
    {
        String tableName = "test_deleting_entire_partitioned_table_" + randomTableSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (partitioning = ARRAY['regionkey']) AS SELECT * FROM tpch.tiny.nation", 25);

        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(5);
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey < 10", "SELECT count(*) FROM nation WHERE regionkey < 10");
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(0);
        assertUpdate("DELETE FROM " + tableName + " WHERE regionkey < 10");
        assertThat(query("SELECT * FROM " + tableName)).returnsEmptyResult();
        assertThat(this.loadTable(tableName).newScan().planFiles()).hasSize(0);
    }

    private void writeEqualityDeleteToNationTable(Table icebergTable)
            throws Exception
    {
        writeEqualityDeleteToNationTable(icebergTable, Optional.empty(), Optional.empty());
    }

    private void writeEqualityDeleteToNationTable(Table icebergTable, Optional<PartitionSpec> partitionSpec, Optional<PartitionData> partitionData)
            throws Exception
    {
        Path metadataDir = new Path(metastoreDir.toURI());
        String deleteFileName = "delete_file_" + UUID.randomUUID();
        FileSystem fs = hdfsEnvironment.getFileSystem(new HdfsContext(SESSION), metadataDir);

        Schema deleteRowSchema = icebergTable.schema().select("regionkey");
        Parquet.DeleteWriteBuilder writerBuilder = Parquet.writeDeletes(HadoopOutputFile.fromPath(new Path(metadataDir, deleteFileName), fs))
                .forTable(icebergTable)
                .rowSchema(deleteRowSchema)
                .createWriterFunc(GenericParquetWriter::buildWriter)
                .equalityFieldIds(deleteRowSchema.findField("regionkey").fieldId())
                .overwrite();
        if (partitionSpec.isPresent() && partitionData.isPresent()) {
            writerBuilder = writerBuilder
                    .withSpec(partitionSpec.get())
                    .withPartition(partitionData.get());
        }
        EqualityDeleteWriter<Record> writer = writerBuilder.buildEqualityWriter();

        Record dataDelete = GenericRecord.create(deleteRowSchema);
        try (Closeable ignored = writer) {
            writer.delete(dataDelete.copy("regionkey", 1L));
        }

        icebergTable.newRowDelta().addDeletes(writer.toDeleteFile()).commit();
    }

    private Table updateTableToV2(String tableName)
    {
        BaseTable table = loadTable(tableName);
        TableOperations operations = table.operations();
        TableMetadata currentMetadata = operations.current();
        operations.commit(currentMetadata, currentMetadata.upgradeToFormatVersion(2));

        return table;
    }

    private BaseTable loadTable(String tableName)
    {
        IcebergTableOperationsProvider tableOperationsProvider = new FileMetastoreTableOperationsProvider(new HdfsFileIoProvider(hdfsEnvironment));
        TrinoCatalog catalog = new TrinoHiveCatalog(
                new CatalogName("hive"),
                CachingHiveMetastore.memoizeMetastore(metastore, 1000),
                hdfsEnvironment,
                new TestingTypeManager(),
                tableOperationsProvider,
                "test",
                false,
                false,
                false);
        return (BaseTable) loadIcebergTable(catalog, tableOperationsProvider, SESSION, new SchemaTableName("tpch", tableName));
    }

    private List<String> getActiveFiles(String tableName)
    {
        return computeActual(format("SELECT file_path FROM \"%s$files\"", tableName)).getOnlyColumn()
                .map(String.class::cast)
                .collect(toImmutableList());
    }
}
