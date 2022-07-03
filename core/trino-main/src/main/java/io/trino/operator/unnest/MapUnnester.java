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
package io.trino.operator.unnest;

import io.trino.spi.block.Block;
import io.trino.spi.block.ColumnarMap;
import io.trino.spi.type.Type;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.spi.block.ColumnarMap.toColumnarMap;

/**
 * Unnester for a nested column with map type.
 * Maintains a {@link ColumnarMap} object to get underlying keys and values block from the map block.
 * <p>
 * All protected methods implemented here assume that they are being invoked when {@code columnarMap} is non-null.
 */
class MapUnnester
        extends Unnester
{
    private ColumnarMap columnarMap;

    public MapUnnester(Type keyType, Type valueType)
    {
        super(keyType, valueType);
    }

    @Override
    protected void processCurrentPosition(int requiredOutputCount)
    {
        // Translate indices
        int mapLength = columnarMap.getEntryCount(getCurrentPosition());
        int startingOffset = columnarMap.getOffset(getCurrentPosition());

        // Append elements and nulls
        getBlockBuilder(0).appendRange(startingOffset, mapLength);
        getBlockBuilder(1).appendRange(startingOffset, mapLength);
        appendNulls(requiredOutputCount - mapLength);
    }

    @Override
    protected void appendNulls(int count)
    {
        for (int i = 0; i < count; i++) {
            getBlockBuilder(0).appendNull();
            getBlockBuilder(1).appendNull();
        }
    }

    @Override
    public int getChannelCount()
    {
        return 2;
    }

    @Override
    public int getInputEntryCount()
    {
        if (columnarMap == null) {
            return 0;
        }
        return columnarMap.getPositionCount();
    }

    @Override
    protected void resetColumnarStructure(Block block)
    {
        this.columnarMap = toColumnarMap(block);
    }

    @Override
    protected Block getElementsBlock(int channel)
    {
        checkState(channel == 0 || channel == 1, "index is not 0 or 1");
        if (channel == 0) {
            return columnarMap.getKeysBlock();
        }
        return columnarMap.getValuesBlock();
    }

    @Override
    protected int getElementsLength(int index)
    {
        return columnarMap.getEntryCount(index);
    }
}
