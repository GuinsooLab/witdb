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

import io.trino.spi.Page;

import java.io.Closeable;
import java.util.Optional;

public interface FileWriter
{
    long getWrittenBytes();

    long getMemoryUsage();

    void appendRows(Page dataPage);

    /**
     * Commits written data. Returns rollback {@link Closeable} which can be used to cleanup on failure.
     */
    Closeable commit();

    void rollback();

    long getValidationCpuNanos();

    default Optional<Runnable> getVerificationTask()
    {
        return Optional.empty();
    }
}
