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
package io.trino.orc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.orc.stream.MemoryOrcDataReader;
import io.trino.orc.stream.OrcDataReader;

import java.io.IOException;
import java.util.Map;

import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class CachingOrcDataSource
        implements OrcDataSource
{
    private final OrcDataSource dataSource;
    private final RegionFinder regionFinder;

    private long cachePosition;
    private int cacheLength;
    private Slice cache;

    public CachingOrcDataSource(OrcDataSource dataSource, RegionFinder regionFinder)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
        this.regionFinder = requireNonNull(regionFinder, "regionFinder is null");
        this.cache = Slices.EMPTY_SLICE;
    }

    @Override
    public OrcDataSourceId getId()
    {
        return dataSource.getId();
    }

    @Override
    public long getReadBytes()
    {
        return dataSource.getReadBytes();
    }

    @Override
    public long getReadTimeNanos()
    {
        return dataSource.getReadTimeNanos();
    }

    @Override
    public long getEstimatedSize()
    {
        return dataSource.getEstimatedSize();
    }

    @Override
    public long getRetainedSize()
    {
        // Only return retained memory from delegate data source. The cache in this class
        // is normally reported by an OrcDataReader, and we favor missing memory reporting
        // to double reporting.
        return dataSource.getRetainedSize();
    }

    @VisibleForTesting
    void readCacheAt(long offset)
            throws IOException
    {
        DiskRange newCacheRange = regionFinder.getRangeFor(offset);
        cachePosition = newCacheRange.getOffset();
        cacheLength = newCacheRange.getLength();
        cache = dataSource.readFully(newCacheRange.getOffset(), cacheLength);
    }

    @Override
    public Slice readTail(int length)
    {
        // caching data source is not used for tail reads, and would be complex to implement
        throw new UnsupportedOperationException();
    }

    @Override
    public Slice readFully(long position, int length)
            throws IOException
    {
        if (position < cachePosition) {
            throw new IllegalArgumentException(format("read request (offset %d length %d) is before cache (offset %d length %d)", position, length, cachePosition, cacheLength));
        }
        if (position >= cachePosition + cacheLength) {
            readCacheAt(position);
        }
        if (position + length > cachePosition + cacheLength) {
            throw new IllegalArgumentException(format("read request (offset %d length %d) partially overlaps cache (offset %d length %d)", position, length, cachePosition, cacheLength));
        }
        return cache.slice(toIntExact(position - cachePosition), length);
    }

    @Override
    public <K> Map<K, OrcDataReader> readFully(Map<K, DiskRange> diskRanges)
            throws IOException
    {
        ImmutableMap.Builder<K, OrcDataReader> builder = ImmutableMap.builder();

        // Assumption here: all disk ranges are in the same region. Therefore, serving them in arbitrary order
        // will not result in eviction of cache that otherwise could have served any of the DiskRanges provided.
        for (Map.Entry<K, DiskRange> entry : diskRanges.entrySet()) {
            DiskRange diskRange = entry.getValue();
            Slice buffer = readFully(diskRange.getOffset(), diskRange.getLength());
            builder.put(entry.getKey(), new MemoryOrcDataReader(dataSource.getId(), buffer, buffer.length()));
        }
        return builder.buildOrThrow();
    }

    @Override
    public void close()
            throws IOException
    {
        dataSource.close();
    }

    @Override
    public String toString()
    {
        return dataSource.toString();
    }

    public interface RegionFinder
    {
        DiskRange getRangeFor(long desiredOffset);
    }
}
