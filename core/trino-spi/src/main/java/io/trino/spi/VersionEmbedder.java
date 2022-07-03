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
package io.trino.spi;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public interface VersionEmbedder
{
    /**
     * Encodes Trino server version information in the stack
     */
    Runnable embedVersion(Runnable runnable);

    <T> Callable<T> embedVersion(Callable<T> runnable);

    default Executor embedVersion(Executor delegate)
    {
        requireNonNull(delegate, "delegate is null");
        return runnable -> delegate.execute(embedVersion(runnable));
    }
}
