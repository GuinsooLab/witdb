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
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.function.TypeParameter;
import io.trino.spi.type.StandardTypes;

@Description("Returns the cardinality (length) of the array")
@ScalarFunction("cardinality")
public final class ArrayCardinalityFunction
{
    private ArrayCardinalityFunction() {}

    @TypeParameter("E")
    @SqlType(StandardTypes.BIGINT)
    public static long arrayCardinality(@SqlType("array(E)") Block block)
    {
        return block.getPositionCount();
    }
}
