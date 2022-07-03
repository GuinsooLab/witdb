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
import io.trino.spi.type.Int128;
import io.trino.spi.type.Type;

import static io.trino.spi.type.VarbinaryType.VARBINARY;

public class LongDecimalWithOverflowAndLongStateSerializer
        implements AccumulatorStateSerializer<LongDecimalWithOverflowAndLongState>
{
    private static final int SERIALIZED_SIZE = (Long.BYTES * 2) + Int128.SIZE;

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
            VARBINARY.writeSlice(out, Slices.wrappedLongArray(count, overflow, decimal[offset], decimal[offset + 1]));
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
            if (slice.length() != SERIALIZED_SIZE) {
                throw new IllegalStateException("Unexpected serialized state size: " + slice.length());
            }

            long count = slice.getLong(0);
            long overflow = slice.getLong(Long.BYTES);

            state.setLong(count);
            state.setOverflow(overflow);
            state.setNotNull();
            long[] decimal = state.getDecimalArray();
            int offset = state.getDecimalArrayOffset();
            decimal[offset] = slice.getLong(Long.BYTES * 2);
            decimal[offset + 1] = slice.getLong(Long.BYTES * 3);
        }
    }
}
