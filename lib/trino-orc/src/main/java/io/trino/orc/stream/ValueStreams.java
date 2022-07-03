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

import io.trino.orc.StreamId;
import io.trino.orc.metadata.ColumnEncoding.ColumnEncodingKind;
import io.trino.orc.metadata.OrcType.OrcTypeKind;

import static io.trino.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY;
import static io.trino.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY_V2;
import static io.trino.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT;
import static io.trino.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT_V2;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.DECIMAL;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.TIMESTAMP;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.TIMESTAMP_INSTANT;
import static io.trino.orc.metadata.Stream.StreamKind.DATA;
import static io.trino.orc.metadata.Stream.StreamKind.DICTIONARY_DATA;
import static io.trino.orc.metadata.Stream.StreamKind.LENGTH;
import static io.trino.orc.metadata.Stream.StreamKind.PRESENT;
import static io.trino.orc.metadata.Stream.StreamKind.SECONDARY;
import static java.lang.String.format;

public final class ValueStreams
{
    private ValueStreams() {}

    public static ValueInputStream<?> createValueStreams(
            StreamId streamId,
            OrcChunkLoader chunkLoader,
            OrcTypeKind type,
            ColumnEncodingKind encoding)
    {
        if (streamId.getStreamKind() == PRESENT) {
            return new BooleanInputStream(new OrcInputStream(chunkLoader));
        }

        // dictionary length and data streams are unsigned int streams
        if ((encoding == DICTIONARY || encoding == DICTIONARY_V2) && (streamId.getStreamKind() == LENGTH || streamId.getStreamKind() == DATA)) {
            return createLongStream(new OrcInputStream(chunkLoader), encoding, false);
        }

        if (streamId.getStreamKind() == DATA) {
            switch (type) {
                case BOOLEAN:
                    return new BooleanInputStream(new OrcInputStream(chunkLoader));
                case BYTE:
                    return new ByteInputStream(new OrcInputStream(chunkLoader));
                case SHORT:
                case INT:
                case LONG:
                case DATE:
                    return createLongStream(new OrcInputStream(chunkLoader), encoding, true);
                case FLOAT:
                    return new FloatInputStream(new OrcInputStream(chunkLoader));
                case DOUBLE:
                    return new DoubleInputStream(new OrcInputStream(chunkLoader));
                case STRING:
                case VARCHAR:
                case CHAR:
                case BINARY:
                    return new ByteArrayInputStream(new OrcInputStream(chunkLoader));
                case TIMESTAMP:
                case TIMESTAMP_INSTANT:
                    return createLongStream(new OrcInputStream(chunkLoader), encoding, true);
                case DECIMAL:
                    return new DecimalInputStream(chunkLoader);
                case UNION:
                    return new ByteInputStream(new OrcInputStream(chunkLoader));
                case LIST:
                case MAP:
                case STRUCT:
                    // not a DATA
            }
        }

        // length stream of a direct encoded string or binary column
        if (streamId.getStreamKind() == LENGTH) {
            switch (type) {
                case STRING:
                case VARCHAR:
                case CHAR:
                case BINARY:
                case MAP:
                case LIST:
                    return createLongStream(new OrcInputStream(chunkLoader), encoding, false);
                default:
                    break;
            }
        }

        // length (nanos) of a timestamp column
        if ((type == TIMESTAMP || type == TIMESTAMP_INSTANT) && streamId.getStreamKind() == SECONDARY) {
            return createLongStream(new OrcInputStream(chunkLoader), encoding, false);
        }

        // scale of a decimal column
        if (type == DECIMAL && streamId.getStreamKind() == SECONDARY) {
            // specification (https://orc.apache.org/docs/encodings.html) says scale stream is unsigned,
            // however Hive writer stores scale as signed integer (org.apache.hadoop.hive.ql.io.orc.WriterImpl.DecimalTreeWriter)
            // BUG link: https://issues.apache.org/jira/browse/HIVE-13229
            return createLongStream(new OrcInputStream(chunkLoader), encoding, true);
        }

        if (streamId.getStreamKind() == DICTIONARY_DATA) {
            switch (type) {
                case STRING:
                case VARCHAR:
                case CHAR:
                case BINARY:
                    return new ByteArrayInputStream(new OrcInputStream(chunkLoader));
                default:
                    break;
            }
        }

        throw new IllegalArgumentException(format("Unsupported column type %s for stream %s with encoding %s", type, streamId, encoding));
    }

    private static ValueInputStream<?> createLongStream(
            OrcInputStream inputStream,
            ColumnEncodingKind encoding,
            boolean signed)
    {
        if (encoding == DIRECT_V2 || encoding == DICTIONARY_V2) {
            return new LongInputStreamV2(inputStream, signed, false);
        }
        else if (encoding == DIRECT || encoding == DICTIONARY) {
            return new LongInputStreamV1(inputStream, signed);
        }
        else {
            throw new IllegalArgumentException("Unsupported encoding for long stream: " + encoding);
        }
    }
}
