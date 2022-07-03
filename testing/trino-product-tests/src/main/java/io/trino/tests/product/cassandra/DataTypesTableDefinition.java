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
package io.trino.tests.product.cassandra;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.tempto.fulfillment.table.jdbc.RelationalDataSource;
import io.trino.tempto.internal.fulfillment.table.cassandra.CassandraTableDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static com.datastax.oss.driver.api.core.data.ByteUtils.fromHexString;
import static io.trino.tests.product.cassandra.TestConstants.CONNECTOR_NAME;
import static io.trino.tests.product.cassandra.TestConstants.KEY_SPACE;
import static java.lang.Double.parseDouble;

public final class DataTypesTableDefinition
{
    private DataTypesTableDefinition() {}

    public static final CassandraTableDefinition CASSANDRA_ALL_TYPES;

    private static final String ALL_TYPES_TABLE_NAME = "all_types";

    private static final String ALL_TYPES_DDL =
            "CREATE TABLE %NAME% (a ascii, b bigint, bl blob, bo boolean, d decimal, do double, dt date," +
                    "f float, fr frozen<set<int>>, i inet, integer int, l list<int>, m map<text,int>," +
                    "s set<int>, si smallint, t text, ti tinyint, ts timestamp, tu timeuuid, u uuid, v varchar, vari varint," +
                    "PRIMARY KEY (a))";

    static {
        RelationalDataSource dataSource = () -> {
            try {
                return ImmutableList.of(
                        ImmutableList.of(
                                "\0",
                                Long.MIN_VALUE,
                                fromHexString("0x00"),
                                false,
                                BigDecimal.ZERO,
                                Double.MIN_VALUE,
                                LocalDate.of(1970, 1, 2),
                                Float.MIN_VALUE,
                                ImmutableSet.of(0),
                                Inet4Address.getByName("0.0.0.0"),
                                Integer.MIN_VALUE,
                                ImmutableList.of(0),
                                ImmutableMap.of("a", 0, "\0", Integer.MIN_VALUE),
                                ImmutableSet.of(0),
                                Short.MIN_VALUE,
                                "\0",
                                Byte.MIN_VALUE,
                                OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                                UUID.fromString("d2177dd0-eaa2-11de-a572-001b779c76e3"),
                                UUID.fromString("01234567-0123-0123-0123-0123456789ab"),
                                "\0",
                                BigInteger.valueOf(Long.MIN_VALUE)),
                        ImmutableList.of(
                                "the quick brown fox jumped over the lazy dog",
                                Long.MAX_VALUE,
                                fromHexString("0x3031323334"),
                                true,
                                BigDecimal.valueOf(parseDouble("99999999999999999999999999999999999999")),
                                Double.MAX_VALUE,
                                LocalDate.of(9999, 12, 31),
                                Float.MAX_VALUE,
                                ImmutableSet.of(4, 5, 6, 7),
                                Inet4Address.getByName("255.255.255.255"),
                                Integer.MAX_VALUE,
                                ImmutableList.of(4, 5, 6),
                                ImmutableMap.of("a", 1, "b", 2),
                                ImmutableSet.of(4, 5, 6),
                                Short.MAX_VALUE,
                                "this is a text value",
                                Byte.MAX_VALUE,
                                OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant(),
                                UUID.fromString("d2177dd0-eaa2-11de-a572-001b779c76e3"),
                                UUID.fromString("01234567-0123-0123-0123-0123456789ab"),
                                "abc",
                                BigInteger.valueOf(Long.MAX_VALUE)),
                        Arrays.asList(new Object[] {"def", null, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null})
                ).iterator();
            }
            catch (UnknownHostException e) {
                return null;
            }
        };
        CASSANDRA_ALL_TYPES = CassandraTableDefinition.cassandraBuilder(ALL_TYPES_TABLE_NAME)
                .withDatabase(CONNECTOR_NAME)
                .withSchema(KEY_SPACE)
                .setCreateTableDDLTemplate(ALL_TYPES_DDL)
                .setDataSource(dataSource)
                .build();
    }
}
