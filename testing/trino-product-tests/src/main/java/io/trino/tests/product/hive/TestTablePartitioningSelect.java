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

import com.google.inject.Inject;
import io.trino.tempto.ProductTest;
import io.trino.tempto.Requirement;
import io.trino.tempto.RequirementsProvider;
import io.trino.tempto.configuration.Configuration;
import io.trino.tempto.fulfillment.table.MutableTablesState;
import io.trino.tempto.fulfillment.table.hive.HiveDataSource;
import io.trino.tempto.fulfillment.table.hive.HiveTableDefinition;
import io.trino.tempto.query.QueryExecutionException;
import io.trino.tempto.query.QueryResult;
import org.testng.annotations.Test;

import java.util.Locale;
import java.util.Optional;

import static io.trino.tempto.Requirements.allOf;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tempto.fulfillment.table.MutableTableRequirement.State.LOADED;
import static io.trino.tempto.fulfillment.table.TableRequirements.mutableTable;
import static io.trino.tempto.fulfillment.table.hive.InlineDataSource.createResourceDataSource;
import static io.trino.tempto.fulfillment.table.hive.InlineDataSource.createStringDataSource;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;

public class TestTablePartitioningSelect
        extends ProductTest
        implements RequirementsProvider
{
    private static final HiveTableDefinition SINGLE_INT_COLUMN_PARTITIONEND_TEXTFILE = singleIntColumnPartitionedTableDefinition("TEXTFILE", Optional.of("DELIMITED FIELDS TERMINATED BY '|'"));
    private static final HiveTableDefinition SINGLE_INT_COLUMN_PARTITIONED_ORC = singleIntColumnPartitionedTableDefinition("ORC", Optional.empty());
    private static final HiveTableDefinition SINGLE_INT_COLUMN_PARTITIONED_RCFILE = singleIntColumnPartitionedTableDefinition("RCFILE", Optional.of("SERDE 'org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe'"));
    private static final HiveTableDefinition SINGLE_INT_COLUMN_PARTITIONED_PARQUET = singleIntColumnPartitionedTableDefinition("PARQUET", Optional.empty());
    private static final HiveTableDefinition SINGLE_INT_COLUMN_PARTITIONED_AVRO = singleIntColumnPartitionedTableDefinition("AVRO", Optional.of("SERDE 'org.apache.hadoop.hive.serde2.avro.AvroSerDe'"));
    private static final String TABLE_NAME = "test_table";

    @Inject
    private MutableTablesState tablesState;

    private static HiveTableDefinition singleIntColumnPartitionedTableDefinition(String fileFormat, Optional<String> serde)
    {
        String tableName = fileFormat.toLowerCase(Locale.ENGLISH) + "_single_int_column_partitioned";
        HiveDataSource dataSource = createResourceDataSource(tableName, "io/trino/tests/product/hive/data/single_int_column/data." + fileFormat.toLowerCase(Locale.ENGLISH));
        HiveDataSource invalidData = createStringDataSource(tableName, "INVALID DATA");
        return HiveTableDefinition.builder(tableName)
                .setCreateTableDDLTemplate(buildSingleIntColumnPartitionedTableDDL(fileFormat, serde))
                .addPartition("part_col = 1", invalidData)
                .addPartition("part_col = 2", dataSource)
                .build();
    }

    private static String buildSingleIntColumnPartitionedTableDDL(String fileFormat, Optional<String> rowFormat)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE %EXTERNAL% TABLE %NAME%(");
        sb.append("   col INT");
        sb.append(") ");
        sb.append("PARTITIONED BY (part_col INT) ");
        if (rowFormat.isPresent()) {
            sb.append("ROW FORMAT ").append(rowFormat.get());
        }
        sb.append(" STORED AS " + fileFormat);
        sb.append(" TBLPROPERTIES ('transactional'='false')");
        return sb.toString();
    }

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return allOf(
                mutableTable(SINGLE_INT_COLUMN_PARTITIONEND_TEXTFILE, TABLE_NAME, LOADED),
                mutableTable(SINGLE_INT_COLUMN_PARTITIONED_ORC, TABLE_NAME, LOADED),
                mutableTable(SINGLE_INT_COLUMN_PARTITIONED_RCFILE, TABLE_NAME, LOADED),
                mutableTable(SINGLE_INT_COLUMN_PARTITIONED_PARQUET, TABLE_NAME, LOADED),
                mutableTable(SINGLE_INT_COLUMN_PARTITIONED_AVRO, TABLE_NAME, LOADED));
    }

    @Test
    public void testSelectPartitionedHiveTableDifferentFormats()
    {
        String tableNameInDatabase = tablesState.get(TABLE_NAME).getNameInDatabase();

        String selectFromOnePartitionsSql = "SELECT * FROM " + tableNameInDatabase + " WHERE part_col = 2";
        QueryResult onePartitionQueryResult = onTrino().executeQuery(selectFromOnePartitionsSql);
        assertThat(onePartitionQueryResult).containsOnly(row(42, 2));

        try {
            // This query should fail or return null values for invalid partition data
            assertThat(onTrino().executeQuery("SELECT * FROM " + tableNameInDatabase)).containsOnly(row(42, 2), row(null, 1));
        }
        catch (QueryExecutionException expectedDueToInvalidPartitionData) {
        }
    }
}
