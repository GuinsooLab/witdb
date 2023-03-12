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
package io.trino.parquet.dictionary;

import io.trino.parquet.DictionaryPage;
import io.trino.parquet.reader.SimpleSliceInputStream;
import io.trino.parquet.reader.decoders.ValueDecoder;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.trino.parquet.reader.decoders.PlainValueDecoders.IntPlainValueDecoder;

public class IntegerDictionary
        implements Dictionary
{
    private final int[] content;

    public IntegerDictionary(DictionaryPage dictionaryPage)
    {
        content = new int[dictionaryPage.getDictionarySize()];
        ValueDecoder<int[]> intReader = new IntPlainValueDecoder();
        intReader.init(new SimpleSliceInputStream(dictionaryPage.getSlice()));
        intReader.read(content, 0, dictionaryPage.getDictionarySize());
    }

    @Override
    public int decodeToInt(int id)
    {
        return content[id];
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("content", content)
                .toString();
    }
}
