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
import io.trino.spi.type.MapType;
import io.trino.spi.type.Type;

@Description("Creates an empty map")
@ScalarFunction("map")
public final class EmptyMapConstructor
{
    private final Block emptyMap;

    public EmptyMapConstructor(@TypeParameter("map(unknown,unknown)") Type mapType)
    {
        BlockBuilder mapBlockBuilder = mapType.createBlockBuilder(null, 1);
        mapBlockBuilder.beginBlockEntry();
        mapBlockBuilder.closeEntry();
        emptyMap = ((MapType) mapType).getObject(mapBlockBuilder.build(), 0);
    }

    @SqlType("map(unknown,unknown)")
    public Block map()
    {
        return emptyMap;
    }
}
