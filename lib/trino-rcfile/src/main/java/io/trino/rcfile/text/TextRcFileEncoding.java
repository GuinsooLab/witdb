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
package io.trino.rcfile.text;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.rcfile.ColumnEncoding;
import io.trino.rcfile.RcFileEncoding;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TextRcFileEncoding
        implements RcFileEncoding
{
    private static final byte[] DEFAULT_SEPARATORS = new byte[] {
            1,  // Start of Heading
            2,  // Start of text
            3,  // End of Text
            4,  // End of Transmission
            5,  // Enquiry
            6,  // Acknowledge
            7,  // Bell
            8,  // Backspace
            // RESERVED 9,  // Horizontal Tab
            // RESERVED 10, // Line Feed
            11, // Vertical Tab
            // RESERVED 12, // Form Feed
            // RESERVED 13, // Carriage Return
            14, // Shift Out
            15, // Shift In
            16, // Data Link Escape
            17, // Device Control One
            18, // Device Control Two
            19, // Device Control Three
            20, // Device Control Four
            21, // Negative Acknowledge
            22, // Synchronous Idle
            23, // End of Transmission Block
            24, // Cancel
            25, // End of medium
            26, // Substitute
            // RESERVED 27, // Escape
            28, // File Separator
            29, // Group separator
            // RESERVED 30, // Record Separator
            // RESERVED 31, // Unit separator
    };
    public static final Slice DEFAULT_NULL_SEQUENCE = Slices.utf8Slice("\\N");

    private final Slice nullSequence;
    private final byte[] separators;
    private final Byte escapeByte;
    private final boolean lastColumnTakesRest;

    public TextRcFileEncoding()
    {
        this(
                DEFAULT_NULL_SEQUENCE,
                DEFAULT_SEPARATORS.clone(),
                null,
                false);
    }

    public TextRcFileEncoding(Slice nullSequence, byte[] separators, Byte escapeByte, boolean lastColumnTakesRest)
    {
        this.nullSequence = nullSequence;
        this.separators = separators;
        this.escapeByte = escapeByte;
        this.lastColumnTakesRest = lastColumnTakesRest;
    }

    public static byte[] getDefaultSeparators(int nestingLevels)
    {
        return Arrays.copyOf(DEFAULT_SEPARATORS, nestingLevels);
    }

    @Override
    public ColumnEncoding booleanEncoding(Type type)
    {
        return new BooleanEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding byteEncoding(Type type)
    {
        return longEncoding(type);
    }

    @Override
    public ColumnEncoding shortEncoding(Type type)
    {
        return longEncoding(type);
    }

    @Override
    public ColumnEncoding intEncoding(Type type)
    {
        return longEncoding(type);
    }

    @Override
    public ColumnEncoding longEncoding(Type type)
    {
        return new LongEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding decimalEncoding(Type type)
    {
        return new DecimalEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding floatEncoding(Type type)
    {
        return new FloatEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding doubleEncoding(Type type)
    {
        return new DoubleEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding stringEncoding(Type type)
    {
        return new StringEncoding(type, nullSequence, escapeByte);
    }

    @Override
    public ColumnEncoding binaryEncoding(Type type)
    {
        // binary text encoding is not escaped
        return new BinaryEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding dateEncoding(Type type)
    {
        return new DateEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding timestampEncoding(TimestampType type)
    {
        return new TimestampEncoding(type, nullSequence);
    }

    @Override
    public ColumnEncoding listEncoding(Type type, ColumnEncoding elementEncoding)
    {
        return new ListEncoding(
                type,
                nullSequence,
                separators,
                escapeByte,
                (TextColumnEncoding) elementEncoding);
    }

    @Override
    public ColumnEncoding mapEncoding(Type type, ColumnEncoding keyEncoding, ColumnEncoding valueEncoding)
    {
        return new MapEncoding(
                type,
                nullSequence,
                separators,
                escapeByte,
                (TextColumnEncoding) keyEncoding,
                (TextColumnEncoding) valueEncoding);
    }

    @Override
    public ColumnEncoding structEncoding(Type type, List<ColumnEncoding> fieldEncodings)
    {
        return new StructEncoding(
                type,
                nullSequence,
                separators,
                escapeByte,
                lastColumnTakesRest,
                fieldEncodings.stream()
                        .map(TextColumnEncoding.class::cast)
                        .collect(Collectors.toList()));
    }
}
