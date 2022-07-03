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
package io.trino.plugin.accumulo.conf;

import com.google.common.collect.ImmutableList;
import io.airlift.units.Duration;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.session.PropertyMetadata;

import javax.inject.Inject;

import java.util.List;

import static io.trino.plugin.base.session.PropertyMetadataUtil.durationProperty;
import static io.trino.spi.session.PropertyMetadata.booleanProperty;
import static io.trino.spi.session.PropertyMetadata.doubleProperty;
import static io.trino.spi.session.PropertyMetadata.integerProperty;
import static io.trino.spi.session.PropertyMetadata.stringProperty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Class contains all session-based properties for the Accumulo connector.
 * Use SHOW SESSION to view all available properties.
 * <p>
 * Can set the property using:
 * <p>
 * SET SESSION &lt;property&gt; = &lt;value&gt;;
 */
public final class AccumuloSessionProperties
{
    private static final String OPTIMIZE_LOCALITY_ENABLED = "optimize_locality_enabled";
    private static final String OPTIMIZE_SPLIT_RANGES_ENABLED = "optimize_split_ranges_enabled";
    private static final String OPTIMIZE_INDEX_ENABLED = "optimize_index_enabled";
    private static final String INDEX_ROWS_PER_SPLIT = "index_rows_per_split";
    private static final String INDEX_THRESHOLD = "index_threshold";
    private static final String INDEX_LOWEST_CARDINALITY_THRESHOLD = "index_lowest_cardinality_threshold";
    private static final String INDEX_METRICS_ENABLED = "index_metrics_enabled";
    private static final String SCAN_USERNAME = "scan_username";
    private static final String INDEX_SHORT_CIRCUIT_CARDINALITY_FETCH = "index_short_circuit_cardinality_fetch";
    private static final String INDEX_CARDINALITY_CACHE_POLLING_DURATION = "index_cardinality_cache_polling_duration";

    private final List<PropertyMetadata<?>> sessionProperties;

    @Inject
    public AccumuloSessionProperties()
    {
        sessionProperties = ImmutableList.of(
                booleanProperty(
                        OPTIMIZE_LOCALITY_ENABLED,
                        "Set to true to enable data locality for non-indexed scans. Default true.", true,
                        false),
                booleanProperty(
                        OPTIMIZE_SPLIT_RANGES_ENABLED,
                        "Set to true to split non-indexed queries by tablet splits. Should generally be true.",
                        true, false),
                stringProperty(
                        SCAN_USERNAME,
                        "User to impersonate when scanning the tables. This property trumps the scan_auths table property. Default is the user in the configuration file.", null, false),
                booleanProperty(
                        OPTIMIZE_INDEX_ENABLED,
                        "Set to true to enable usage of the secondary index on query. Default true.",
                        true,
                        false),
                integerProperty(
                        INDEX_ROWS_PER_SPLIT,
                        "The number of Accumulo row IDs that are packed into a single Trino split. Default 10000",
                        10000,
                        false),
                doubleProperty(
                        INDEX_THRESHOLD,
                        "The ratio between number of rows to be scanned based on the index over the total number of rows. If the ratio is below this threshold, the index will be used. Default .2",
                        0.2,
                        false),
                doubleProperty(
                        INDEX_LOWEST_CARDINALITY_THRESHOLD,
                        "The threshold where the column with the lowest cardinality will be used instead of computing an intersection of ranges in the secondary index. Secondary index must be enabled. Default .01",
                        0.01,
                        false),
                booleanProperty(
                        INDEX_METRICS_ENABLED,
                        "Set to true to enable usage of the metrics table to optimize usage of the index. Default true",
                        true,
                        false),
                booleanProperty(
                        INDEX_SHORT_CIRCUIT_CARDINALITY_FETCH,
                        "Short circuit the retrieval of index metrics once any column is less than the lowest cardinality threshold. Default true",
                        true,
                        false),
                durationProperty(
                        INDEX_CARDINALITY_CACHE_POLLING_DURATION,
                        "Sets the cardinality cache polling duration for short circuit retrieval of index metrics. Default 10ms",
                        new Duration(10, MILLISECONDS),
                        false));
    }

    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties;
    }

    public static boolean isOptimizeLocalityEnabled(ConnectorSession session)
    {
        return session.getProperty(OPTIMIZE_LOCALITY_ENABLED, Boolean.class);
    }

    public static boolean isOptimizeSplitRangesEnabled(ConnectorSession session)
    {
        return session.getProperty(OPTIMIZE_SPLIT_RANGES_ENABLED, Boolean.class);
    }

    public static boolean isOptimizeIndexEnabled(ConnectorSession session)
    {
        return session.getProperty(OPTIMIZE_INDEX_ENABLED, Boolean.class);
    }

    public static double getIndexThreshold(ConnectorSession session)
    {
        return session.getProperty(INDEX_THRESHOLD, Double.class);
    }

    public static int getNumIndexRowsPerSplit(ConnectorSession session)
    {
        return session.getProperty(INDEX_ROWS_PER_SPLIT, Integer.class);
    }

    public static double getIndexSmallCardThreshold(ConnectorSession session)
    {
        return session.getProperty(INDEX_LOWEST_CARDINALITY_THRESHOLD, Double.class);
    }

    public static Duration getIndexCardinalityCachePollingDuration(ConnectorSession session)
    {
        return session.getProperty(INDEX_CARDINALITY_CACHE_POLLING_DURATION, Duration.class);
    }

    public static boolean isIndexMetricsEnabled(ConnectorSession session)
    {
        return session.getProperty(INDEX_METRICS_ENABLED, Boolean.class);
    }

    public static String getScanUsername(ConnectorSession session)
    {
        return session.getProperty(SCAN_USERNAME, String.class);
    }

    public static boolean isIndexShortCircuitEnabled(ConnectorSession session)
    {
        return session.getProperty(INDEX_SHORT_CIRCUIT_CARDINALITY_FETCH, Boolean.class);
    }
}
