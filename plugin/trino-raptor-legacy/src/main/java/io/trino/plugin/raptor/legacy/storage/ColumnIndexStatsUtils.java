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
package io.trino.plugin.raptor.legacy.storage;

import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.sql.JDBCType;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;

public final class ColumnIndexStatsUtils
{
    private ColumnIndexStatsUtils() {}

    public static JDBCType jdbcType(Type type)
    {
        if (type.equals(BOOLEAN)) {
            return JDBCType.BOOLEAN;
        }
        if (type.equals(BIGINT) || type.equals(TIMESTAMP_MILLIS)) {
            return JDBCType.BIGINT;
        }
        if (type.equals(INTEGER)) {
            return JDBCType.INTEGER;
        }
        if (type.equals(DOUBLE)) {
            return JDBCType.DOUBLE;
        }
        if (type.equals(DATE)) {
            return JDBCType.INTEGER;
        }
        if (type instanceof VarcharType) {
            return JDBCType.VARBINARY;
        }
        return null;
    }
}
