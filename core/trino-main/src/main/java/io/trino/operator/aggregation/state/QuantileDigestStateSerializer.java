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

import io.airlift.stats.QuantileDigest;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.type.QuantileDigestType;
import io.trino.spi.type.Type;

import static io.trino.spi.type.BigintType.BIGINT;

public class QuantileDigestStateSerializer
        implements AccumulatorStateSerializer<QuantileDigestState>
{
    private final QuantileDigestType type;

    public QuantileDigestStateSerializer()
    {
        this.type = new QuantileDigestType(BIGINT);
    }

    @Override
    public Type getSerializedType()
    {
        return type;
    }

    @Override
    public void serialize(QuantileDigestState state, BlockBuilder out)
    {
        if (state.getQuantileDigest() == null) {
            out.appendNull();
        }
        else {
            type.writeSlice(out, state.getQuantileDigest().serialize());
        }
    }

    @Override
    public void deserialize(Block block, int index, QuantileDigestState state)
    {
        state.setQuantileDigest(new QuantileDigest(type.getSlice(block, index)));
    }
}
