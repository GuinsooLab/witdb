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
package io.trino.orc.metadata;

import io.trino.orc.metadata.PostScript.HiveWriterVersion;
import io.trino.orc.metadata.statistics.BloomFilter;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.List;

public interface MetadataReader
{
    PostScript readPostScript(InputStream inputStream)
            throws IOException;

    Metadata readMetadata(HiveWriterVersion hiveWriterVersion, InputStream inputStream)
            throws IOException;

    Footer readFooter(HiveWriterVersion hiveWriterVersion, InputStream inputStream)
            throws IOException;

    StripeFooter readStripeFooter(ColumnMetadata<OrcType> types, InputStream inputStream, ZoneId legacyFileTimeZone)
            throws IOException;

    List<RowGroupIndex> readRowIndexes(HiveWriterVersion hiveWriterVersion, InputStream inputStream)
            throws IOException;

    List<BloomFilter> readBloomFilterIndexes(InputStream inputStream)
            throws IOException;
}
