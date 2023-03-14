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
package io.trino.plugin.hive;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.DataSize;
import io.airlift.units.MaxDataSize;
import io.airlift.units.MinDataSize;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import static io.airlift.units.DataSize.Unit.MEGABYTE;

public class SortingFileWriterConfig
{
    private DataSize writerSortBufferSize = DataSize.of(64, MEGABYTE);
    private int maxOpenSortFiles = 50;

    @MinDataSize("1MB")
    @MaxDataSize("1GB")
    public DataSize getWriterSortBufferSize()
    {
        return writerSortBufferSize;
    }

    @Config("writer-sort-buffer-size")
    public SortingFileWriterConfig setWriterSortBufferSize(DataSize writerSortBufferSize)
    {
        this.writerSortBufferSize = writerSortBufferSize;
        return this;
    }

    @Min(2)
    @Max(1000)
    public int getMaxOpenSortFiles()
    {
        return maxOpenSortFiles;
    }

    @Config("max-open-sort-files")
    @ConfigDescription("Maximum number of writer temporary files to read in one pass")
    public SortingFileWriterConfig setMaxOpenSortFiles(int maxOpenSortFiles)
    {
        this.maxOpenSortFiles = maxOpenSortFiles;
        return this;
    }
}
