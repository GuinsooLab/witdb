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

import io.trino.parquet.ParquetEncoding;
import org.apache.parquet.io.api.Binary;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class Dictionary
{
    public Dictionary(ParquetEncoding encoding)
    {
        checkArgument(
                encoding == ParquetEncoding.PLAIN_DICTIONARY || encoding == ParquetEncoding.PLAIN,
                "Dictionary does not support encoding: %s",
                encoding);
    }

    public Binary decodeToBinary(int id)
    {
        throw new UnsupportedOperationException();
    }

    public int decodeToInt(int id)
    {
        throw new UnsupportedOperationException();
    }

    public long decodeToLong(int id)
    {
        throw new UnsupportedOperationException();
    }

    public float decodeToFloat(int id)
    {
        throw new UnsupportedOperationException();
    }

    public double decodeToDouble(int id)
    {
        throw new UnsupportedOperationException();
    }
}
