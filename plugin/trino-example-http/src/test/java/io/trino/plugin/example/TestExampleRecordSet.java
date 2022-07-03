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
package io.trino.plugin.example;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordSet;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class TestExampleRecordSet
{
    private ExampleHttpServer exampleHttpServer;
    private String dataUri;

    @Test
    public void testGetColumnTypes()
    {
        RecordSet recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of(
                new ExampleColumnHandle("text", createUnboundedVarcharType(), 0),
                new ExampleColumnHandle("value", BIGINT, 1)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(createUnboundedVarcharType(), BIGINT));

        recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of(
                new ExampleColumnHandle("value", BIGINT, 1),
                new ExampleColumnHandle("text", createUnboundedVarcharType(), 0)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(BIGINT, createUnboundedVarcharType()));

        recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of(
                new ExampleColumnHandle("value", BIGINT, 1),
                new ExampleColumnHandle("value", BIGINT, 1),
                new ExampleColumnHandle("text", createUnboundedVarcharType(), 0)));
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of(BIGINT, BIGINT, createUnboundedVarcharType()));

        recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of());
        assertEquals(recordSet.getColumnTypes(), ImmutableList.of());
    }

    @Test
    public void testCursorSimple()
    {
        RecordSet recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of(
                new ExampleColumnHandle("text", createUnboundedVarcharType(), 0),
                new ExampleColumnHandle("value", BIGINT, 1)));
        RecordCursor cursor = recordSet.cursor();

        assertEquals(cursor.getType(0), createUnboundedVarcharType());
        assertEquals(cursor.getType(1), BIGINT);

        Map<String, Long> data = new LinkedHashMap<>();
        while (cursor.advanceNextPosition()) {
            data.put(cursor.getSlice(0).toStringUtf8(), cursor.getLong(1));
            assertFalse(cursor.isNull(0));
            assertFalse(cursor.isNull(1));
        }
        assertEquals(data, ImmutableMap.<String, Long>builder()
                .put("ten", 10L)
                .put("eleven", 11L)
                .put("twelve", 12L)
                .buildOrThrow());
    }

    @Test
    public void testCursorMixedOrder()
    {
        RecordSet recordSet = new ExampleRecordSet(new ExampleSplit(dataUri), ImmutableList.of(
                new ExampleColumnHandle("value", BIGINT, 1),
                new ExampleColumnHandle("value", BIGINT, 1),
                new ExampleColumnHandle("text", createUnboundedVarcharType(), 0)));
        RecordCursor cursor = recordSet.cursor();

        Map<String, Long> data = new LinkedHashMap<>();
        while (cursor.advanceNextPosition()) {
            assertEquals(cursor.getLong(0), cursor.getLong(1));
            data.put(cursor.getSlice(2).toStringUtf8(), cursor.getLong(0));
        }
        assertEquals(data, ImmutableMap.<String, Long>builder()
                .put("ten", 10L)
                .put("eleven", 11L)
                .put("twelve", 12L)
                .buildOrThrow());
    }

    //
    // TODO: your code should also have tests for all types that you support and for the state machine of your cursor
    //

    //
    // Start http server for testing
    //

    @BeforeClass
    public void setUp()
    {
        exampleHttpServer = new ExampleHttpServer();
        dataUri = exampleHttpServer.resolve("/example-data/numbers-2.csv").toString();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        if (exampleHttpServer != null) {
            exampleHttpServer.stop();
        }
    }
}
