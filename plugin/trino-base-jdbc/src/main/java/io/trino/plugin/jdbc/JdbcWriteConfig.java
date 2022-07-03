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
package io.trino.plugin.jdbc;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

public class JdbcWriteConfig
{
    static final int MAX_ALLOWED_WRITE_BATCH_SIZE = 1_000_000;

    private int writeBatchSize = 1000;

    // Do not create temporary table during insert.
    // This means that the write operation can fail and leave the table in an inconsistent state.
    private boolean nonTransactionalInsert;

    @Min(1)
    @Max(MAX_ALLOWED_WRITE_BATCH_SIZE)
    public int getWriteBatchSize()
    {
        return writeBatchSize;
    }

    @Config("write.batch-size")
    @ConfigDescription("Maximum number of rows to write in a single batch")
    public JdbcWriteConfig setWriteBatchSize(int writeBatchSize)
    {
        this.writeBatchSize = writeBatchSize;
        return this;
    }

    public boolean isNonTransactionalInsert()
    {
        return nonTransactionalInsert;
    }

    @Config("insert.non-transactional-insert.enabled")
    @ConfigDescription("Do not create temporary table during insert. " +
            "This means that the write operation can fail and leave the table in an inconsistent state.")
    public JdbcWriteConfig setNonTransactionalInsert(boolean nonTransactionalInsert)
    {
        this.nonTransactionalInsert = nonTransactionalInsert;
        return this;
    }
}
