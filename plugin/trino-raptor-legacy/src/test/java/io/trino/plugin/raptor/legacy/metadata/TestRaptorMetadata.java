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
package io.trino.plugin.raptor.legacy.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.metadata.MetadataUtil.TableMetadataBuilder;
import io.trino.plugin.raptor.legacy.NodeSupplier;
import io.trino.plugin.raptor.legacy.RaptorColumnHandle;
import io.trino.plugin.raptor.legacy.RaptorMetadata;
import io.trino.plugin.raptor.legacy.RaptorPartitioningHandle;
import io.trino.plugin.raptor.legacy.RaptorSessionProperties;
import io.trino.plugin.raptor.legacy.RaptorTableHandle;
import io.trino.plugin.raptor.legacy.storage.StorageManagerConfig;
import io.trino.spi.NodeManager;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.testing.TestingConnectorSession;
import io.trino.testing.TestingNodeManager;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import static com.google.common.base.Ticker.systemTicker;
import static io.airlift.testing.Assertions.assertInstanceOf;
import static io.trino.metadata.MetadataUtil.TableMetadataBuilder.tableMetadataBuilder;
import static io.trino.plugin.raptor.legacy.DatabaseTesting.createTestingJdbi;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.BUCKETED_ON_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.BUCKET_COUNT_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.DISTRIBUTION_NAME_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.ORDERING_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.ORGANIZED_PROPERTY;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.TEMPORAL_COLUMN_PROPERTY;
import static io.trino.plugin.raptor.legacy.metadata.SchemaDaoUtil.createTablesWithRetry;
import static io.trino.plugin.raptor.legacy.metadata.TestDatabaseShardManager.createShardManager;
import static io.trino.spi.StandardErrorCode.TRANSACTION_CONFLICT;
import static io.trino.spi.connector.RetryMode.NO_RETRIES;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.testing.QueryAssertions.assertEqualsIgnoreOrder;
import static io.trino.testing.assertions.TrinoExceptionAssert.assertTrinoExceptionThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestRaptorMetadata
{
    private static final SchemaTableName DEFAULT_TEST_ORDERS = new SchemaTableName("test", "orders");
    private static final SchemaTableName DEFAULT_TEST_LINEITEMS = new SchemaTableName("test", "lineitems");
    private static final ConnectorSession SESSION = TestingConnectorSession.builder()
            .setPropertyMetadata(new RaptorSessionProperties(new StorageManagerConfig()).getSessionProperties())
            .build();

    private Jdbi dbi;
    private Handle dummyHandle;
    private ShardManager shardManager;
    private RaptorMetadata metadata;

    @BeforeMethod
    public void setupDatabase()
    {
        dbi = createTestingJdbi();
        dummyHandle = dbi.open();
        createTablesWithRetry(dbi);

        NodeManager nodeManager = new TestingNodeManager();
        NodeSupplier nodeSupplier = nodeManager::getWorkerNodes;
        shardManager = createShardManager(dbi, nodeSupplier, systemTicker());
        metadata = new RaptorMetadata(dbi, shardManager);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupDatabase()
    {
        dummyHandle.close();
        dummyHandle = null;
    }

    @Test
    public void testRenameColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, getOrdersTable(), false);
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);

        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        ColumnHandle columnHandle = metadata.getColumnHandles(SESSION, tableHandle).get("orderkey");

        metadata.renameColumn(SESSION, raptorTableHandle, columnHandle, "orderkey_renamed");

        assertNull(metadata.getColumnHandles(SESSION, tableHandle).get("orderkey"));
        assertNotNull(metadata.getColumnHandles(SESSION, tableHandle).get("orderkey_renamed"));
    }

    @Test
    public void testAddColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, buildTable(ImmutableMap.of(), tableMetadataBuilder(DEFAULT_TEST_ORDERS)
                        .column("orderkey", BIGINT)
                        .column("price", BIGINT)),
                false);
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);

        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;

        metadata.addColumn(SESSION, raptorTableHandle, new ColumnMetadata("new_col", BIGINT));
        assertNotNull(metadata.getColumnHandles(SESSION, raptorTableHandle).get("new_col"));
    }

    @Test
    public void testDropColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, buildTable(ImmutableMap.of(), tableMetadataBuilder(DEFAULT_TEST_ORDERS)
                        .column("orderkey", BIGINT)
                        .column("price", BIGINT)),
                false);
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);

        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;

        ColumnHandle lastColumn = metadata.getColumnHandles(SESSION, tableHandle).get("orderkey");
        metadata.dropColumn(SESSION, raptorTableHandle, lastColumn);
        assertNull(metadata.getColumnHandles(SESSION, tableHandle).get("orderkey"));
    }

    @Test
    public void testAddColumnAfterDropColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, buildTable(ImmutableMap.of(), tableMetadataBuilder(DEFAULT_TEST_ORDERS)
                        .column("orderkey", BIGINT)
                        .column("price", BIGINT)),
                false);
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);

        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        ColumnHandle column = metadata.getColumnHandles(SESSION, tableHandle).get("orderkey");

        metadata.dropColumn(SESSION, raptorTableHandle, column);
        metadata.addColumn(SESSION, raptorTableHandle, new ColumnMetadata("new_col", BIGINT));
        assertNull(metadata.getColumnHandles(SESSION, tableHandle).get("orderkey"));
        assertNotNull(metadata.getColumnHandles(SESSION, raptorTableHandle).get("new_col"));
    }

    @Test
    public void testDropColumnDisallowed()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        Map<String, Object> properties = ImmutableMap.of(
                BUCKET_COUNT_PROPERTY, 16,
                BUCKETED_ON_PROPERTY, ImmutableList.of("orderkey"),
                ORDERING_PROPERTY, ImmutableList.of("totalprice"),
                TEMPORAL_COLUMN_PROPERTY, "orderdate");
        ConnectorTableMetadata ordersTable = buildTable(properties, tableMetadataBuilder(DEFAULT_TEST_ORDERS)
                .column("orderkey", BIGINT)
                .column("totalprice", DOUBLE)
                .column("orderdate", DATE)
                .column("highestid", BIGINT));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle ordersTableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(ordersTableHandle, RaptorTableHandle.class);
        RaptorTableHandle ordersRaptorTableHandle = (RaptorTableHandle) ordersTableHandle;
        assertEquals(ordersRaptorTableHandle.getTableId(), 1);

        assertInstanceOf(ordersRaptorTableHandle, RaptorTableHandle.class);

        // disallow dropping bucket, sort, temporal and highest-id columns
        ColumnHandle bucketColumn = metadata.getColumnHandles(SESSION, ordersRaptorTableHandle).get("orderkey");
        assertThatThrownBy(() -> metadata.dropColumn(SESSION, ordersTableHandle, bucketColumn))
                .isInstanceOf(TrinoException.class)
                .hasMessage("Cannot drop bucket columns");

        ColumnHandle sortColumn = metadata.getColumnHandles(SESSION, ordersRaptorTableHandle).get("totalprice");
        assertThatThrownBy(() -> metadata.dropColumn(SESSION, ordersTableHandle, sortColumn))
                .isInstanceOf(TrinoException.class)
                .hasMessage("Cannot drop sort columns");

        ColumnHandle temporalColumn = metadata.getColumnHandles(SESSION, ordersRaptorTableHandle).get("orderdate");
        assertThatThrownBy(() -> metadata.dropColumn(SESSION, ordersTableHandle, temporalColumn))
                .isInstanceOf(TrinoException.class)
                .hasMessage("Cannot drop the temporal column");

        ColumnHandle highestColumn = metadata.getColumnHandles(SESSION, ordersRaptorTableHandle).get("highestid");
        assertThatThrownBy(() -> metadata.dropColumn(SESSION, ordersTableHandle, highestColumn))
                .isInstanceOf(TrinoException.class)
                .hasMessage("Cannot drop the column which has the largest column ID in the table");
    }

    @Test
    public void testRenameTable()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, getOrdersTable(), false);
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);

        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        SchemaTableName renamedTable = new SchemaTableName(raptorTableHandle.getSchemaName(), "orders_renamed");

        metadata.renameTable(SESSION, raptorTableHandle, renamedTable);
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        ConnectorTableHandle renamedTableHandle = metadata.getTableHandle(SESSION, renamedTable);
        assertNotNull(renamedTableHandle);
        assertEquals(((RaptorTableHandle) renamedTableHandle).getTableName(), renamedTable.getTableName());
    }

    @Test
    public void testCreateTable()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        metadata.createTable(SESSION, getOrdersTable(), false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        assertEquals(((RaptorTableHandle) tableHandle).getTableId(), 1);

        ConnectorTableMetadata table = metadata.getTableMetadata(SESSION, tableHandle);
        assertTableEqual(table, getOrdersTable());

        ColumnHandle columnHandle = metadata.getColumnHandles(SESSION, tableHandle).get("orderkey");
        assertInstanceOf(columnHandle, RaptorColumnHandle.class);
        assertEquals(((RaptorColumnHandle) columnHandle).getColumnId(), 1);

        ColumnMetadata columnMetadata = metadata.getColumnMetadata(SESSION, tableHandle, columnHandle);
        assertNotNull(columnMetadata);
        assertEquals(columnMetadata.getName(), "orderkey");
        assertEquals(columnMetadata.getType(), BIGINT);
    }

    @Test
    public void testTableProperties()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(
                ORDERING_PROPERTY, ImmutableList.of("orderdate", "custkey"),
                TEMPORAL_COLUMN_PROPERTY, "orderdate"));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        // verify sort columns
        List<TableColumn> sortColumns = metadataDao.listSortColumns(tableId);
        assertTableColumnsEqual(sortColumns, ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "orderdate", DATE, 4, 3, OptionalInt.empty(), OptionalInt.of(0), true),
                new TableColumn(DEFAULT_TEST_ORDERS, "custkey", BIGINT, 2, 1, OptionalInt.empty(), OptionalInt.of(1), false)));

        // verify temporal column
        assertEquals(metadataDao.getTemporalColumnId(tableId), Long.valueOf(4));

        // verify no organization
        assertFalse(metadataDao.getTableInformation(tableId).isOrganized());

        metadata.dropTable(SESSION, tableHandle);
    }

    @Test
    public void testTablePropertiesWithOrganization()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(
                ORDERING_PROPERTY, ImmutableList.of("orderdate", "custkey"),
                ORGANIZED_PROPERTY, true));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        // verify sort columns
        List<TableColumn> sortColumns = metadataDao.listSortColumns(tableId);
        assertTableColumnsEqual(sortColumns, ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "orderdate", DATE, 4, 3, OptionalInt.empty(), OptionalInt.of(0), false),
                new TableColumn(DEFAULT_TEST_ORDERS, "custkey", BIGINT, 2, 1, OptionalInt.empty(), OptionalInt.of(1), false)));

        // verify organization
        assertTrue(metadataDao.getTableInformation(tableId).isOrganized());

        metadata.dropTable(SESSION, tableHandle);
    }

    @Test
    public void testCreateBucketedTable()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(
                BUCKET_COUNT_PROPERTY, 16,
                BUCKETED_ON_PROPERTY, ImmutableList.of("custkey", "orderkey")));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        assertTableColumnsEqual(metadataDao.listBucketColumns(tableId), ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "custkey", BIGINT, 2, 1, OptionalInt.of(0), OptionalInt.empty(), false),
                new TableColumn(DEFAULT_TEST_ORDERS, "orderkey", BIGINT, 1, 0, OptionalInt.of(1), OptionalInt.empty(), false)));

        assertEquals(raptorTableHandle.getBucketCount(), OptionalInt.of(16));

        assertEquals(getTableDistributionId(tableId), Long.valueOf(1));

        metadata.dropTable(SESSION, tableHandle);

        // create a new table and verify it has a different distribution
        metadata.createTable(SESSION, ordersTable, false);
        tableId = ((RaptorTableHandle) metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS)).getTableId();
        assertEquals(tableId, 2);
        assertEquals(getTableDistributionId(tableId), Long.valueOf(2));
    }

    @Test
    public void testCreateBucketedTableAsSelect()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(
                BUCKET_COUNT_PROPERTY, 32,
                BUCKETED_ON_PROPERTY, ImmutableList.of("orderkey", "custkey")));

        ConnectorTableLayout layout = metadata.getNewTableLayout(SESSION, ordersTable).get();
        assertEquals(layout.getPartitionColumns(), ImmutableList.of("orderkey", "custkey"));
        assertTrue(layout.getPartitioning().isPresent());
        assertInstanceOf(layout.getPartitioning().get(), RaptorPartitioningHandle.class);
        RaptorPartitioningHandle partitioning = (RaptorPartitioningHandle) layout.getPartitioning().get();
        assertEquals(partitioning.getDistributionId(), 1);

        ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(SESSION, ordersTable, Optional.of(layout), NO_RETRIES);
        metadata.finishCreateTable(SESSION, outputHandle, ImmutableList.of(), ImmutableList.of());

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        assertTableColumnsEqual(metadataDao.listBucketColumns(tableId), ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "orderkey", BIGINT, 1, 0, OptionalInt.of(0), OptionalInt.empty(), false),
                new TableColumn(DEFAULT_TEST_ORDERS, "custkey", BIGINT, 2, 1, OptionalInt.of(1), OptionalInt.empty(), false)));

        assertEquals(raptorTableHandle.getBucketCount(), OptionalInt.of(32));

        assertEquals(getTableDistributionId(tableId), Long.valueOf(1));

        metadata.dropTable(SESSION, tableHandle);
    }

    @Test
    public void testCreateBucketedTableExistingDistribution()
    {
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        // create orders table
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata table = getOrdersTable(ImmutableMap.of(
                BUCKET_COUNT_PROPERTY, 16,
                BUCKETED_ON_PROPERTY, ImmutableList.of("orderkey"),
                DISTRIBUTION_NAME_PROPERTY, "orders"));
        metadata.createTable(SESSION, table, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;

        long tableId = raptorTableHandle.getTableId();
        assertEquals(raptorTableHandle.getTableId(), 1);

        assertTableColumnsEqual(metadataDao.listBucketColumns(tableId), ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "orderkey", BIGINT, 1, 0, OptionalInt.of(0), OptionalInt.empty(), false)));

        assertEquals(raptorTableHandle.getBucketCount(), OptionalInt.of(16));

        assertEquals(getTableDistributionId(tableId), Long.valueOf(1));

        // create lineitems table
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_LINEITEMS));

        table = getLineItemsTable(ImmutableMap.of(
                BUCKET_COUNT_PROPERTY, 16,
                BUCKETED_ON_PROPERTY, ImmutableList.of("orderkey"),
                DISTRIBUTION_NAME_PROPERTY, "orders"));
        metadata.createTable(SESSION, table, false);

        tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_LINEITEMS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        raptorTableHandle = (RaptorTableHandle) tableHandle;

        tableId = raptorTableHandle.getTableId();
        assertEquals(tableId, 2);

        assertTableColumnsEqual(metadataDao.listBucketColumns(tableId), ImmutableList.of(
                new TableColumn(DEFAULT_TEST_LINEITEMS, "orderkey", BIGINT, 1, 0, OptionalInt.of(0), OptionalInt.empty(), false)));

        assertEquals(raptorTableHandle.getBucketCount(), OptionalInt.of(16));

        assertEquals(getTableDistributionId(tableId), Long.valueOf(1));
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "Ordering column does not exist: orderdatefoo")
    public void testInvalidOrderingColumns()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(ORDERING_PROPERTY, ImmutableList.of("orderdatefoo")));
        metadata.createTable(SESSION, ordersTable, false);
        fail("Expected createTable to fail");
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "Temporal column does not exist: foo")
    public void testInvalidTemporalColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(TEMPORAL_COLUMN_PROPERTY, "foo"));
        metadata.createTable(SESSION, ordersTable, false);
        fail("Expected createTable to fail");
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "Temporal column must be of type timestamp or date: orderkey")
    public void testInvalidTemporalColumnType()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, getOrdersTable(ImmutableMap.of(TEMPORAL_COLUMN_PROPERTY, "orderkey")), false);
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "Table with temporal columns cannot be organized")
    public void testInvalidTemporalOrganization()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, getOrdersTable(ImmutableMap.of(
                TEMPORAL_COLUMN_PROPERTY, "orderdate",
                ORGANIZED_PROPERTY, true)),
                false);
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "Table organization requires an ordering")
    public void testInvalidOrderingOrganization()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));
        metadata.createTable(SESSION, getOrdersTable(ImmutableMap.of(ORGANIZED_PROPERTY, true)), false);
    }

    @Test
    public void testSortOrderProperty()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(ORDERING_PROPERTY, ImmutableList.of("orderdate", "custkey")));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        // verify sort columns
        List<TableColumn> sortColumns = metadataDao.listSortColumns(tableId);
        assertTableColumnsEqual(sortColumns, ImmutableList.of(
                new TableColumn(DEFAULT_TEST_ORDERS, "orderdate", DATE, 4, 3, OptionalInt.empty(), OptionalInt.of(0), false),
                new TableColumn(DEFAULT_TEST_ORDERS, "custkey", BIGINT, 2, 1, OptionalInt.empty(), OptionalInt.of(1), false)));

        // verify temporal column is not set
        assertEquals(metadataDao.getTemporalColumnId(tableId), null);
        metadata.dropTable(SESSION, tableHandle);
    }

    @Test
    public void testTemporalColumn()
    {
        assertNull(metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS));

        ConnectorTableMetadata ordersTable = getOrdersTable(ImmutableMap.of(TEMPORAL_COLUMN_PROPERTY, "orderdate"));
        metadata.createTable(SESSION, ordersTable, false);

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        assertInstanceOf(tableHandle, RaptorTableHandle.class);
        RaptorTableHandle raptorTableHandle = (RaptorTableHandle) tableHandle;
        assertEquals(raptorTableHandle.getTableId(), 1);

        long tableId = raptorTableHandle.getTableId();
        MetadataDao metadataDao = dbi.onDemand(MetadataDao.class);

        // verify sort columns are not set
        List<TableColumn> sortColumns = metadataDao.listSortColumns(tableId);
        assertEquals(sortColumns.size(), 0);
        assertEquals(sortColumns, ImmutableList.of());

        // verify temporal column is set
        assertEquals(metadataDao.getTemporalColumnId(tableId), Long.valueOf(4));
        metadata.dropTable(SESSION, tableHandle);
    }

    @Test
    public void testListTables()
    {
        metadata.createTable(SESSION, getOrdersTable(), false);
        List<SchemaTableName> tables = metadata.listTables(SESSION, Optional.empty());
        assertEquals(tables, ImmutableList.of(DEFAULT_TEST_ORDERS));
    }

    @Test
    public void testListTableColumns()
    {
        metadata.createTable(SESSION, getOrdersTable(), false);
        Map<SchemaTableName, List<ColumnMetadata>> columns = metadata.listTableColumns(SESSION, new SchemaTablePrefix());
        assertEquals(columns, ImmutableMap.of(DEFAULT_TEST_ORDERS, getOrdersTable().getColumns()));
    }

    @Test
    public void testListTableColumnsFiltering()
    {
        metadata.createTable(SESSION, getOrdersTable(), false);
        Map<SchemaTableName, List<ColumnMetadata>> filterCatalog = metadata.listTableColumns(SESSION, new SchemaTablePrefix());
        Map<SchemaTableName, List<ColumnMetadata>> filterSchema = metadata.listTableColumns(SESSION, new SchemaTablePrefix("test"));
        Map<SchemaTableName, List<ColumnMetadata>> filterTable = metadata.listTableColumns(SESSION, new SchemaTablePrefix("test", "orders"));
        assertEquals(filterCatalog, filterSchema);
        assertEquals(filterCatalog, filterTable);
    }

    @Test
    public void testViews()
    {
        SchemaTableName test1 = new SchemaTableName("test", "test_view1");
        SchemaTableName test2 = new SchemaTableName("test", "test_view2");

        // create views
        metadata.createView(SESSION, test1, testingViewDefinition("test1"), false);
        metadata.createView(SESSION, test2, testingViewDefinition("test2"), false);

        // verify listing
        List<SchemaTableName> list = metadata.listViews(SESSION, Optional.of("test"));
        assertEqualsIgnoreOrder(list, ImmutableList.of(test1, test2));

        // verify getting data
        Map<SchemaTableName, ConnectorViewDefinition> views = metadata.getViews(SESSION, Optional.of("test"));
        assertEquals(views.keySet(), ImmutableSet.of(test1, test2));
        assertEquals(views.get(test1).getOriginalSql(), "test1");
        assertEquals(views.get(test2).getOriginalSql(), "test2");

        // drop first view
        metadata.dropView(SESSION, test1);

        assertThat(metadata.getViews(SESSION, Optional.of("test")))
                .containsOnlyKeys(test2);

        // drop second view
        metadata.dropView(SESSION, test2);

        assertThat(metadata.getViews(SESSION, Optional.of("test")))
                .isEmpty();

        // verify listing everything
        assertThat(metadata.getViews(SESSION, Optional.empty()))
                .isEmpty();
    }

    @Test(expectedExceptions = TrinoException.class, expectedExceptionsMessageRegExp = "View already exists: test\\.test_view")
    public void testCreateViewWithoutReplace()
    {
        SchemaTableName test = new SchemaTableName("test", "test_view");
        try {
            metadata.createView(SESSION, test, testingViewDefinition("test"), false);
        }
        catch (Exception e) {
            fail("should have succeeded");
        }

        metadata.createView(SESSION, test, testingViewDefinition("test"), false);
    }

    @Test
    public void testCreateViewWithReplace()
    {
        SchemaTableName test = new SchemaTableName("test", "test_view");

        metadata.createView(SESSION, test, testingViewDefinition("aaa"), true);
        metadata.createView(SESSION, test, testingViewDefinition("bbb"), true);

        assertThat(metadata.getView(SESSION, test))
                .map(ConnectorViewDefinition::getOriginalSql)
                .contains("bbb");
    }

    @Test
    public void testTransactionTableWrite()
    {
        // start table creation
        long transactionId = 1;
        ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(SESSION, getOrdersTable(), Optional.empty(), NO_RETRIES);

        // transaction is in progress
        assertTrue(transactionExists(transactionId));
        assertNull(transactionSuccessful(transactionId));

        // commit table creation
        metadata.finishCreateTable(SESSION, outputHandle, ImmutableList.of(), ImmutableList.of());
        assertTrue(transactionExists(transactionId));
        assertTrue(transactionSuccessful(transactionId));
    }

    @Test
    public void testTransactionInsert()
    {
        // creating a table allocates a transaction
        long transactionId = 1;
        metadata.createTable(SESSION, getOrdersTable(), false);
        assertTrue(transactionSuccessful(transactionId));

        // start insert
        transactionId++;
        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, DEFAULT_TEST_ORDERS);
        ConnectorInsertTableHandle insertHandle = metadata.beginInsert(SESSION, tableHandle, ImmutableList.of(), NO_RETRIES);

        // transaction is in progress
        assertTrue(transactionExists(transactionId));
        assertNull(transactionSuccessful(transactionId));

        // commit insert
        metadata.finishInsert(SESSION, insertHandle, ImmutableList.of(), ImmutableList.of());
        assertTrue(transactionExists(transactionId));
        assertTrue(transactionSuccessful(transactionId));
    }

    @Test
    public void testTransactionAbort()
    {
        // start table creation
        long transactionId = 1;
        ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(SESSION, getOrdersTable(), Optional.empty(), NO_RETRIES);

        // transaction is in progress
        assertTrue(transactionExists(transactionId));
        assertNull(transactionSuccessful(transactionId));

        // force transaction to abort
        shardManager.rollbackTransaction(transactionId);
        assertTrue(transactionExists(transactionId));
        assertFalse(transactionSuccessful(transactionId));

        // commit table creation
        assertTrinoExceptionThrownBy(() -> metadata.finishCreateTable(SESSION, outputHandle, ImmutableList.of(), ImmutableList.of()))
                .hasErrorCode(TRANSACTION_CONFLICT)
                .hasMessage("Transaction commit failed. Please retry the operation.");
    }

    private boolean transactionExists(long transactionId)
    {
        return dbi.withHandle(handle -> handle
                .select("SELECT count(*) FROM transactions WHERE transaction_id = ?", transactionId)
                .mapTo(boolean.class)
                .one());
    }

    private Boolean transactionSuccessful(long transactionId)
    {
        return dbi.withHandle(handle -> handle
                .select("SELECT successful FROM transactions WHERE transaction_id = ?", transactionId)
                .mapTo(Boolean.class)
                .findFirst()
                .orElse(null));
    }

    private Long getTableDistributionId(long tableId)
    {
        return dbi.withHandle(handle -> handle
                .select("SELECT distribution_id FROM tables WHERE table_id = ?", tableId)
                .mapTo(Long.class)
                .findFirst()
                .orElse(null));
    }

    private static ConnectorTableMetadata getOrdersTable()
    {
        return getOrdersTable(ImmutableMap.of());
    }

    private static ConnectorTableMetadata getOrdersTable(Map<String, Object> properties)
    {
        return buildTable(properties, tableMetadataBuilder(DEFAULT_TEST_ORDERS)
                .column("orderkey", BIGINT)
                .column("custkey", BIGINT)
                .column("totalprice", DOUBLE)
                .column("orderdate", DATE));
    }

    private static ConnectorTableMetadata getLineItemsTable(Map<String, Object> properties)
    {
        return buildTable(properties, tableMetadataBuilder(DEFAULT_TEST_LINEITEMS)
                .column("orderkey", BIGINT)
                .column("partkey", BIGINT)
                .column("quantity", DOUBLE)
                .column("price", DOUBLE));
    }

    private static ConnectorTableMetadata buildTable(Map<String, Object> properties, TableMetadataBuilder builder)
    {
        if (!properties.isEmpty()) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                builder.property(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    private static ConnectorViewDefinition testingViewDefinition(String sql)
    {
        return new ConnectorViewDefinition(
                sql,
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(new ViewColumn("test", BIGINT.getTypeId(), Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                true);
    }

    private static void assertTableEqual(ConnectorTableMetadata actual, ConnectorTableMetadata expected)
    {
        assertEquals(actual.getTable(), expected.getTable());

        List<ColumnMetadata> actualColumns = actual.getColumns().stream()
                .filter(columnMetadata -> !columnMetadata.isHidden())
                .collect(Collectors.toList());

        List<ColumnMetadata> expectedColumns = expected.getColumns();
        assertEquals(actualColumns.size(), expectedColumns.size());
        for (int i = 0; i < actualColumns.size(); i++) {
            ColumnMetadata actualColumn = actualColumns.get(i);
            ColumnMetadata expectedColumn = expectedColumns.get(i);
            assertEquals(actualColumn.getName(), expectedColumn.getName());
            assertEquals(actualColumn.getType(), expectedColumn.getType());
        }
        assertEquals(actual.getProperties(), expected.getProperties());
    }

    private static void assertTableColumnEqual(TableColumn actual, TableColumn expected)
    {
        assertEquals(actual.getTable(), expected.getTable());
        assertEquals(actual.getColumnId(), expected.getColumnId());
        assertEquals(actual.getColumnName(), expected.getColumnName());
        assertEquals(actual.getDataType(), expected.getDataType());
        assertEquals(actual.getOrdinalPosition(), expected.getOrdinalPosition());
        assertEquals(actual.getBucketOrdinal(), expected.getBucketOrdinal());
        assertEquals(actual.getSortOrdinal(), expected.getSortOrdinal());
        assertEquals(actual.isTemporal(), expected.isTemporal());
    }

    private static void assertTableColumnsEqual(List<TableColumn> actual, List<TableColumn> expected)
    {
        assertEquals(actual.size(), expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertTableColumnEqual(actual.get(i), expected.get(i));
        }
    }
}
