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

import io.airlift.slice.Slice;
import io.airlift.stats.TDigest;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.AbstractVariableWidthType;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.TypeSignature;

public class TDigestType
        extends AbstractVariableWidthType
{
    public static final TDigestType TDIGEST = new TDigestType();

    private TDigestType()
    {
        super(new TypeSignature(StandardTypes.TDIGEST), TDigest.class);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            block.writeBytesTo(position, 0, block.getSliceLength(position), blockBuilder);
            blockBuilder.closeEntry();
        }
    }

    @Override
    public Object getObject(Block block, int position)
    {
        return TDigest.deserialize(block.getSlice(position, 0, block.getSliceLength(position)));
    }

    @Override
    public void writeObject(BlockBuilder blockBuilder, Object value)
    {
        Slice serialized = ((TDigest) value).serialize();
        blockBuilder.writeBytes(serialized, 0, serialized.length()).closeEntry();
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return new SqlVarbinary(block.getSlice(position, 0, block.getSliceLength(position)).getBytes());
    }
}
