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
import io.trino.tempto.fulfillment.table.jdbc.RelationalDataSource;
import io.trino.tempto.internal.fulfillment.table.cassandra.CassandraTableDefinition;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static io.trino.tests.product.cassandra.TestConstants.CONNECTOR_NAME;
import static io.trino.tests.product.cassandra.TestConstants.KEY_SPACE;

public final class MultiColumnKeyTableDefinition
{
    private MultiColumnKeyTableDefinition() {}

    private static final String MULTI_COLUMN_KEY_DDL =
            "CREATE TABLE %NAME% (" +
                    "user_id text, " +
                    "key text, " +
                    "updated_at timestamp, " +
                    "value text, " +
                    "PRIMARY KEY (user_id, key, updated_at));";
    private static final String MULTI_COLUMN_KEY_TABLE_NAME = "multicolumnkey";

    public static final CassandraTableDefinition CASSANDRA_MULTI_COLUMN_KEY;

    static {
        RelationalDataSource dataSource = () -> ImmutableList.<List<Object>>of(
                ImmutableList.of(
                        "Alice",
                        "a1",
                        OffsetDateTime.of(2015, 1, 1, 1, 1, 1, 0, ZoneOffset.UTC).toInstant(),
                        "Test value 1"),
                ImmutableList.of(
                        "Bob",
                        "b1",
                        OffsetDateTime.of(2014, 2, 2, 3, 4, 5, 0, ZoneOffset.UTC).toInstant(),
                        "Test value 2")
        ).iterator();
        CASSANDRA_MULTI_COLUMN_KEY = CassandraTableDefinition.cassandraBuilder(MULTI_COLUMN_KEY_TABLE_NAME)
                .withDatabase(CONNECTOR_NAME)
                .withSchema(KEY_SPACE)
                .setCreateTableDDLTemplate(MULTI_COLUMN_KEY_DDL)
                .setDataSource(dataSource)
                .build();
    }
}
