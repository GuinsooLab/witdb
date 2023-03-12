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
package io.trino.plugin.hive.parquet;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.LegacyConfig;
import io.airlift.units.DataSize;
import io.trino.parquet.writer.ParquetWriterOptions;
import org.apache.parquet.hadoop.ParquetWriter;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;

public class ParquetWriterConfig
{
    private boolean parquetOptimizedWriterEnabled;

    private DataSize blockSize = DataSize.ofBytes(ParquetWriter.DEFAULT_BLOCK_SIZE);
    private DataSize pageSize = DataSize.ofBytes(ParquetWriter.DEFAULT_PAGE_SIZE);
    private int batchSize = ParquetWriterOptions.DEFAULT_BATCH_SIZE;
    private double validationPercentage = 5;

    public DataSize getBlockSize()
    {
        return blockSize;
    }

    @Config("parquet.writer.block-size")
    @LegacyConfig("hive.parquet.writer.block-size")
    public ParquetWriterConfig setBlockSize(DataSize blockSize)
    {
        this.blockSize = blockSize;
        return this;
    }

    public DataSize getPageSize()
    {
        return pageSize;
    }

    @Config("parquet.writer.page-size")
    @LegacyConfig("hive.parquet.writer.page-size")
    public ParquetWriterConfig setPageSize(DataSize pageSize)
    {
        this.pageSize = pageSize;
        return this;
    }

    public boolean isParquetOptimizedWriterEnabled()
    {
        return parquetOptimizedWriterEnabled;
    }

    @Config("parquet.optimized-writer.enabled")
    @LegacyConfig({"hive.parquet.optimized-writer.enabled", "parquet.experimental-optimized-writer.enabled"})
    @ConfigDescription("Enable optimized Parquet writer")
    public ParquetWriterConfig setParquetOptimizedWriterEnabled(boolean parquetOptimizedWriterEnabled)
    {
        this.parquetOptimizedWriterEnabled = parquetOptimizedWriterEnabled;
        return this;
    }

    @Config("parquet.writer.batch-size")
    @ConfigDescription("Maximum number of rows passed to the writer in each batch")
    public ParquetWriterConfig setBatchSize(int batchSize)
    {
        this.batchSize = batchSize;
        return this;
    }

    public int getBatchSize()
    {
        return batchSize;
    }

    @DecimalMin("0.0")
    @DecimalMax("100.0")
    public double getValidationPercentage()
    {
        return validationPercentage;
    }

    @Config("parquet.optimized-writer.validation-percentage")
    @ConfigDescription("Percentage of parquet files to validate after write by re-reading the whole file")
    public ParquetWriterConfig setValidationPercentage(double validationPercentage)
    {
        this.validationPercentage = validationPercentage;
        return this;
    }
}
