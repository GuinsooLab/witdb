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
package io.trino.plugin.hive.util;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.trino.orc.OrcDataSink;
import io.trino.orc.OrcWriteValidation.OrcWriteValidationMode;
import io.trino.orc.OrcWriter;
import io.trino.orc.OrcWriterOptions;
import io.trino.orc.OrcWriterStats;
import io.trino.orc.metadata.OrcType;
import io.trino.spi.Page;
import io.trino.spi.type.Type;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.orc.metadata.CompressionKind.LZ4;

public class TempFileWriter
        implements Closeable
{
    private final OrcWriter orcWriter;

    public TempFileWriter(List<Type> types, OrcDataSink sink)
    {
        this.orcWriter = createOrcFileWriter(sink, types);
    }

    public void writePage(Page page)
    {
        try {
            orcWriter.write(page);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close()
            throws IOException
    {
        orcWriter.close();
    }

    public long getWrittenBytes()
    {
        return orcWriter.getWrittenBytes();
    }

    private static OrcWriter createOrcFileWriter(OrcDataSink sink, List<Type> types)
    {
        List<String> columnNames = IntStream.range(0, types.size())
                .mapToObj(String::valueOf)
                .collect(toImmutableList());

        return new OrcWriter(
                sink,
                columnNames,
                types,
                OrcType.createRootOrcType(columnNames, types),
                LZ4,
                new OrcWriterOptions()
                        .withMaxStringStatisticsLimit(DataSize.ofBytes(0))
                        .withStripeMinSize(DataSize.of(64, MEGABYTE))
                        .withDictionaryMaxMemory(DataSize.of(1, MEGABYTE)),
                ImmutableMap.of(),
                false,
                OrcWriteValidationMode.BOTH,
                new OrcWriterStats());
    }
}
