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
package io.trino.spi.connector;

import java.util.StringJoiner;

import static java.util.Objects.requireNonNull;

public final class BeginTableExecuteResult<E, T>
{
    /**
     * Updated tableExecuteHandle
     */
    private final E tableExecuteHandle;

    /**
     * Updated sourceHandle
     */
    private final T sourceHandle;

    public BeginTableExecuteResult(E tableExecuteHandle, T sourceHandle)
    {
        this.tableExecuteHandle = requireNonNull(tableExecuteHandle, "tableExecuteHandle is null");
        this.sourceHandle = requireNonNull(sourceHandle, "sourceHandle is null");
    }

    public E getTableExecuteHandle()
    {
        return tableExecuteHandle;
    }

    public T getSourceHandle()
    {
        return sourceHandle;
    }

    @Override
    public String toString()
    {
        return new StringJoiner(", ", BeginTableExecuteResult.class.getSimpleName() + "[", "]")
                .add("tableExecuteHandle=" + tableExecuteHandle)
                .add("sourceHandle=" + sourceHandle)
                .toString();
    }
}
