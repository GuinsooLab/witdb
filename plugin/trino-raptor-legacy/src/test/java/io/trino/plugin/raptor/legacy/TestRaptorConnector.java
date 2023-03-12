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
package io.trino.plugin.raptor.legacy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.slice.Slice;
import io.trino.operator.PagesIndex;
import io.trino.operator.PagesIndexPageSorter;
import io.trino.plugin.base.CatalogName;
import io.trino.plugin.raptor.legacy.metadata.MetadataDao;
import io.trino.plugin.raptor.legacy.metadata.ShardManager;
import io.trino.plugin.raptor.legacy.storage.StorageManager;
import io.trino.plugin.raptor.legacy.storage.StorageManagerConfig;
import io.trino.spi.NodeManager;
import io.trino.spi.Page;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorPageSink;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.Type;
import io.trino.testing.MaterializedResult;
import io.trino.testing.TestingConnectorSession;
import io.trino.testing.TestingNodeManager;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Optional;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.operator.scalar.timestamp.VarcharToTimestampCast.castToShortTimestamp;
import static io.trino.plugin.raptor.legacy.DatabaseTesting.createTestingJdbi;
import static io.trino.plugin.raptor.legacy.RaptorTableProperties.TEMPORAL_COLUMN_PROPERTY;
import static io.trino.plugin.raptor.legacy.metadata.SchemaDaoUtil.createTablesWithRetry;
import static io.trino.plugin.raptor.legacy.metadata.TestDatabaseShardManager.createShardManager;
import static io.trino.plugin.raptor.legacy.storage.TestRaptorStorageManager.createRaptorStorageManager;
import static io.trino.spi.connector.RetryMode.NO_RETRIES;
import static io.trino.spi.transaction.IsolationLevel.READ_COMMITTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.testing.TestingPageSinkId.TESTING_PAGE_SINK_ID;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static io.trino.util.DateTimeUtils.parseDate;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestRaptorConnector
{
    private static final ConnectorSession SESSION = TestingConnectorSession.builder()
            .setPropertyMetadata(new RaptorSessionProperties(new StorageManagerConfig()).getSessionProperties())
            .build();

    private Handle dummyHandle;
    private MetadataDao metadataDao;
    private File dataDir;
    private RaptorConnector connector;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        Jdbi dbi = createTestingJdbi();
        dummyHandle = dbi.open();
        metadataDao = dbi.onDemand(MetadataDao.class);
        createTablesWithRetry(dbi);
        dataDir = Files.createTempDirectory(null).toFile();

        CatalogName connectorId = new CatalogName("test");
        NodeManager nodeManager = new TestingNodeManager();
        NodeSupplier nodeSupplier = nodeManager::getWorkerNodes;
        ShardManager shardManager = createShardManager(dbi);
        StorageManager storageManager = createRaptorStorageManager(dbi, dataDir);
        StorageManagerConfig config = new StorageManagerConfig();
        connector = new RaptorConnector(
                new LifeCycleManager(ImmutableList.of(), null),
                new TestingNodeManager(),
                new RaptorMetadataFactory(dbi, shardManager),
                new RaptorSplitManager(connectorId, nodeSupplier, shardManager, false),
                new RaptorPageSourceProvider(storageManager),
                new RaptorPageSinkProvider(storageManager,
                        new PagesIndexPageSorter(new PagesIndex.TestingFactory(false)),
                        config),
                new RaptorNodePartitioningProvider(nodeSupplier),
                new RaptorSessionProperties(config),
                new RaptorTableProperties(TESTING_TYPE_MANAGER),
                ImmutableSet.of(),
                Optional.empty(),
                dbi);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        dummyHandle.close();
        dummyHandle = null;
        deleteRecursively(dataDir.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testMaintenanceBlocked()
    {
        long tableId1 = createTable("test1");
        long tableId2 = createTable("test2");

        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // begin delete for table1
        ConnectorTransactionHandle txn1 = beginTransaction();
        ConnectorTableHandle handle1 = getTableHandle(connector.getMetadata(SESSION, txn1), "test1");
        connector.getMetadata(SESSION, txn1).beginMerge(SESSION, handle1, NO_RETRIES);

        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // begin delete for table2
        ConnectorTransactionHandle txn2 = beginTransaction();
        ConnectorTableHandle handle2 = getTableHandle(connector.getMetadata(SESSION, txn2), "test2");
        connector.getMetadata(SESSION, txn2).beginMerge(SESSION, handle2, NO_RETRIES);

        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // begin another delete for table1
        ConnectorTransactionHandle txn3 = beginTransaction();
        ConnectorTableHandle handle3 = getTableHandle(connector.getMetadata(SESSION, txn3), "test1");
        connector.getMetadata(SESSION, txn3).beginMerge(SESSION, handle3, NO_RETRIES);

        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // commit first delete for table1
        connector.commit(txn1);

        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // rollback second delete for table1
        connector.rollback(txn3);

        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId2));

        // commit delete for table2
        connector.commit(txn2);

        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId1));
        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId2));
    }

    @Test
    public void testMaintenanceUnblockedOnStart()
    {
        long tableId = createTable("test");

        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId));
        metadataDao.blockMaintenance(tableId);
        assertTrue(metadataDao.isMaintenanceBlockedLocked(tableId));

        connector.start();

        assertFalse(metadataDao.isMaintenanceBlockedLocked(tableId));
    }

    @Test
    public void testTemporalShardSplit()
            throws Exception
    {
        // Same date should be in same split
        assertSplitShard(DATE, "2001-08-22", "2001-08-22", 1);

        // Same date should be in different splits
        assertSplitShard(DATE, "2001-08-22", "2001-08-23", 2);

        // Same timestamp should be in same split
        assertSplitShard(TIMESTAMP_MILLIS, "2001-08-22 00:00:01.000", "2001-08-22 23:59:01.000", 1);

        // Same timestamp should be in different splits
        assertSplitShard(TIMESTAMP_MILLIS, "2001-08-22 23:59:01.000", "2001-08-23 00:00:01.000", 2);
    }

    private void assertSplitShard(Type temporalType, String min, String max, int expectedSplits)
            throws Exception
    {
        ConnectorSession session = TestingConnectorSession.builder()
                .setPropertyMetadata(new RaptorSessionProperties(new StorageManagerConfig()).getSessionProperties())
                .build();

        ConnectorTransactionHandle transaction = beginTransaction();
        connector.getMetadata(SESSION, transaction).createTable(
                SESSION,
                new ConnectorTableMetadata(
                        new SchemaTableName("test", "test"),
                        ImmutableList.of(new ColumnMetadata("id", BIGINT), new ColumnMetadata("time", temporalType)),
                        ImmutableMap.of(TEMPORAL_COLUMN_PROPERTY, "time")),
                false);
        connector.commit(transaction);

        ConnectorTransactionHandle txn1 = beginTransaction();
        ConnectorTableHandle handle1 = getTableHandle(connector.getMetadata(SESSION, txn1), "test");
        ConnectorInsertTableHandle insertTableHandle = connector.getMetadata(SESSION, txn1).beginInsert(session, handle1, ImmutableList.of(), NO_RETRIES);
        ConnectorPageSink raptorPageSink = connector.getPageSinkProvider().createPageSink(txn1, session, insertTableHandle, TESTING_PAGE_SINK_ID);

        Object timestamp1 = null;
        Object timestamp2 = null;
        if (temporalType.equals(TIMESTAMP_MILLIS)) {
            timestamp1 = SqlTimestamp.newInstance(3, castToShortTimestamp(TIMESTAMP_MILLIS.getPrecision(), min), 0);
            timestamp2 = SqlTimestamp.newInstance(3, castToShortTimestamp(TIMESTAMP_MILLIS.getPrecision(), max), 0);
        }
        else if (temporalType.equals(DATE)) {
            timestamp1 = new SqlDate(parseDate(min));
            timestamp2 = new SqlDate(parseDate(max));
        }

        Page inputPage = MaterializedResult.resultBuilder(session, ImmutableList.of(BIGINT, temporalType))
                .row(1L, timestamp1)
                .row(2L, timestamp2)
                .build()
                .toPage();

        raptorPageSink.appendPage(inputPage);

        Collection<Slice> shards = raptorPageSink.finish().get();
        assertEquals(shards.size(), expectedSplits);
        connector.getMetadata(session, txn1).dropTable(session, handle1);
        connector.commit(txn1);
    }

    private long createTable(String name)
    {
        ConnectorTransactionHandle transaction = beginTransaction();
        connector.getMetadata(SESSION, transaction).createTable(
                SESSION,
                new ConnectorTableMetadata(
                        new SchemaTableName("test", name),
                        ImmutableList.of(new ColumnMetadata("id", BIGINT))),
                false);
        connector.commit(transaction);

        transaction = beginTransaction();
        ConnectorTableHandle tableHandle = getTableHandle(connector.getMetadata(SESSION, transaction), name);
        connector.commit(transaction);
        return ((RaptorTableHandle) tableHandle).getTableId();
    }

    private ConnectorTransactionHandle beginTransaction()
    {
        return connector.beginTransaction(READ_COMMITTED, false, true);
    }

    private static ConnectorTableHandle getTableHandle(ConnectorMetadata metadata, String name)
    {
        return metadata.getTableHandle(SESSION, new SchemaTableName("test", name));
    }
}
