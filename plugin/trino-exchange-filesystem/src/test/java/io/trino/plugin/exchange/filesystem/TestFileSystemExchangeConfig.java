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
package io.trino.plugin.exchange.filesystem;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;

public class TestFileSystemExchangeConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(FileSystemExchangeConfig.class)
                .setBaseDirectories(null)
                .setExchangeEncryptionEnabled(true)
                .setMaxPageStorageSize(DataSize.of(16, MEGABYTE))
                .setExchangeSinkBufferPoolMinSize(10)
                .setExchangeSinkBuffersPerPartition(2)
                .setExchangeSinkMaxFileSize(DataSize.of(1, GIGABYTE))
                .setExchangeSourceConcurrentReaders(4)
                .setMaxOutputPartitionCount(50)
                .setExchangeFileListingParallelism(50));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("exchange.base-directories", "s3n://exchange-spooling-test/")
                .put("exchange.encryption-enabled", "false")
                .put("exchange.max-page-storage-size", "32MB")
                .put("exchange.sink-buffer-pool-min-size", "20")
                .put("exchange.sink-buffers-per-partition", "3")
                .put("exchange.sink-max-file-size", "2GB")
                .put("exchange.source-concurrent-readers", "10")
                .put("exchange.max-output-partition-count", "53")
                .put("exchange.file-listing-parallelism", "20")
                .buildOrThrow();

        FileSystemExchangeConfig expected = new FileSystemExchangeConfig()
                .setBaseDirectories("s3n://exchange-spooling-test/")
                .setExchangeEncryptionEnabled(false)
                .setMaxPageStorageSize(DataSize.of(32, MEGABYTE))
                .setExchangeSinkBufferPoolMinSize(20)
                .setExchangeSinkBuffersPerPartition(3)
                .setExchangeSinkMaxFileSize(DataSize.of(2, GIGABYTE))
                .setExchangeSourceConcurrentReaders(10)
                .setMaxOutputPartitionCount(53)
                .setExchangeFileListingParallelism(20);

        assertFullMapping(properties, expected);
    }
}
