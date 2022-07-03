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
package io.trino.plugin.ml;

import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.Type;

public class LearnStateSerializer
        implements AccumulatorStateSerializer<LearnState>
{
    @Override
    public Type getSerializedType()
    {
        return BigintType.BIGINT;
    }

    @Override
    public void serialize(LearnState state, BlockBuilder out)
    {
        throw new UnsupportedOperationException("LEARN must run on a single machine");
    }

    @Override
    public void deserialize(Block block, int index, LearnState state)
    {
        throw new UnsupportedOperationException("LEARN must run on a single machine");
    }
}
