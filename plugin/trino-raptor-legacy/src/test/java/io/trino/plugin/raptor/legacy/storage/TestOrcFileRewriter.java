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
import io.airlift.json.JsonCodec;
import io.trino.orc.OrcDataSource;
import io.trino.orc.OrcRecordReader;
import io.trino.plugin.raptor.legacy.storage.OrcFileRewriter.OrcFileInfo;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeId;
import io.trino.spi.type.TypeSignatureParameter;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.plugin.raptor.legacy.storage.OrcTestingUtil.createReader;
import static io.trino.plugin.raptor.legacy.storage.OrcTestingUtil.fileOrcDataSource;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.StructuralTestUtil.arrayBlockOf;
import static io.trino.testing.StructuralTestUtil.arrayBlocksEqual;
import static io.trino.testing.StructuralTestUtil.mapBlockOf;
import static io.trino.testing.StructuralTestUtil.mapBlocksEqual;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.readAllBytes;
import static java.util.UUID.randomUUID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestOrcFileRewriter
{
    private static final JsonCodec<OrcFileMetadata> METADATA_CODEC = jsonCodec(OrcFileMetadata.class);

    private Path temporary;

    @BeforeClass
    public void setup()
            throws IOException
    {
        temporary = createTempDirectory(null);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        deleteRecursively(temporary, ALLOW_INSECURE);
    }

    @Test
    public void testRewrite()
            throws Exception
    {
        ArrayType arrayType = new ArrayType(BIGINT);
        ArrayType arrayOfArrayType = new ArrayType(arrayType);
        Type mapType = TESTING_TYPE_MANAGER.getParameterizedType(StandardTypes.MAP, ImmutableList.of(
                TypeSignatureParameter.typeParameter(createVarcharType(5).getTypeSignature()),
                TypeSignatureParameter.typeParameter(BOOLEAN.getTypeSignature())));
        List<Long> columnIds = ImmutableList.of(3L, 7L, 9L, 10L, 11L, 12L);
        DecimalType decimalType = DecimalType.createDecimalType(4, 4);

        List<Type> columnTypes = ImmutableList.of(BIGINT, createVarcharType(20), arrayType, mapType, arrayOfArrayType, decimalType);

        File file = temporary.resolve(randomUUID().toString()).toFile();
        try (OrcFileWriter writer = new OrcFileWriter(TESTING_TYPE_MANAGER, columnIds, columnTypes, file)) {
            List<Page> pages = rowPagesBuilder(columnTypes)
                    .row(123L, "hello", arrayBlockOf(BIGINT, 1, 2), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5)), new BigDecimal("2.3"))
                    .row(777L, "sky", arrayBlockOf(BIGINT, 3, 4), mapBlockOf(createVarcharType(5), BOOLEAN, "k2", false), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 6)), new BigDecimal("2.3"))
                    .row(456L, "bye", arrayBlockOf(BIGINT, 5, 6), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7)), new BigDecimal("2.3"))
                    .row(888L, "world", arrayBlockOf(BIGINT, 7, 8), mapBlockOf(createVarcharType(5), BOOLEAN, "k4", true), arrayBlockOf(arrayType, null, arrayBlockOf(BIGINT, 8), null), new BigDecimal("2.3"))
                    .row(999L, "done", arrayBlockOf(BIGINT, 9, 10), mapBlockOf(createVarcharType(5), BOOLEAN, "k5", true), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 9, 10)), new BigDecimal("2.3"))
                    .build();
            writer.appendPages(pages);
        }

        try (OrcDataSource dataSource = fileOrcDataSource(file)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 5);
            assertEquals(reader.getFileRowCount(), 5);
            assertEquals(reader.getSplitLength(), file.length());

            Page page = reader.nextPage();
            assertEquals(page.getPositionCount(), 5);

            Block column0 = page.getBlock(0);
            assertEquals(column0.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column0.isNull(i), false);
            }
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 777L);
            assertEquals(BIGINT.getLong(column0, 2), 456L);
            assertEquals(BIGINT.getLong(column0, 3), 888L);
            assertEquals(BIGINT.getLong(column0, 4), 999L);

            Block column1 = page.getBlock(1);
            assertEquals(column1.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column1.isNull(i), false);
            }
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(20).getSlice(column1, 1), utf8Slice("sky"));
            assertEquals(createVarcharType(20).getSlice(column1, 2), utf8Slice("bye"));
            assertEquals(createVarcharType(20).getSlice(column1, 3), utf8Slice("world"));
            assertEquals(createVarcharType(20).getSlice(column1, 4), utf8Slice("done"));

            Block column2 = page.getBlock(2);
            assertEquals(column2.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column2.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 0), arrayBlockOf(BIGINT, 1, 2)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 1), arrayBlockOf(BIGINT, 3, 4)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 2), arrayBlockOf(BIGINT, 5, 6)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 3), arrayBlockOf(BIGINT, 7, 8)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 4), arrayBlockOf(BIGINT, 9, 10)));

            Block column3 = page.getBlock(3);
            assertEquals(column3.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column3.isNull(i), false);
            }
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 0), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 1), mapBlockOf(createVarcharType(5), BOOLEAN, "k2", false)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 2), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 3), mapBlockOf(createVarcharType(5), BOOLEAN, "k4", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 4), mapBlockOf(createVarcharType(5), BOOLEAN, "k5", true)));

            Block column4 = page.getBlock(4);
            assertEquals(column4.getPositionCount(), 5);
            for (int i = 0; i < 5; i++) {
                assertEquals(column4.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 0), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 1), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 6))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 2), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 3), arrayBlockOf(arrayType, null, arrayBlockOf(BIGINT, 8), null)));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 4), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 9, 10))));

            assertNull(reader.nextPage());

            OrcFileMetadata orcFileMetadata = METADATA_CODEC.fromJson(reader.getUserMetadata().get(OrcFileMetadata.KEY).getBytes());
            assertEquals(orcFileMetadata, new OrcFileMetadata(ImmutableMap.<Long, TypeId>builder()
                    .put(3L, BIGINT.getTypeId())
                    .put(7L, createVarcharType(20).getTypeId())
                    .put(9L, arrayType.getTypeId())
                    .put(10L, mapType.getTypeId())
                    .put(11L, arrayOfArrayType.getTypeId())
                    .put(12L, decimalType.getTypeId())
                    .buildOrThrow()));
        }

        BitSet rowsToDelete = new BitSet(5);
        rowsToDelete.set(1);
        rowsToDelete.set(3);
        rowsToDelete.set(4);

        File newFile = temporary.resolve(randomUUID().toString()).toFile();
        OrcFileInfo info = OrcFileRewriter.rewrite(TESTING_TYPE_MANAGER, file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 2);
        assertEquals(info.getUncompressedSize(), 182);

        try (OrcDataSource dataSource = fileOrcDataSource(newFile)) {
            OrcRecordReader reader = createReader(dataSource, columnIds, columnTypes);

            assertEquals(reader.getReaderRowCount(), 2);
            assertEquals(reader.getFileRowCount(), 2);
            assertEquals(reader.getSplitLength(), newFile.length());

            Page page = reader.nextPage();
            assertEquals(page.getPositionCount(), 2);

            Block column0 = page.getBlock(0);
            assertEquals(column0.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column0.isNull(i), false);
            }
            assertEquals(BIGINT.getLong(column0, 0), 123L);
            assertEquals(BIGINT.getLong(column0, 1), 456L);

            Block column1 = page.getBlock(1);
            assertEquals(column1.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column1.isNull(i), false);
            }
            assertEquals(createVarcharType(20).getSlice(column1, 0), utf8Slice("hello"));
            assertEquals(createVarcharType(20).getSlice(column1, 1), utf8Slice("bye"));

            Block column2 = page.getBlock(2);
            assertEquals(column2.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column2.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 0), arrayBlockOf(BIGINT, 1, 2)));
            assertTrue(arrayBlocksEqual(BIGINT, arrayType.getObject(column2, 1), arrayBlockOf(BIGINT, 5, 6)));

            Block column3 = page.getBlock(3);
            assertEquals(column3.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column3.isNull(i), false);
            }
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 0), mapBlockOf(createVarcharType(5), BOOLEAN, "k1", true)));
            assertTrue(mapBlocksEqual(createVarcharType(5), BOOLEAN, arrayType.getObject(column3, 1), mapBlockOf(createVarcharType(5), BOOLEAN, "k3", true)));

            Block column4 = page.getBlock(4);
            assertEquals(column4.getPositionCount(), 2);
            for (int i = 0; i < 2; i++) {
                assertEquals(column4.isNull(i), false);
            }
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 0), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 5))));
            assertTrue(arrayBlocksEqual(arrayType, arrayOfArrayType.getObject(column4, 1), arrayBlockOf(arrayType, arrayBlockOf(BIGINT, 7))));

            assertEquals(reader.nextPage(), null);

            OrcFileMetadata orcFileMetadata = METADATA_CODEC.fromJson(reader.getUserMetadata().get(OrcFileMetadata.KEY).getBytes());
            assertEquals(orcFileMetadata, new OrcFileMetadata(ImmutableMap.<Long, TypeId>builder()
                    .put(3L, BIGINT.getTypeId())
                    .put(7L, createVarcharType(20).getTypeId())
                    .put(9L, arrayType.getTypeId())
                    .put(10L, mapType.getTypeId())
                    .put(11L, arrayOfArrayType.getTypeId())
                    .put(12L, decimalType.getTypeId())
                    .buildOrThrow()));
        }
    }

    @Test
    public void testRewriteAllRowsDeleted()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(3L);
        List<Type> columnTypes = ImmutableList.of(BIGINT);

        File file = temporary.resolve(randomUUID().toString()).toFile();
        try (OrcFileWriter writer = new OrcFileWriter(TESTING_TYPE_MANAGER, columnIds, columnTypes, file)) {
            writer.appendPages(rowPagesBuilder(columnTypes).row(123L).row(456L).build());
        }

        BitSet rowsToDelete = new BitSet();
        rowsToDelete.set(0);
        rowsToDelete.set(1);

        File newFile = temporary.resolve(randomUUID().toString()).toFile();
        OrcFileInfo info = OrcFileRewriter.rewrite(TESTING_TYPE_MANAGER, file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 0);
        assertEquals(info.getUncompressedSize(), 0);

        assertFalse(newFile.exists());
    }

    @Test
    public void testRewriteNoRowsDeleted()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(3L);
        List<Type> columnTypes = ImmutableList.of(BIGINT);

        File file = temporary.resolve(randomUUID().toString()).toFile();
        try (OrcFileWriter writer = new OrcFileWriter(TESTING_TYPE_MANAGER, columnIds, columnTypes, file)) {
            writer.appendPages(rowPagesBuilder(columnTypes).row(123L).row(456L).build());
        }

        BitSet rowsToDelete = new BitSet();

        File newFile = temporary.resolve(randomUUID().toString()).toFile();
        OrcFileInfo info = OrcFileRewriter.rewrite(TESTING_TYPE_MANAGER, file, newFile, rowsToDelete);
        assertEquals(info.getRowCount(), 2);
        assertEquals(info.getUncompressedSize(), 18);

        assertEquals(readAllBytes(newFile.toPath()), readAllBytes(file.toPath()));
    }

    @Test
    public void testUncompressedSize()
            throws Exception
    {
        List<Long> columnIds = ImmutableList.of(1L, 2L, 3L, 4L, 5L);
        List<Type> columnTypes = ImmutableList.of(BOOLEAN, BIGINT, DOUBLE, createVarcharType(10), VARBINARY);

        File file = temporary.resolve(randomUUID().toString()).toFile();
        try (OrcFileWriter writer = new OrcFileWriter(TESTING_TYPE_MANAGER, columnIds, columnTypes, file)) {
            List<Page> pages = rowPagesBuilder(columnTypes)
                    .row(true, 123L, 98.7, "hello", utf8Slice("abc"))
                    .row(false, 456L, 65.4, "world", utf8Slice("xyz"))
                    .row(null, null, null, null, null)
                    .build();
            writer.appendPages(pages);
        }

        File newFile = temporary.resolve(randomUUID().toString()).toFile();
        OrcFileInfo info = OrcFileRewriter.rewrite(TESTING_TYPE_MANAGER, file, newFile, new BitSet());
        assertEquals(info.getRowCount(), 3);
        assertEquals(info.getUncompressedSize(), 106);
    }
}
