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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.orc.OrcDataSource;
import io.trino.orc.OrcReaderOptions;
import io.trino.orc.OrcRecordReader;
import io.trino.plugin.raptor.legacy.RaptorColumnHandle;
import io.trino.plugin.raptor.legacy.backup.BackupManager;
import io.trino.plugin.raptor.legacy.backup.BackupStore;
import io.trino.plugin.raptor.legacy.backup.FileBackupStore;
import io.trino.plugin.raptor.legacy.metadata.ColumnStats;
import io.trino.plugin.raptor.legacy.metadata.ShardDelta;
import io.trino.plugin.raptor.legacy.metadata.ShardInfo;
import io.trino.plugin.raptor.legacy.metadata.ShardManager;
import io.trino.plugin.raptor.legacy.metadata.ShardRecorder;
import io.trino.plugin.raptor.legacy.storage.InMemoryShardRecorder.RecordedShard;
import io.trino.spi.NodeManager;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.Type;
import io.trino.testing.MaterializedResult;
import io.trino.testing.TestingNodeManager;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.chrono.ISOChronology;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.plugin.raptor.legacy.DatabaseTesting.createTestingJdbi;
import static io.trino.plugin.raptor.legacy.metadata.SchemaDaoUtil.createTablesWithRetry;
import static io.trino.plugin.raptor.legacy.metadata.TestDatabaseShardManager.createShardManager;
import static io.trino.plugin.raptor.legacy.storage.OrcTestingUtil.createReader;
import static io.trino.plugin.raptor.legacy.storage.OrcTestingUtil.octets;
import static io.trino.plugin.raptor.legacy.storage.RaptorStorageManager.xxhash64;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static io.trino.testing.MaterializedResult.materializeSourceDataStream;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static java.lang.String.format;
import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import static org.testng.FileAssert.assertDirectory;
import static org.testng.FileAssert.assertFile;

@Test(singleThreaded = true)
public class TestRaptorStorageManager
{
    private static final ISOChronology UTC_CHRONOLOGY = ISOChronology.getInstanceUTC();
    private static final DateTime EPOCH = new DateTime(0, UTC_CHRONOLOGY);
    private static final String CURRENT_NODE = "node";
    private static final String CONNECTOR_ID = "test";
    private static final long TRANSACTION_ID = 123;
    private static final int DELETION_THREADS = 2;
    private static final Duration SHARD_RECOVERY_TIMEOUT = new Duration(30, TimeUnit.SECONDS);
    private static final int MAX_SHARD_ROWS = 100;
    private static final DataSize MAX_FILE_SIZE = DataSize.of(1, MEGABYTE);
    private static final Duration MISSING_SHARD_DISCOVERY = new Duration(5, TimeUnit.MINUTES);
    private static final OrcReaderOptions READER_OPTIONS = new OrcReaderOptions()
            .withMaxMergeDistance(DataSize.of(1, MEGABYTE))
            .withMaxBufferSize(DataSize.of(1, MEGABYTE))
            .withStreamBufferSize(DataSize.of(1, MEGABYTE))
            .withTinyStripeThreshold(DataSize.of(1, MEGABYTE));

    private final NodeManager nodeManager = new TestingNodeManager();
    private Handle dummyHandle;
    private Path temporary;
    private StorageService storageService;
    private ShardRecoveryManager recoveryManager;
    private FileBackupStore fileBackupStore;
    private Optional<BackupStore> backupStore;
    private InMemoryShardRecorder shardRecorder;

    @BeforeMethod
    public void setup()
            throws IOException
    {
        temporary = createTempDirectory(null);
        File directory = temporary.resolve("data").toFile();
        storageService = new FileStorageService(directory);
        storageService.start();

        File backupDirectory = temporary.resolve("backup").toFile();
        fileBackupStore = new FileBackupStore(backupDirectory);
        fileBackupStore.start();
        backupStore = Optional.of(fileBackupStore);

        Jdbi dbi = createTestingJdbi();
        dummyHandle = dbi.open();
        createTablesWithRetry(dbi);

        ShardManager shardManager = createShardManager(dbi);
        Duration discoveryInterval = new Duration(5, TimeUnit.MINUTES);
        recoveryManager = new ShardRecoveryManager(storageService, backupStore, nodeManager, shardManager, discoveryInterval, 10);

        shardRecorder = new InMemoryShardRecorder();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        if (dummyHandle != null) {
            dummyHandle.close();
            dummyHandle = null;
        }
        deleteRecursively(temporary, ALLOW_INSECURE);
    }

    @Test
    public void testWriter()
            throws Exception
    {
        RaptorStorageManager manager = createRaptorStorageManager();

        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(10));

        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello")
                .row(456L, "bye")
                .build();
        sink.appendPages(pages);

        // shard is not recorded until flush
        assertEquals(shardRecorder.getShards().size(), 0);

        sink.flush();

        // shard is recorded after flush
        List<RecordedShard> recordedShards = shardRecorder.getShards();
        assertEquals(recordedShards.size(), 1);

        List<ShardInfo> shards = getFutureValue(sink.commit());

        assertEquals(shards.size(), 1);
        ShardInfo shardInfo = Iterables.getOnlyElement(shards);

        UUID shardUuid = shardInfo.getShardUuid();
        File file = storageService.getStorageFile(shardUuid);
        File backupFile = fileBackupStore.getBackupFile(shardUuid);

        assertEquals(recordedShards.get(0).getTransactionId(), TRANSACTION_ID);
        assertEquals(recordedShards.get(0).getShardUuid(), shardUuid);

        assertEquals(shardInfo.getRowCount(), 2);
        assertEquals(shardInfo.getCompressedSize(), file.length());
        assertEquals(shardInfo.getXxhash64(), xxhash64(file));

        // verify primary and backup shard exist
        assertFile(file, "primary shard");
        assertFile(backupFile, "backup shard");

        assertFileEquals(file, backupFile);

        // remove primary shard to force recovery from backup
        assertTrue(file.delete());
        assertTrue(file.getParentFile().delete());
        assertFalse(file.exists());

        recoveryManager.restoreFromBackup(shardUuid, shardInfo.getCompressedSize(), OptionalLong.of(shardInfo.getXxhash64()));

        try (OrcDataSource dataSource = manager.openShard(shardUuid, READER_OPTIONS)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            Page page = reader.nextPage();
            assertEquals(page.getPositionCount(), 2);

            Block column0 = page.getBlock(0);
            assertEquals(column0.isNull(0), false);
            assertEquals(column0.isNull(1), false);
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 456L);

            Block column1 = page.getBlock(1);
            assertEquals(createVarcharType(10).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(10).getSlice(column1, 1), utf8Slice("bye"));

            assertNull(reader.nextPage());
        }
    }

    @Test
    public void testReader()
            throws Exception
    {
        RaptorStorageManager manager = createRaptorStorageManager();

        List<Long> columnIds = ImmutableList.of(2L, 4L, 6L, 7L, 8L, 9L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(10), VARBINARY, DATE, BOOLEAN, DOUBLE);

        byte[] bytes1 = octets(0x00, 0xFE, 0xFF);
        byte[] bytes3 = octets(0x01, 0x02, 0x19, 0x80);

        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);

        Object[][] doubles = {
                {881L, "-inf", null, null, null, Double.NEGATIVE_INFINITY},
                {882L, "+inf", null, null, null, Double.POSITIVE_INFINITY},
                {883L, "nan", null, null, null, Double.NaN},
                {884L, "min", null, null, null, Double.MIN_VALUE},
                {885L, "max", null, null, null, Double.MAX_VALUE},
                {886L, "pzero", null, null, null, 0.0},
                {887L, "nzero", null, null, null, -0.0},
        };

        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello", wrappedBuffer(bytes1), sqlDate(2001, 8, 22).getDays(), true, 123.45)
                .row(null, null, null, null, null, null)
                .row(456L, "bye", wrappedBuffer(bytes3), sqlDate(2005, 4, 22).getDays(), false, 987.65)
                .rows(doubles)
                .build();

        sink.appendPages(pages);
        List<ShardInfo> shards = getFutureValue(sink.commit());

        assertEquals(shards.size(), 1);
        UUID uuid = Iterables.getOnlyElement(shards).getShardUuid();

        MaterializedResult expected = resultBuilder(SESSION, columnTypes)
                .row(123L, "hello", sqlBinary(bytes1), sqlDate(2001, 8, 22), true, 123.45)
                .row(null, null, null, null, null, null)
                .row(456L, "bye", sqlBinary(bytes3), sqlDate(2005, 4, 22), false, 987.65)
                .rows(doubles)
                .build();

        // no tuple domain (all)
        TupleDomain<RaptorColumnHandle> tupleDomain = TupleDomain.all();

        try (ConnectorPageSource pageSource = getPageSource(manager, columnIds, columnTypes, uuid, tupleDomain)) {
            MaterializedResult result = materializeSourceDataStream(SESSION, pageSource, columnTypes);
            assertEquals(result.getRowCount(), expected.getRowCount());
            assertThat(result).containsExactlyElementsOf(expected);
        }

        // tuple domain within the column range
        tupleDomain = TupleDomain.fromFixedValues(ImmutableMap.of(new RaptorColumnHandle("c1", 2, BIGINT), NullableValue.of(BIGINT, 124L)));

        try (ConnectorPageSource pageSource = getPageSource(manager, columnIds, columnTypes, uuid, tupleDomain)) {
            MaterializedResult result = materializeSourceDataStream(SESSION, pageSource, columnTypes);
            assertEquals(result.getRowCount(), expected.getRowCount());
        }

        // tuple domain outside the column range
        tupleDomain = TupleDomain.fromFixedValues(ImmutableMap.of(new RaptorColumnHandle("c1", 2, BIGINT), NullableValue.of(BIGINT, 122L)));

        try (ConnectorPageSource pageSource = getPageSource(manager, columnIds, columnTypes, uuid, tupleDomain)) {
            MaterializedResult result = materializeSourceDataStream(SESSION, pageSource, columnTypes);
            assertEquals(result.getRowCount(), 0);
        }
    }

    @Test
    public void testRewriter()
            throws Exception
    {
        RaptorStorageManager manager = createRaptorStorageManager();

        long transactionId = TRANSACTION_ID;
        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(10));

        // create file with 2 rows
        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello")
                .row(456L, "bye")
                .build();
        sink.appendPages(pages);
        List<ShardInfo> shards = getFutureValue(sink.commit());

        assertEquals(shardRecorder.getShards().size(), 1);

        // delete one row
        BitSet rowsToDelete = new BitSet();
        rowsToDelete.set(0);
        Collection<Slice> fragments = manager.rewriteShard(transactionId, OptionalInt.empty(), shards.get(0).getShardUuid(), rowsToDelete);

        Slice shardDelta = Iterables.getOnlyElement(fragments);
        ShardDelta shardDeltas = jsonCodec(ShardDelta.class).fromJson(shardDelta.getBytes());
        ShardInfo shardInfo = Iterables.getOnlyElement(shardDeltas.getNewShards());

        // check that output file has one row
        assertEquals(shardInfo.getRowCount(), 1);

        // check that storage file is same as backup file
        File storageFile = storageService.getStorageFile(shardInfo.getShardUuid());
        File backupFile = fileBackupStore.getBackupFile(shardInfo.getShardUuid());
        assertFileEquals(storageFile, backupFile);

        // verify recorded shard
        List<RecordedShard> recordedShards = shardRecorder.getShards();
        assertEquals(recordedShards.size(), 2);
        assertEquals(recordedShards.get(1).getTransactionId(), TRANSACTION_ID);
        assertEquals(recordedShards.get(1).getShardUuid(), shardInfo.getShardUuid());
    }

    @Test
    public void testWriterRollback()
    {
        // verify staging directory is empty
        File staging = temporary.resolve("data").resolve("staging").toFile();
        assertDirectory(staging);
        assertEquals(staging.list(), new String[] {});

        // create a shard in staging
        RaptorStorageManager manager = createRaptorStorageManager();

        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(10));

        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello")
                .row(456L, "bye")
                .build();
        sink.appendPages(pages);

        sink.flush();

        // verify shard exists in staging
        String[] files = staging.list();
        assertNotNull(files);
        String stagingFile = Arrays.stream(files)
                .filter(file -> file.endsWith(".orc"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("file not found in staging"));

        // rollback should cleanup staging files
        sink.rollback();

        files = staging.list();
        assertNotNull(files);
        assertTrue(Arrays.stream(files).noneMatch(stagingFile::equals));
    }

    @Test
    public void testShardStatsBigint()
    {
        List<ColumnStats> stats = columnStats(types(BIGINT),
                row(2L),
                row(-3L),
                row(5L));
        assertColumnStats(stats, 1, -3L, 5L);
    }

    @Test
    public void testShardStatsDouble()
    {
        List<ColumnStats> stats = columnStats(types(DOUBLE),
                row(2.5),
                row(-4.1),
                row(6.6));
        assertColumnStats(stats, 1, -4.1, 6.6);
    }

    @Test
    public void testShardStatsBigintDouble()
    {
        List<ColumnStats> stats = columnStats(types(BIGINT, DOUBLE),
                row(-3L, 6.6),
                row(5L, -4.1));
        assertColumnStats(stats, 1, -3L, 5L);
        assertColumnStats(stats, 2, -4.1, 6.6);
    }

    @Test
    public void testShardStatsDoubleMinMax()
    {
        List<ColumnStats> stats = columnStats(types(DOUBLE),
                row(3.2),
                row(Double.MIN_VALUE),
                row(4.5));
        assertColumnStats(stats, 1, Double.MIN_VALUE, 4.5);

        stats = columnStats(types(DOUBLE),
                row(3.2),
                row(Double.MAX_VALUE),
                row(4.5));
        assertColumnStats(stats, 1, 3.2, Double.MAX_VALUE);
    }

    @Test
    public void testShardStatsDoubleNotFinite()
    {
        List<ColumnStats> stats = columnStats(types(DOUBLE),
                row(3.2),
                row(Double.NEGATIVE_INFINITY),
                row(4.5));
        assertColumnStats(stats, 1, null, 4.5);

        stats = columnStats(types(DOUBLE),
                row(3.2),
                row(Double.POSITIVE_INFINITY),
                row(4.5));
        assertColumnStats(stats, 1, 3.2, null);

        stats = columnStats(types(DOUBLE),
                row(3.2),
                row(Double.NaN),
                row(4.5));
        assertColumnStats(stats, 1, 3.2, 4.5);
    }

    @Test
    public void testShardStatsVarchar()
    {
        List<ColumnStats> stats = columnStats(
                types(createVarcharType(10)),
                row(utf8Slice("hello")),
                row(utf8Slice("bye")),
                row(utf8Slice("foo")));
        assertColumnStats(stats, 1, "bye", "hello");
    }

    @Test
    public void testShardStatsBigintVarbinary()
    {
        List<ColumnStats> stats = columnStats(types(BIGINT, VARBINARY),
                row(5L, wrappedBuffer(octets(0x00))),
                row(3L, wrappedBuffer(octets(0x01))));
        assertColumnStats(stats, 1, 3L, 5L);
        assertNoColumnStats(stats, 2);
    }

    @Test
    public void testShardStatsDateTimestamp()
    {
        long minDate = sqlDate(2001, 8, 22).getDays();
        long maxDate = sqlDate(2005, 4, 22).getDays();
        long maxTimestamp = sqlTimestamp(2002, 4, 13, 6, 7, 8).getMillis();
        long minTimestamp = sqlTimestamp(2001, 3, 15, 9, 10, 11).getMillis();

        List<ColumnStats> stats = columnStats(types(DATE, TIMESTAMP_MILLIS),
                row(minDate, maxTimestamp),
                row(maxDate, minTimestamp));
        assertColumnStats(stats, 1, minDate, maxDate);
        assertColumnStats(stats, 2, minTimestamp, maxTimestamp);
    }

    @Test
    public void testMaxShardRows()
    {
        RaptorStorageManager manager = createRaptorStorageManager(2, DataSize.of(2, MEGABYTE));

        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(10));

        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello")
                .row(456L, "bye")
                .build();
        sink.appendPages(pages);
        assertTrue(sink.isFull());
    }

    @Test
    public void testMaxFileSize()
    {
        List<Long> columnIds = ImmutableList.of(3L, 7L);
        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(5));

        List<Page> pages = rowPagesBuilder(columnTypes)
                .row(123L, "hello")
                .row(456L, "bye")
                .build();

        // Set maxFileSize to 1 byte, so adding any page makes the StoragePageSink full
        RaptorStorageManager manager = createRaptorStorageManager(20, DataSize.ofBytes(1));
        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        sink.appendPages(pages);
        assertTrue(sink.isFull());
    }

    private static ConnectorPageSource getPageSource(
            RaptorStorageManager manager,
            List<Long> columnIds,
            List<Type> columnTypes,
            UUID uuid,
            TupleDomain<RaptorColumnHandle> tupleDomain)
    {
        return manager.getPageSource(uuid, OptionalInt.empty(), columnIds, columnTypes, tupleDomain, READER_OPTIONS);
    }

    private static StoragePageSink createStoragePageSink(StorageManager manager, List<Long> columnIds, List<Type> columnTypes)
    {
        long transactionId = TRANSACTION_ID;
        return manager.createStoragePageSink(transactionId, OptionalInt.empty(), columnIds, columnTypes, false);
    }

    private RaptorStorageManager createRaptorStorageManager()
    {
        return createRaptorStorageManager(MAX_SHARD_ROWS, MAX_FILE_SIZE);
    }

    private RaptorStorageManager createRaptorStorageManager(int maxShardRows, DataSize maxFileSize)
    {
        return createRaptorStorageManager(storageService, backupStore, recoveryManager, shardRecorder, maxShardRows, maxFileSize);
    }

    public static RaptorStorageManager createRaptorStorageManager(Jdbi dbi, File temporary)
    {
        return createRaptorStorageManager(dbi, temporary, MAX_SHARD_ROWS);
    }

    public static RaptorStorageManager createRaptorStorageManager(Jdbi dbi, File temporary, int maxShardRows)
    {
        File directory = new File(temporary, "data");
        StorageService storageService = new FileStorageService(directory);
        storageService.start();

        File backupDirectory = new File(temporary, "backup");
        FileBackupStore fileBackupStore = new FileBackupStore(backupDirectory);
        fileBackupStore.start();
        Optional<BackupStore> backupStore = Optional.of(fileBackupStore);

        ShardManager shardManager = createShardManager(dbi);
        ShardRecoveryManager recoveryManager = new ShardRecoveryManager(
                storageService,
                backupStore,
                new TestingNodeManager(),
                shardManager,
                MISSING_SHARD_DISCOVERY,
                10);
        return createRaptorStorageManager(
                storageService,
                backupStore,
                recoveryManager,
                new InMemoryShardRecorder(),
                maxShardRows,
                MAX_FILE_SIZE);
    }

    public static RaptorStorageManager createRaptorStorageManager(
            StorageService storageService,
            Optional<BackupStore> backupStore,
            ShardRecoveryManager recoveryManager,
            ShardRecorder shardRecorder,
            int maxShardRows,
            DataSize maxFileSize)
    {
        return new RaptorStorageManager(
                CURRENT_NODE,
                storageService,
                backupStore,
                READER_OPTIONS,
                new BackupManager(backupStore, storageService, 1),
                recoveryManager,
                shardRecorder,
                TESTING_TYPE_MANAGER,
                CONNECTOR_ID,
                DELETION_THREADS,
                SHARD_RECOVERY_TIMEOUT,
                maxShardRows,
                maxFileSize,
                DataSize.ofBytes(0));
    }

    private static void assertFileEquals(File actual, File expected)
    {
        assertThat(actual).hasSameBinaryContentAs(expected);
    }

    private static void assertColumnStats(List<ColumnStats> list, long columnId, Object min, Object max)
    {
        for (ColumnStats stats : list) {
            if (stats.getColumnId() == columnId) {
                assertEquals(stats.getMin(), min);
                assertEquals(stats.getMax(), max);
                return;
            }
        }
        fail(format("no stats for column: %s: %s", columnId, list));
    }

    private static void assertNoColumnStats(List<ColumnStats> list, long columnId)
    {
        for (ColumnStats stats : list) {
            assertNotEquals(stats.getColumnId(), columnId);
        }
    }

    private static List<Type> types(Type... types)
    {
        return ImmutableList.copyOf(types);
    }

    private static Object[] row(Object... values)
    {
        return values;
    }

    private List<ColumnStats> columnStats(List<Type> columnTypes, Object[]... rows)
    {
        ImmutableList.Builder<Long> list = ImmutableList.builder();
        for (long i = 1; i <= columnTypes.size(); i++) {
            list.add(i);
        }
        List<Long> columnIds = list.build();

        RaptorStorageManager manager = createRaptorStorageManager();
        StoragePageSink sink = createStoragePageSink(manager, columnIds, columnTypes);
        sink.appendPages(rowPagesBuilder(columnTypes).rows(rows).build());
        List<ShardInfo> shards = getFutureValue(sink.commit());

        assertEquals(shards.size(), 1);
        return Iterables.getOnlyElement(shards).getColumnStats();
    }

    private static SqlVarbinary sqlBinary(byte[] bytes)
    {
        return new SqlVarbinary(bytes);
    }

    private static SqlDate sqlDate(int year, int month, int day)
    {
        DateTime date = new DateTime(year, month, day, 0, 0, 0, 0, UTC);
        return new SqlDate(Days.daysBetween(EPOCH, date).getDays());
    }

    private static SqlTimestamp sqlTimestamp(int year, int month, int day, int hour, int minute, int second)
    {
        return sqlTimestampOf(3, year, month, day, hour, minute, second, 0);
    }
}
