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
package io.trino.operator.aggregation.state;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.type.Type;

import static io.trino.spi.type.VarbinaryType.VARBINARY;

public class LongDecimalWithOverflowAndLongStateSerializer
        implements AccumulatorStateSerializer<LongDecimalWithOverflowAndLongState>
{
    @Override
    public Type getSerializedType()
    {
        return VARBINARY;
    }

    @Override
    public void serialize(LongDecimalWithOverflowAndLongState state, BlockBuilder out)
    {
        if (state.isNotNull()) {
            long count = state.getLong();
            long overflow = state.getOverflow();
            long[] decimal = state.getDecimalArray();
            int offset = state.getDecimalArrayOffset();
            long[] buffer = new long[4];
            long high = decimal[offset];
            long low = decimal[offset + 1];

            buffer[0] = low;
            buffer[1] = high;
            // if high = 0, the count will overwrite it
            int countOffset = 1 + (high == 0 ? 0 : 1);
            // append count, overflow
            buffer[countOffset] = count;
            buffer[countOffset + 1] = overflow;

            // cases
            // high == 0 (countOffset = 1)
            //    overflow == 0 & count == 1  -> bufferLength = 1
            //    overflow != 0 || count != 1 -> bufferLength = 3
            // high != 0 (countOffset = 2)
            //    overflow == 0 & count == 1  -> bufferLength = 2
            //    overflow != 0 || count != 1 -> bufferLength = 4
            int bufferLength = countOffset + ((overflow == 0 & count == 1) ? 0 : 2);
            VARBINARY.writeSlice(out, Slices.wrappedLongArray(buffer, 0, bufferLength));
        }
        else {
            out.appendNull();
        }
    }

    @Override
    public void deserialize(Block block, int index, LongDecimalWithOverflowAndLongState state)
    {
        if (!block.isNull(index)) {
            Slice slice = VARBINARY.getSlice(block, index);
            long[] decimal = state.getDecimalArray();
            int offset = state.getDecimalArrayOffset();

            int sliceLength = slice.length();
            long low = slice.getLong(0);
            long high = 0;
            long overflow = 0;
            long count = 1;

            switch (sliceLength) {
                case 4 * Long.BYTES:
                    overflow = slice.getLong(Long.BYTES * 3);
                    count = slice.getLong(Long.BYTES * 2);
                    // fall through
                case 2 * Long.BYTES:
                    high = slice.getLong(Long.BYTES);
                    break;
                case 3 * Long.BYTES:
                    overflow = slice.getLong(Long.BYTES * 2);
                    count = slice.getLong(Long.BYTES);
            }

            decimal[offset + 1] = low;
            decimal[offset] = high;
            state.setOverflow(overflow);
            state.setLong(count);
            state.setNotNull();
        }
    }
}
