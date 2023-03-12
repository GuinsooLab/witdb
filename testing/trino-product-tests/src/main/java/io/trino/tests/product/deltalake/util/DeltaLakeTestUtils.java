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
package io.trino.tests.product.deltalake.util;

import com.amazonaws.services.glue.model.ConcurrentModificationException;
import com.google.common.base.Throwables;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.airlift.log.Logger;
import io.trino.tempto.query.QueryResult;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.intellij.lang.annotations.Language;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static io.trino.tests.product.utils.QueryExecutors.onDelta;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;

public final class DeltaLakeTestUtils
{
    private static final Logger log = Logger.get(DeltaLakeTestUtils.class);

    public static final String DATABRICKS_COMMUNICATION_FAILURE_ISSUE = "https://github.com/trinodb/trino/issues/14391";
    @Language("RegExp")
    public static final String DATABRICKS_COMMUNICATION_FAILURE_MATCH =
            "\\Q[Databricks][DatabricksJDBCDriver](500593) Communication link failure. Failed to connect to server. Reason: HTTP retry after response received with no Retry-After header, error: HTTP Response code: 503, Error message: Unknown.";
    private static final RetryPolicy<QueryResult> CONCURRENT_MODIFICATION_EXCEPTION_RETRY_POLICY = RetryPolicy.<QueryResult>builder()
            .handleIf(throwable -> Throwables.getRootCause(throwable) instanceof ConcurrentModificationException)
            .handleIf(throwable -> Throwables.getRootCause(throwable) instanceof MetaException metaException && metaException.getMessage() != null && metaException.getMessage().contains("Table being modified concurrently"))
            .withBackoff(1, 10, ChronoUnit.SECONDS)
            .withMaxRetries(3)
            .onRetry(event -> log.warn(event.getLastException(), "Query failed on attempt %d, will retry.", event.getAttemptCount()))
            .build();

    private DeltaLakeTestUtils() {}

    public static Optional<DatabricksVersion> getDatabricksRuntimeVersion()
    {
        String version = (String) onDelta().executeQuery("SELECT java_method('java.lang.System', 'getenv', 'DATABRICKS_RUNTIME_VERSION')").getOnlyValue();
        // OSS Spark returns null
        if (version.equals("null")) {
            return Optional.empty();
        }
        return Optional.of(DatabricksVersion.parse(version));
    }

    public static String getColumnCommentOnTrino(String schemaName, String tableName, String columnName)
    {
        return (String) onTrino()
                .executeQuery("SELECT comment FROM information_schema.columns WHERE table_schema = '" + schemaName + "' AND table_name = '" + tableName + "' AND column_name = '" + columnName + "'")
                .getOnlyValue();
    }

    public static String getColumnCommentOnDelta(String schemaName, String tableName, String columnName)
    {
        QueryResult result = onDelta().executeQuery(format("DESCRIBE %s.%s %s", schemaName, tableName, columnName));
        return (String) result.row(2).get(1);
    }

    public static String getTableCommentOnDelta(String schemaName, String tableName)
    {
        QueryResult result = onDelta().executeQuery(format("DESCRIBE EXTENDED %s.%s", schemaName, tableName));
        return (String) result.rows().stream()
                .filter(row -> row.get(0).equals("Comment"))
                .map(row -> row.get(1))
                .collect(onlyElement());
    }

    /**
     * Workaround method to avoid <a href="https://github.com/trinodb/trino/issues/13199">Table being modified concurrently error in Glue</a>.
     */
    public static QueryResult dropDeltaTableWithRetry(String tableName)
    {
        return Failsafe.with(CONCURRENT_MODIFICATION_EXCEPTION_RETRY_POLICY)
                .get(() -> onDelta().executeQuery("DROP TABLE IF EXISTS " + tableName));
    }
}
