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
package io.trino.tests.product.hive;

import io.trino.tempto.ProductTest;
import io.trino.tempto.Requirement;
import io.trino.tempto.RequirementsProvider;
import io.trino.tempto.configuration.Configuration;
import io.trino.tempto.fulfillment.table.TableDefinition;
import io.trino.tempto.fulfillment.table.hive.HiveTableDefinition;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static io.trino.tempto.Requirements.compose;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tempto.fulfillment.table.MutableTableRequirement.State.CREATED;
import static io.trino.tempto.fulfillment.table.MutableTablesState.mutableTablesState;
import static io.trino.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.trino.tempto.fulfillment.table.TableRequirements.mutableTable;
import static io.trino.tests.product.hive.AllSimpleTypesTableDefinitions.ALL_HIVE_SIMPLE_TYPES_TEXTFILE;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.nio.charset.StandardCharsets.UTF_8;

public class TestInsertIntoHiveTable
        extends ProductTest
        implements RequirementsProvider
{
    private static final String TABLE_NAME = "target_table";
    private static final String PARTITIONED_TABLE_WITH_SERDE = "target_partitioned_with_serde_property";

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return compose(
                mutableTable(ALL_HIVE_SIMPLE_TYPES_TEXTFILE, TABLE_NAME, CREATED),
                mutableTable(partitionedTableDefinition(), PARTITIONED_TABLE_WITH_SERDE, CREATED),
                immutableTable(ALL_HIVE_SIMPLE_TYPES_TEXTFILE));
    }

    private static TableDefinition partitionedTableDefinition()
    {
        String createTableDdl = "CREATE TABLE %NAME%( " +
                "id int, " +
                "name string " +
                ") " +
                "PARTITIONED BY (dt string) " +
                "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' " +
                "WITH SERDEPROPERTIES ( " +
                "'field.delim'='\\t', " +
                "'line.delim'='\\n', " +
                "'serialization.format'='\\t' " +
                ")";

        return HiveTableDefinition.builder(PARTITIONED_TABLE_WITH_SERDE)
                .setCreateTableDDLTemplate(createTableDdl)
                .setNoData()
                .build();
    }

    @Test
    public void testInsertIntoValuesToHiveTableAllHiveSimpleTypes()
    {
        String tableNameInDatabase = mutableTablesState().get(TABLE_NAME).getNameInDatabase();
        assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).hasNoRows();
        onTrino().executeQuery("INSERT INTO " + tableNameInDatabase + " VALUES(" +
                "TINYINT '127', " +
                "SMALLINT '32767', " +
                "2147483647, " +
                "9223372036854775807, " +
                "REAL '123.345', " +
                "234.567, " +
                "CAST(346 as DECIMAL(10,0))," +
                "CAST(345.67800 as DECIMAL(10,5))," +
                "timestamp '2015-05-10 12:15:35.123', " +
                "date '2015-05-10', " +
                "'ala ma kota', " +
                "'ala ma kot', " +
                "CAST('ala ma    ' as CHAR(10)), " +
                "true, " +
                "from_base64('a290IGJpbmFybnk=')" +
                ")");

        assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).containsOnly(
                row(
                        127,
                        32767,
                        2147483647,
                        9223372036854775807L,
                        123.345f,
                        234.567,
                        new BigDecimal("346"),
                        new BigDecimal("345.67800"),
                        Timestamp.valueOf(LocalDateTime.of(2015, 5, 10, 12, 15, 35, 123_000_000)),
                        Date.valueOf("2015-05-10"),
                        "ala ma kota",
                        "ala ma kot",
                        "ala ma    ",
                        true,
                        "kot binarny".getBytes(UTF_8)));
    }

    @Test
    public void testInsertIntoSelectToHiveTableAllHiveSimpleTypes()
    {
        String tableNameInDatabase = mutableTablesState().get(TABLE_NAME).getNameInDatabase();
        assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).hasNoRows();
        assertThat(onTrino().executeQuery("INSERT INTO " + tableNameInDatabase + " SELECT * from textfile_all_types")).containsExactlyInOrder(row(1));
        assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).containsOnly(
                row(
                        127,
                        32767,
                        2147483647,
                        9223372036854775807L,
                        123.345f,
                        234.567,
                        new BigDecimal("346"),
                        new BigDecimal("345.67800"),
                        Timestamp.valueOf(LocalDateTime.of(2015, 5, 10, 12, 15, 35, 123_000_000)),
                        Date.valueOf("2015-05-10"),
                        "ala ma kota",
                        "ala ma kot",
                        "ala ma    ",
                        true,
                        "kot binarny".getBytes(UTF_8)));
    }

    @Test
    public void testInsertIntoPartitionedWithSerdeProperty()
    {
        String tableNameInDatabase = mutableTablesState().get(PARTITIONED_TABLE_WITH_SERDE).getNameInDatabase();
        assertThat(onTrino().executeQuery("INSERT INTO " + tableNameInDatabase + " SELECT 1, 'Trino', '2018-01-01'")).containsExactlyInOrder(row(1));
        assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).containsExactlyInOrder(row(1, "Trino", "2018-01-01"));
    }
}
