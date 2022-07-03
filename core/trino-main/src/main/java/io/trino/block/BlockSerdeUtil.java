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
package io.trino.block;

import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import io.airlift.slice.SliceOutput;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockEncodingSerde;
import io.trino.spi.type.Type;

import java.lang.invoke.MethodHandle;

import static io.trino.util.Reflection.methodHandle;

public final class BlockSerdeUtil
{
    public static final MethodHandle READ_BLOCK = methodHandle(BlockSerdeUtil.class, "readBlock", BlockEncodingSerde.class, Slice.class);
    public static final MethodHandle READ_BLOCK_VALUE = methodHandle(BlockSerdeUtil.class, "readBlockValue", BlockEncodingSerde.class, Type.class, Slice.class);

    private BlockSerdeUtil()
    {
    }

    public static Block readBlock(BlockEncodingSerde blockEncodingSerde, Slice slice)
    {
        return readBlock(blockEncodingSerde, slice.getInput());
    }

    public static Block readBlock(BlockEncodingSerde blockEncodingSerde, SliceInput input)
    {
        return blockEncodingSerde.readBlock(input);
    }

    public static Object readBlockValue(BlockEncodingSerde blockEncodingSerde, Type type, Slice slice)
    {
        Block block = readBlock(blockEncodingSerde, slice.getInput());
        return type.getObject(block, 0);
    }

    public static void writeBlock(BlockEncodingSerde blockEncodingSerde, SliceOutput output, Block block)
    {
        blockEncodingSerde.writeBlock(output, block);
    }
}
