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
package io.trino.orc.stream;

import io.airlift.slice.Slice;
import io.trino.orc.OrcCorruptionException;
import io.trino.orc.OrcDecompressor;
import io.trino.orc.checkpoint.LongStreamCheckpoint;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.orc.OrcDecompressor.createOrcDecompressor;
import static io.trino.orc.metadata.CompressionKind.SNAPPY;
import static io.trino.orc.metadata.Stream.StreamKind.DATA;

public class TestLongStreamV2
        extends AbstractTestValueStream<Long, LongStreamCheckpoint, LongOutputStreamV2, LongInputStreamV2>
{
    @Test
    public void test()
            throws IOException
    {
        List<List<Long>> groups = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < 3; groupIndex++) {
            List<Long> group = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                group.add((long) (groupIndex * 10_000 + i));
            }
            groups.add(group);
        }
        testWriteValue(groups);
    }

    @Override
    protected LongOutputStreamV2 createValueOutputStream()
    {
        return new LongOutputStreamV2(SNAPPY, COMPRESSION_BLOCK_SIZE, true, DATA);
    }

    @Override
    protected void writeValue(LongOutputStreamV2 outputStream, Long value)
    {
        outputStream.writeLong(value);
    }

    @Override
    protected LongInputStreamV2 createValueStream(Slice slice)
            throws OrcCorruptionException
    {
        Optional<OrcDecompressor> orcDecompressor = createOrcDecompressor(ORC_DATA_SOURCE_ID, SNAPPY, COMPRESSION_BLOCK_SIZE);
        OrcInputStream input = new OrcInputStream(OrcChunkLoader.create(ORC_DATA_SOURCE_ID, slice, orcDecompressor, newSimpleAggregatedMemoryContext()));
        return new LongInputStreamV2(input, true, false);
    }

    @Override
    protected Long readValue(LongInputStreamV2 valueStream)
            throws IOException
    {
        return valueStream.next();
    }
}
