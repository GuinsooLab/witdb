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
package io.trino.type;

import com.google.common.collect.ImmutableMap;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.Type;

import java.util.Map;

import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.util.StructuralTestUtil.mapBlockOf;
import static io.trino.util.StructuralTestUtil.mapType;

public class TestTinyintVarcharMapType
        extends AbstractTestType
{
    public TestTinyintVarcharMapType()
    {
        super(mapType(TINYINT, VARCHAR), Map.class, createTestBlock(mapType(TINYINT, VARCHAR)));
    }

    public static Block createTestBlock(Type mapType)
    {
        BlockBuilder blockBuilder = mapType.createBlockBuilder(null, 2);
        mapType.writeObject(blockBuilder, mapBlockOf(TINYINT, VARCHAR, ImmutableMap.of(1, "hi")));
        mapType.writeObject(blockBuilder, mapBlockOf(TINYINT, VARCHAR, ImmutableMap.of(1, "2", 2, "hello")));
        return blockBuilder.build();
    }

    @Override
    protected Object getGreaterValue(Object value)
    {
        throw new UnsupportedOperationException();
    }
}
