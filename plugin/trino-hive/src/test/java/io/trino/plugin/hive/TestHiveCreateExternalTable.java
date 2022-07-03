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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.MaterializedResult;
import io.trino.testing.QueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.testing.QueryAssertions.assertEqualsIgnoreOrder;
import static io.trino.tpch.TpchTable.CUSTOMER;
import static io.trino.tpch.TpchTable.ORDERS;
import static java.lang.String.format;
import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHiveCreateExternalTable
        extends AbstractTestQueryFramework
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return HiveQueryRunner.builder()
                .setHiveProperties(ImmutableMap.of("hive.non-managed-table-writes-enabled", "true"))
                .setInitialTables(ImmutableList.of(ORDERS, CUSTOMER))
                .build();
    }

    @Test
    public void testCreateExternalTableWithData()
            throws IOException
    {
        Path tempDir = createTempDirectory(null);
        Path tableLocation = tempDir.resolve("data");

        @Language("SQL") String createTableSql = format("" +
                        "CREATE TABLE test_create_external " +
                        "WITH (external_location = '%s') AS " +
                        "SELECT * FROM tpch.tiny.nation",
                tableLocation.toUri().toASCIIString());

        assertUpdate(createTableSql, 25);

        MaterializedResult expected = computeActual("SELECT * FROM tpch.tiny.nation");
        MaterializedResult actual = computeActual("SELECT * FROM test_create_external");
        assertEqualsIgnoreOrder(actual.getMaterializedRows(), expected.getMaterializedRows());

        MaterializedResult result = computeActual("SELECT DISTINCT regexp_replace(\"$path\", '/[^/]*$', '/') FROM test_create_external");
        String tablePath = (String) result.getOnlyValue();
        assertThat(tablePath).startsWith(tableLocation.toFile().toURI().toString());

        assertUpdate("DROP TABLE test_create_external");
        deleteRecursively(tempDir, ALLOW_INSECURE);
    }

    @Test
    public void testCreateExternalTableAsWithExistingDirectory()
            throws IOException
    {
        Path tempDir = createTempDirectory(null);

        @Language("SQL") String createTableSql = format("" +
                        "CREATE TABLE test_create_external_exists " +
                        "WITH (external_location = '%s') AS " +
                        "SELECT * FROM tpch.tiny.nation",
                tempDir.toUri().toASCIIString());

        assertQueryFails(createTableSql, "Target directory for table '.*' already exists:.*");
    }
}
