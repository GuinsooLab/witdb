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
package io.trino.parquet.predicate;

import com.google.common.collect.ImmutableSet;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.EncodingStats;
import org.apache.parquet.column.statistics.BinaryStatistics;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;
import org.testng.annotations.Test;

import java.util.Set;

import static com.google.common.collect.Sets.union;
import static io.trino.parquet.ParquetReaderUtils.isOnlyDictionaryEncodingPages;
import static io.trino.parquet.predicate.PredicateUtils.isStatisticsOverflow;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static org.apache.parquet.column.Encoding.BIT_PACKED;
import static org.apache.parquet.column.Encoding.PLAIN;
import static org.apache.parquet.column.Encoding.PLAIN_DICTIONARY;
import static org.apache.parquet.column.Encoding.RLE;
import static org.apache.parquet.column.Encoding.RLE_DICTIONARY;
import static org.apache.parquet.hadoop.metadata.ColumnPath.fromDotString;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.UNCOMPRESSED;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestPredicateUtils
{
    @Test
    public void testIsStatisticsOverflow()
    {
        assertFalse(isStatisticsOverflow(TINYINT, -10L, 10L));
        assertTrue(isStatisticsOverflow(TINYINT, -129L, 10L));
        assertTrue(isStatisticsOverflow(TINYINT, -10L, 129L));

        assertFalse(isStatisticsOverflow(SMALLINT, -32_000L, 32_000L));
        assertTrue(isStatisticsOverflow(SMALLINT, -100_000L, 32_000L));
        assertTrue(isStatisticsOverflow(SMALLINT, -32_000L, 100_000L));

        assertFalse(isStatisticsOverflow(INTEGER, -2_000_000_000L, 2_000_000_000L));
        assertTrue(isStatisticsOverflow(INTEGER, -3_000_000_000L, 2_000_000_000L));
        assertTrue(isStatisticsOverflow(INTEGER, -2_000_000_000L, 3_000_000_000L));

        // short decimal
        assertFalse(isStatisticsOverflow(createDecimalType(5, 0), -10_000L, 10_000L));
        assertTrue(isStatisticsOverflow(createDecimalType(5, 0), -100_000L, 10_000L));
        assertTrue(isStatisticsOverflow(createDecimalType(5, 0), -10_000L, 100_000L));

        // long decimal
        assertFalse(isStatisticsOverflow(createDecimalType(19, 0), -1_000_000_000_000_000_000L, 1_000_000_000_000_000_000L));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDictionaryEncodingV1()
    {
        Set<Encoding> required = ImmutableSet.of(BIT_PACKED);
        Set<Encoding> optional = ImmutableSet.of(BIT_PACKED, RLE);
        Set<Encoding> repeated = ImmutableSet.of(RLE);

        Set<Encoding> notDictionary = ImmutableSet.of(PLAIN);
        Set<Encoding> mixedDictionary = ImmutableSet.of(PLAIN_DICTIONARY, PLAIN);
        Set<Encoding> dictionary = ImmutableSet.of(PLAIN_DICTIONARY);

        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(required, notDictionary))), "required notDictionary");
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(optional, notDictionary))), "optional notDictionary");
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(repeated, notDictionary))), "repeated notDictionary");
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(required, mixedDictionary))), "required mixedDictionary");
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(optional, mixedDictionary))), "optional mixedDictionary");
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(repeated, mixedDictionary))), "repeated mixedDictionary");
        assertTrue(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(required, dictionary))), "required dictionary");
        assertTrue(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(optional, dictionary))), "optional dictionary");
        assertTrue(isOnlyDictionaryEncodingPages(createColumnMetaDataV1(union(repeated, dictionary))), "repeated dictionary");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDictionaryEncodingV2()
    {
        assertTrue(isOnlyDictionaryEncodingPages(createColumnMetaDataV2(RLE_DICTIONARY)));
        assertTrue(isOnlyDictionaryEncodingPages(createColumnMetaDataV2(PLAIN_DICTIONARY)));
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV2(PLAIN)));

        // Simulate fallback to plain encoding e.g. too many unique entries
        assertFalse(isOnlyDictionaryEncodingPages(createColumnMetaDataV2(RLE_DICTIONARY, PLAIN)));
    }

    private ColumnChunkMetaData createColumnMetaDataV2(Encoding... dataEncodings)
    {
        EncodingStats encodingStats = new EncodingStats.Builder()
                .withV2Pages()
                .addDictEncoding(PLAIN)
                .addDataEncodings(ImmutableSet.copyOf(dataEncodings)).build();

        PrimitiveType type = Types.optional(BINARY).named("");
        Statistics<?> stats = Statistics.createStats(type);
        return ColumnChunkMetaData.get(fromDotString("column"), type, UNCOMPRESSED, encodingStats, encodingStats.getDataEncodings(), stats, 0, 0, 1, 1, 1);
    }

    @SuppressWarnings("deprecation")
    private ColumnChunkMetaData createColumnMetaDataV1(Set<Encoding> encodings)
    {
        return ColumnChunkMetaData.get(fromDotString("column"), BINARY, UNCOMPRESSED, encodings, new BinaryStatistics(), 0, 0, 1, 1, 1);
    }
}
