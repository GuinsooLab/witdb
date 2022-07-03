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

import io.airlift.slice.Slice;
import io.trino.spi.type.Type;

import java.io.Closeable;

public interface RecordCursor
        extends Closeable
{
    /**
     * Gets the number of input bytes processed by this record cursor so far.
     * If size is not available, this method should return zero.
     */
    long getCompletedBytes();

    /**
     * Gets the wall time spent reading data.
     * If read time is not available, this method should return zero.
     *
     * @see ConnectorPageSource#getReadTimeNanos()
     */
    long getReadTimeNanos();

    Type getType(int field);

    boolean advanceNextPosition();

    boolean getBoolean(int field);

    long getLong(int field);

    double getDouble(int field);

    Slice getSlice(int field);

    Object getObject(int field);

    boolean isNull(int field);

    default long getMemoryUsage()
    {
        // TODO: implement this method in subclasses and remove this default implementation
        return 0;
    }

    @Override
    void close();
}
