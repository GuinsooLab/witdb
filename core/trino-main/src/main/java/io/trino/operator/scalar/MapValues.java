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
package io.trino.operator.scalar;

import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.function.TypeParameter;
import io.trino.spi.type.Type;

@ScalarFunction("map_values")
@Description("Returns the values of the given map(K,V) as an array")
public final class MapValues
{
    private MapValues() {}

    @TypeParameter("K")
    @TypeParameter("V")
    @SqlType("array(V)")
    public static Block getValues(
            @TypeParameter("V") Type valueType,
            @SqlType("map(K,V)") Block block)
    {
        BlockBuilder blockBuilder = valueType.createBlockBuilder(null, block.getPositionCount() / 2);
        for (int i = 0; i < block.getPositionCount(); i += 2) {
            valueType.appendTo(block, i + 1, blockBuilder);
        }
        return blockBuilder.build();
    }
}
