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
package io.trino.tests.product;

import com.google.common.collect.ImmutableList;
import io.trino.tempto.ProductTest;
import org.testng.annotations.Test;

import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tests.product.TestGroups.JDBC;
import static io.trino.tests.product.TestGroups.SYSTEM_CONNECTOR;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.sql.JDBCType.ARRAY;
import static java.sql.JDBCType.BIGINT;
import static java.sql.JDBCType.TIMESTAMP_WITH_TIMEZONE;
import static java.sql.JDBCType.VARCHAR;

public class TestSystemConnector
        extends ProductTest
{
    @Test(groups = {SYSTEM_CONNECTOR, JDBC})
    public void selectRuntimeNodes()
    {
        String sql = "SELECT node_id, http_uri, node_version, state FROM system.runtime.nodes";
        assertThat(onTrino().executeQuery(sql))
                .hasColumns(VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .hasAnyRows();
    }

    @Test(groups = {SYSTEM_CONNECTOR, JDBC})
    public void testRuleStats()
    {
        assertThat(onTrino().executeQuery("SELECT rule_name, invocations, matches, failures FROM system.runtime.optimizer_rule_stats"))
                .hasColumns(VARCHAR, BIGINT, BIGINT, BIGINT)
                .hasAnyRows();
    }

    @Test(groups = {SYSTEM_CONNECTOR, JDBC})
    public void selectRuntimeQueries()
    {
        String sql = "SELECT" +
                "  query_id," +
                "  state," +
                "  user," +
                "  query," +
                "  resource_group_id," +
                "  queued_time_ms," +
                "  analysis_time_ms," +
                "  planning_time_ms," +
                "  created," +
                "  started," +
                "  last_heartbeat," +
                "  \"end\"," +
                "  error_type," +
                "  error_code " +
                "FROM system.runtime.queries";
        assertThat(onTrino().executeQuery(sql))
                .hasColumns(VARCHAR, VARCHAR, VARCHAR, VARCHAR, ARRAY,
                        BIGINT, BIGINT, BIGINT, TIMESTAMP_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE,
                        TIMESTAMP_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE, VARCHAR, VARCHAR)
                .hasAnyRows();
    }

    @Test(groups = {SYSTEM_CONNECTOR, JDBC})
    public void selectRuntimeTasks()
    {
        String sql = "SELECT" +
                "  node_id," +
                "  task_id," +
                "  stage_id," +
                "  query_id," +
                "  state," +
                "  splits," +
                "  queued_splits," +
                "  running_splits," +
                "  completed_splits," +
                "  split_scheduled_time_ms," +
                "  split_cpu_time_ms," +
                "  split_blocked_time_ms," +
                "  raw_input_bytes," +
                "  raw_input_rows," +
                "  processed_input_bytes," +
                "  processed_input_rows," +
                "  output_bytes," +
                "  output_rows," +
                "  physical_input_bytes," +
                "  physical_written_bytes," +
                "  created," +
                "  start," +
                "  last_heartbeat," +
                "  \"end\" " +
                "FROM SYSTEM.runtime.tasks";
        assertThat(onTrino().executeQuery(sql))
                .hasColumns(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR,
                        BIGINT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT,
                        BIGINT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT,
                        TIMESTAMP_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE)
                .hasAnyRows();
    }

    @Test(groups = {SYSTEM_CONNECTOR, JDBC})
    public void selectMetadataCatalogs()
    {
        String sql = "select catalog_name, connector_id, connector_name from system.metadata.catalogs";
        assertThat(onTrino().executeQuery(sql))
                .hasColumns(VARCHAR, VARCHAR, VARCHAR)
                .contains(
                        ImmutableList.of(
                                row("jmx", "jmx", "jmx"),
                                row("system", "system", "system"),
                                row("tpch", "tpch", "tpch")));
    }
}
