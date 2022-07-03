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
package io.trino.orc.stream;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.orc.checkpoint.FloatStreamCheckpoint;

import java.io.IOException;

import static io.airlift.slice.SizeOf.SIZE_OF_FLOAT;

public class FloatInputStream
        implements ValueInputStream<FloatStreamCheckpoint>
{
    private static final int BUFFER_SIZE = 128;
    private final OrcInputStream input;
    private final byte[] buffer = new byte[SIZE_OF_FLOAT * BUFFER_SIZE];
    private final Slice slice = Slices.wrappedBuffer(buffer);

    public FloatInputStream(OrcInputStream input)
    {
        this.input = input;
    }

    @Override
    public void seekToCheckpoint(FloatStreamCheckpoint checkpoint)
            throws IOException
    {
        input.seekToCheckpoint(checkpoint.getInputStreamCheckpoint());
    }

    @Override
    public void skip(long items)
            throws IOException
    {
        long length = items * SIZE_OF_FLOAT;
        input.skipFully(length);
    }

    public float next()
            throws IOException
    {
        input.readFully(buffer, 0, SIZE_OF_FLOAT);
        return slice.getFloat(0);
    }

    public void next(int[] values, int items)
            throws IOException
    {
        input.readFully(Slices.wrappedIntArray(values), 0, items * SIZE_OF_FLOAT);
    }
}
