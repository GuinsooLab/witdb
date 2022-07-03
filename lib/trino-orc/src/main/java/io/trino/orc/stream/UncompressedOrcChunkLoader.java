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
import io.trino.memory.context.AggregatedMemoryContext;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.orc.OrcCorruptionException;
import io.trino.orc.OrcDataSourceId;

import java.io.IOException;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.trino.orc.checkpoint.InputStreamCheckpoint.createInputStreamCheckpoint;
import static io.trino.orc.checkpoint.InputStreamCheckpoint.decodeCompressedBlockOffset;
import static io.trino.orc.checkpoint.InputStreamCheckpoint.decodeDecompressedOffset;
import static java.util.Objects.requireNonNull;

public final class UncompressedOrcChunkLoader
        implements OrcChunkLoader
{
    private final OrcDataReader dataReader;
    private final LocalMemoryContext dataReaderMemoryUsage;

    private long lastCheckpoint;
    private int nextPosition;

    public UncompressedOrcChunkLoader(OrcDataReader dataReader, AggregatedMemoryContext memoryContext)
    {
        this.dataReader = requireNonNull(dataReader, "dataReader is null");
        requireNonNull(memoryContext, "memoryContext is null");
        this.dataReaderMemoryUsage = memoryContext.newLocalMemoryContext(UncompressedOrcChunkLoader.class.getSimpleName());
        dataReaderMemoryUsage.setBytes(dataReader.getRetainedSize());
    }

    @Override
    public OrcDataSourceId getOrcDataSourceId()
    {
        return dataReader.getOrcDataSourceId();
    }

    private int getCurrentCompressedOffset()
    {
        return hasNextChunk() ? 0 : dataReader.getSize();
    }

    @Override
    public boolean hasNextChunk()
    {
        return nextPosition < dataReader.getSize();
    }

    @Override
    public long getLastCheckpoint()
    {
        return lastCheckpoint;
    }

    @Override
    public void seekToCheckpoint(long checkpoint)
            throws OrcCorruptionException
    {
        int compressedOffset = decodeCompressedBlockOffset(checkpoint);
        if (compressedOffset != 0) {
            throw new OrcCorruptionException(dataReader.getOrcDataSourceId(), "Uncompressed stream does not support seeking to a compressed offset");
        }

        int decompressedOffset = decodeDecompressedOffset(checkpoint);
        nextPosition = decompressedOffset;
        lastCheckpoint = checkpoint;
    }

    @Override
    public Slice nextChunk()
            throws IOException
    {
        if (nextPosition >= dataReader.getSize()) {
            throw new OrcCorruptionException(dataReader.getOrcDataSourceId(), "Read past end of stream");
        }

        Slice chunk = dataReader.seekBuffer(nextPosition);
        dataReaderMemoryUsage.setBytes(dataReader.getRetainedSize());
        lastCheckpoint = createInputStreamCheckpoint(0, nextPosition);
        nextPosition += chunk.length();

        return chunk;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("loader", dataReader)
                .add("compressedOffset", getCurrentCompressedOffset())
                .toString();
    }
}
