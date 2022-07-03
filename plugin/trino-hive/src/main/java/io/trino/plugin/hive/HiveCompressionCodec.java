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
package io.trino.plugin.hive;

import io.trino.orc.metadata.CompressionKind;
import org.apache.avro.file.DataFileConstants;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.io.compress.Lz4Codec;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.hadoop.io.compress.ZStandardCodec;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public enum HiveCompressionCodec
{
    NONE(null, CompressionKind.NONE, CompressionCodecName.UNCOMPRESSED, DataFileConstants.NULL_CODEC),
    SNAPPY(SnappyCodec.class, CompressionKind.SNAPPY, CompressionCodecName.SNAPPY, DataFileConstants.SNAPPY_CODEC),
    LZ4(Lz4Codec.class, CompressionKind.LZ4, CompressionCodecName.LZ4, null),
    ZSTD(ZStandardCodec.class, CompressionKind.ZSTD, CompressionCodecName.ZSTD, DataFileConstants.ZSTANDARD_CODEC),
    // Using DEFLATE for GZIP for Avro for now so Avro files can be written in default configuration
    // TODO(https://github.com/trinodb/trino/issues/12580) change GZIP to be unsupported for Avro when we change Trino default compression to be storage format aware
    GZIP(GzipCodec.class, CompressionKind.ZLIB, CompressionCodecName.GZIP, DataFileConstants.DEFLATE_CODEC);

    private final Optional<Class<? extends CompressionCodec>> codec;
    private final CompressionKind orcCompressionKind;
    private final CompressionCodecName parquetCompressionCodec;

    private final Optional<String> avroCompressionCodec;

    HiveCompressionCodec(
            Class<? extends CompressionCodec> codec,
            CompressionKind orcCompressionKind,
            CompressionCodecName parquetCompressionCodec,
            String avroCompressionCodec)
    {
        this.codec = Optional.ofNullable(codec);
        this.orcCompressionKind = requireNonNull(orcCompressionKind, "orcCompressionKind is null");
        this.parquetCompressionCodec = requireNonNull(parquetCompressionCodec, "parquetCompressionCodec is null");
        this.avroCompressionCodec = Optional.ofNullable(avroCompressionCodec);
    }

    public Optional<Class<? extends CompressionCodec>> getCodec()
    {
        return codec;
    }

    public CompressionKind getOrcCompressionKind()
    {
        return orcCompressionKind;
    }

    public CompressionCodecName getParquetCompressionCodec()
    {
        return parquetCompressionCodec;
    }

    public Optional<String> getAvroCompressionCodec()
    {
        return avroCompressionCodec;
    }
}
