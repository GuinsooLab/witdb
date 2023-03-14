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
package io.trino.plugin.iceberg.catalog.rest;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.http.server.testing.TestingHttpServer;
import io.trino.plugin.iceberg.BaseIcebergConnectorSmokeTest;
import io.trino.plugin.iceberg.IcebergConfig;
import io.trino.plugin.iceberg.IcebergQueryRunner;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.tpch.TpchTable;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.DelegatingRestSessionCatalog;
import org.assertj.core.util.Files;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.plugin.iceberg.IcebergTestUtils.checkOrcFileSorting;
import static io.trino.plugin.iceberg.catalog.rest.RestCatalogTestUtils.backendCatalog;
import static io.trino.tpch.TpchTable.LINE_ITEM;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestIcebergRestCatalogConnectorSmokeTest
        extends BaseIcebergConnectorSmokeTest
{
    private File warehouseLocation;

    public TestIcebergRestCatalogConnectorSmokeTest()
    {
        super(new IcebergConfig().getFileFormat().toIceberg());
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        return switch (connectorBehavior) {
            case SUPPORTS_RENAME_SCHEMA -> false;
            case SUPPORTS_CREATE_VIEW, SUPPORTS_COMMENT_ON_VIEW, SUPPORTS_COMMENT_ON_VIEW_COLUMN -> false;
            case SUPPORTS_CREATE_MATERIALIZED_VIEW, SUPPORTS_RENAME_MATERIALIZED_VIEW -> false;
            default -> super.hasBehavior(connectorBehavior);
        };
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        warehouseLocation = Files.newTemporaryFolder();
        closeAfterClass(() -> deleteRecursively(warehouseLocation.toPath(), ALLOW_INSECURE));

        Catalog backend = backendCatalog(warehouseLocation);

        DelegatingRestSessionCatalog delegatingCatalog = DelegatingRestSessionCatalog.builder()
                .delegate(backend)
                .build();

        TestingHttpServer testServer = delegatingCatalog.testServer();
        testServer.start();
        closeAfterClass(testServer::stop);

        return IcebergQueryRunner.builder()
                .setBaseDataDir(Optional.of(warehouseLocation.toPath()))
                .setIcebergProperties(
                        ImmutableMap.<String, String>builder()
                                .put("iceberg.file-format", format.name())
                                .put("iceberg.catalog.type", "rest")
                                .put("iceberg.rest-catalog.uri", testServer.getBaseUrl().toString())
                                .put("iceberg.register-table-procedure.enabled", "true")
                                .put("iceberg.writer-sort-buffer-size", "1MB")
                                .buildOrThrow())
                .setInitialTables(ImmutableList.<TpchTable<?>>builder()
                        .addAll(REQUIRED_TPCH_TABLES)
                        .add(LINE_ITEM)
                        .build())
                .build();
    }

    @Override
    public void testView()
    {
        assertThatThrownBy(super::testView)
                .hasMessageContaining("createView is not supported for Iceberg REST catalog");
    }

    @Override
    public void testMaterializedView()
    {
        assertThatThrownBy(super::testMaterializedView)
                .hasMessageContaining("createMaterializedView is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRenameSchema()
    {
        assertThatThrownBy(super::testRenameSchema)
                .hasMessageContaining("renameNamespace is not supported for Iceberg REST catalog");
    }

    @Override
    protected void dropTableFromMetastore(String tableName)
    {
        // used when registering a table, which is not supported by the REST catalog
    }

    @Override
    protected String getMetadataLocation(String tableName)
    {
        // used when registering a table, which is not supported by the REST catalog
        throw new UnsupportedOperationException("metadata location for register_table is not supported");
    }

    @Override
    protected String schemaPath()
    {
        return format("%s/%s", warehouseLocation, getSession().getSchema());
    }

    @Override
    protected boolean locationExists(String location)
    {
        return java.nio.file.Files.exists(Path.of(location));
    }

    @Override
    public void testRegisterTableWithTableLocation()
    {
        assertThatThrownBy(super::testRegisterTableWithTableLocation)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRegisterTableWithComments()
    {
        assertThatThrownBy(super::testRegisterTableWithComments)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRegisterTableWithShowCreateTable()
    {
        assertThatThrownBy(super::testRegisterTableWithShowCreateTable)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRegisterTableWithReInsert()
    {
        assertThatThrownBy(super::testRegisterTableWithReInsert)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRegisterTableWithDifferentTableName()
    {
        assertThatThrownBy(super::testRegisterTableWithDifferentTableName)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testRegisterTableWithMetadataFile()
    {
        assertThatThrownBy(super::testRegisterTableWithMetadataFile)
                .hasMessageContaining("metadata location for register_table is not supported");
    }

    @Override
    public void testRegisterTableWithTrailingSpaceInLocation()
    {
        assertThatThrownBy(super::testRegisterTableWithTrailingSpaceInLocation)
                .hasMessageContaining("registerTable is not supported for Iceberg REST catalog");
    }

    @Override
    public void testUnregisterTable()
    {
        assertThatThrownBy(super::testUnregisterTable)
                .hasMessageContaining("unregisterTable is not supported for Iceberg REST catalogs");
    }

    @Override
    public void testUnregisterBrokenTable()
    {
        assertThatThrownBy(super::testUnregisterBrokenTable)
                .hasMessageContaining("unregisterTable is not supported for Iceberg REST catalogs");
    }

    @Override
    public void testUnregisterTableNotExistingTable()
    {
        assertThatThrownBy(super::testUnregisterTableNotExistingTable)
                .hasMessageContaining("unregisterTable is not supported for Iceberg REST catalogs");
    }

    @Override
    public void testRepeatUnregisterTable()
    {
        assertThatThrownBy(super::testRepeatUnregisterTable)
                .hasMessageContaining("unregisterTable is not supported for Iceberg REST catalogs");
    }

    @Override
    protected boolean isFileSorted(String path, String sortColumnName)
    {
        return checkOrcFileSorting(path, sortColumnName);
    }

    @Override
    protected void deleteDirectory(String location)
    {
        // used when unregistering a table, which is not supported by the REST catalog
    }
}
