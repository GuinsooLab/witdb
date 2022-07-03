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
package io.trino.orc.stream;

import io.trino.orc.OrcCorruptionException;
import io.trino.orc.checkpoint.LongStreamCheckpoint;
import io.trino.orc.checkpoint.LongStreamV1Checkpoint;

import java.io.IOException;

import static java.lang.Math.min;

public class LongInputStreamV1
        implements LongInputStream
{
    private static final int MIN_REPEAT_SIZE = 3;
    private static final int MAX_LITERAL_SIZE = 128;

    private final OrcInputStream input;
    private final boolean signed;
    private final long[] literals = new long[MAX_LITERAL_SIZE];
    private int numLiterals;
    private int delta;
    private int used;
    private boolean repeat;
    private long lastReadInputCheckpoint;

    public LongInputStreamV1(OrcInputStream input, boolean signed)
    {
        this.input = input;
        this.signed = signed;
        lastReadInputCheckpoint = input.getCheckpoint();
    }

    // This comes from the Apache Hive ORC code
    private void readValues()
            throws IOException
    {
        lastReadInputCheckpoint = input.getCheckpoint();

        int control = input.read();
        if (control == -1) {
            throw new OrcCorruptionException(input.getOrcDataSourceId(), "Read past end of RLE integer");
        }

        if (control < 0x80) {
            numLiterals = control + MIN_REPEAT_SIZE;
            used = 0;
            repeat = true;
            int delta = input.read();
            if (delta == -1) {
                throw new OrcCorruptionException(input.getOrcDataSourceId(), "End of stream in RLE Integer");
            }

            // convert from 0 to 255 to -128 to 127 by converting to a signed byte
            this.delta = (byte) delta;
            literals[0] = LongDecode.readVInt(signed, input);
        }
        else {
            numLiterals = 0x100 - control;
            used = 0;
            repeat = false;
            for (int i = 0; i < numLiterals; ++i) {
                literals[i] = LongDecode.readVInt(signed, input);
            }
        }
    }

    @Override
    // This comes from the Apache Hive ORC code
    public long next()
            throws IOException
    {
        long result;
        if (used == numLiterals) {
            readValues();
        }
        if (repeat) {
            result = literals[0] + (used++) * delta;
        }
        else {
            result = literals[used++];
        }
        return result;
    }

    @Override
    public void next(long[] values, int items)
            throws IOException
    {
        int offset = 0;
        while (items > 0) {
            if (used == numLiterals) {
                numLiterals = 0;
                used = 0;
                readValues();
            }

            int chunkSize = min(numLiterals - used, items);
            if (repeat) {
                for (int i = 0; i < chunkSize; i++) {
                    values[offset + i] = literals[0] + ((used + i) * delta);
                }
            }
            else {
                System.arraycopy(literals, used, values, offset, chunkSize);
            }
            used += chunkSize;
            offset += chunkSize;
            items -= chunkSize;
        }
    }

    @Override
    public void next(int[] values, int items)
            throws IOException
    {
        int offset = 0;
        while (items > 0) {
            if (used == numLiterals) {
                numLiterals = 0;
                used = 0;
                readValues();
            }

            int chunkSize = min(numLiterals - used, items);
            if (repeat) {
                for (int i = 0; i < chunkSize; i++) {
                    long literal = literals[0] + ((used + i) * delta);
                    int value = (int) literal;
                    if (literal != value) {
                        throw new OrcCorruptionException(input.getOrcDataSourceId(), "Decoded value out of range for a 32bit number");
                    }
                    values[offset + i] = value;
                }
            }
            else {
                for (int i = 0; i < chunkSize; i++) {
                    long literal = literals[used + i];
                    int value = (int) literal;
                    if (literal != value) {
                        throw new OrcCorruptionException(input.getOrcDataSourceId(), "Decoded value out of range for a 32bit number");
                    }
                    values[offset + i] = value;
                }
            }
            used += chunkSize;
            offset += chunkSize;
            items -= chunkSize;
        }
    }

    @Override
    public void next(short[] values, int items)
            throws IOException
    {
        int offset = 0;
        while (items > 0) {
            if (used == numLiterals) {
                numLiterals = 0;
                used = 0;
                readValues();
            }

            int chunkSize = min(numLiterals - used, items);
            if (repeat) {
                for (int i = 0; i < chunkSize; i++) {
                    long literal = literals[0] + ((used + i) * delta);
                    short value = (short) literal;
                    if (literal != value) {
                        throw new OrcCorruptionException(input.getOrcDataSourceId(), "Decoded value out of range for a 16bit number");
                    }
                    values[offset + i] = value;
                }
            }
            else {
                for (int i = 0; i < chunkSize; i++) {
                    long literal = literals[used + i];
                    short value = (short) literal;
                    if (literal != value) {
                        throw new OrcCorruptionException(input.getOrcDataSourceId(), "Decoded value out of range for a 16bit number");
                    }
                    values[offset + i] = value;
                }
            }
            used += chunkSize;
            offset += chunkSize;
            items -= chunkSize;
        }
    }

    @Override
    public void seekToCheckpoint(LongStreamCheckpoint checkpoint)
            throws IOException
    {
        LongStreamV1Checkpoint v1Checkpoint = (LongStreamV1Checkpoint) checkpoint;

        // if the checkpoint is within the current buffer, just adjust the pointer
        if (lastReadInputCheckpoint == v1Checkpoint.getInputStreamCheckpoint() && v1Checkpoint.getOffset() <= numLiterals) {
            used = v1Checkpoint.getOffset();
        }
        else {
            // otherwise, discard the buffer and start over
            input.seekToCheckpoint(v1Checkpoint.getInputStreamCheckpoint());
            numLiterals = 0;
            used = 0;
            skip(v1Checkpoint.getOffset());
        }
    }

    @Override
    public void skip(long items)
            throws IOException
    {
        while (items > 0) {
            if (used == numLiterals) {
                readValues();
            }
            long consume = min(items, numLiterals - used);
            used += consume;
            items -= consume;
        }
    }
}
