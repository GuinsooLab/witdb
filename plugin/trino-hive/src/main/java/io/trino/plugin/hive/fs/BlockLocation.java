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
package io.trino.plugin.hive.fs;

import com.google.common.collect.ImmutableList;
import io.trino.filesystem.FileEntry.Block;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class BlockLocation
{
    private final List<String> hosts;
    private final long offset;
    private final long length;

    public static List<BlockLocation> fromHiveBlockLocations(@Nullable org.apache.hadoop.fs.BlockLocation[] blockLocations)
    {
        if (blockLocations == null) {
            return ImmutableList.of();
        }

        return Arrays.stream(blockLocations)
                .map(BlockLocation::new)
                .collect(toImmutableList());
    }

    public BlockLocation(Block block)
    {
        this.hosts = ImmutableList.copyOf(block.hosts());
        this.offset = block.offset();
        this.length = block.length();
    }

    public BlockLocation(org.apache.hadoop.fs.BlockLocation blockLocation)
    {
        requireNonNull(blockLocation, "blockLocation is null");
        try {
            this.hosts = ImmutableList.copyOf(blockLocation.getHosts());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.offset = blockLocation.getOffset();
        this.length = blockLocation.getLength();
    }

    public List<String> getHosts()
    {
        return hosts;
    }

    public long getOffset()
    {
        return offset;
    }

    public long getLength()
    {
        return length;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BlockLocation that = (BlockLocation) o;
        return offset == that.offset
                && length == that.length
                && hosts.equals(that.hosts);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(hosts, offset, length);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("hosts", hosts)
                .add("offset", offset)
                .add("length", length)
                .toString();
    }
}
