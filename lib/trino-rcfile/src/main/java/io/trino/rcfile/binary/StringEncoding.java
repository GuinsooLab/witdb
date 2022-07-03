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
package io.trino.rcfile.binary;

import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.trino.rcfile.ColumnData;
import io.trino.rcfile.EncodeOutput;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.Type;

import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.trino.rcfile.RcFileDecoderUtils.calculateTruncationLength;
import static io.trino.rcfile.RcFileDecoderUtils.decodeVIntSize;
import static io.trino.rcfile.RcFileDecoderUtils.readVInt;
import static io.trino.rcfile.RcFileDecoderUtils.writeVInt;
import static java.lang.Math.toIntExact;

public class StringEncoding
        implements BinaryColumnEncoding
{
    private static final byte HIVE_EMPTY_STRING_BYTE = (byte) 0xbf;

    private final Type type;

    public StringEncoding(Type type)
    {
        this.type = type;
    }

    @Override
    public void encodeColumn(Block block, SliceOutput output, EncodeOutput encodeOutput)
    {
        for (int position = 0; position < block.getPositionCount(); position++) {
            if (!block.isNull(position)) {
                Slice slice = type.getSlice(block, position);
                if (slice.length() == 0) {
                    output.writeByte(HIVE_EMPTY_STRING_BYTE);
                }
                else {
                    output.writeBytes(slice);
                }
            }
            encodeOutput.closeEntry();
        }
    }

    @Override
    public void encodeValueInto(Block block, int position, SliceOutput output)
    {
        Slice slice = type.getSlice(block, position);
        // Note strings nested in complex structures do no use the empty string marker
        writeVInt(output, slice.length());
        output.writeBytes(slice);
    }

    @Override
    public Block decodeColumn(ColumnData columnData)
    {
        int size = columnData.rowCount();
        BlockBuilder builder = type.createBlockBuilder(null, size);

        Slice slice = columnData.getSlice();
        for (int i = 0; i < size; i++) {
            int length = columnData.getLength(i);
            if (length > 0) {
                int offset = columnData.getOffset(i);
                if ((length == 1) && slice.getByte(offset) == HIVE_EMPTY_STRING_BYTE) {
                    type.writeSlice(builder, EMPTY_SLICE);
                }
                else {
                    length = calculateTruncationLength(type, slice, offset, length);
                    type.writeSlice(builder, slice.slice(offset, length));
                }
            }
            else {
                builder.appendNull();
            }
        }
        return builder.build();
    }

    @Override
    public int getValueOffset(Slice slice, int offset)
    {
        return decodeVIntSize(slice, offset);
    }

    @Override
    public int getValueLength(Slice slice, int offset)
    {
        return toIntExact(readVInt(slice, offset));
    }

    @Override
    public void decodeValueInto(BlockBuilder builder, Slice slice, int offset, int length)
    {
        // Note strings nested in complex structures do no use the empty string marker
        length = calculateTruncationLength(type, slice, offset, length);
        type.writeSlice(builder, slice, offset, length);
    }
}
