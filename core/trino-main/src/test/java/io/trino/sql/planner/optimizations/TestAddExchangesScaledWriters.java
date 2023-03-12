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

package io.trino.sql.planner.optimizations;

import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.connector.MockConnectorFactory;
import io.trino.plugin.tpch.TpchConnectorFactory;
import io.trino.sql.planner.LogicalPlanner;
import io.trino.sql.planner.SubPlan;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.testing.LocalQueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.trino.sql.planner.SystemPartitioningHandle.SCALED_WRITER_ROUND_ROBIN_DISTRIBUTION;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestAddExchangesScaledWriters
        extends BasePlanTest
{
    @Override
    protected LocalQueryRunner createLocalQueryRunner()
    {
        Session session = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("tiny")
                .build();
        LocalQueryRunner queryRunner = LocalQueryRunner.create(session);
        queryRunner.createCatalog("tpch", new TpchConnectorFactory(1), ImmutableMap.of());
        queryRunner.createCatalog("mock_dont_report_written_bytes", createConnectorFactorySupportingReportingBytesWritten(false, "mock_dont_report_written_bytes"), ImmutableMap.of());
        queryRunner.createCatalog("mock_report_written_bytes", createConnectorFactorySupportingReportingBytesWritten(true, "mock_report_written_bytes"), ImmutableMap.of());
        return queryRunner;
    }

    private MockConnectorFactory createConnectorFactorySupportingReportingBytesWritten(boolean supportsWrittenBytes, String name)
    {
        MockConnectorFactory connectorFactory = MockConnectorFactory.builder()
                .withSupportsReportingWrittenBytes(supportsWrittenBytes)
                .withGetTableHandle(((session, schemaTableName) -> null))
                .withName(name)
                .build();
        return connectorFactory;
    }

    @DataProvider(name = "scale_writers")
    public Object[][] prepareScaledWritersOption()
    {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "scale_writers")
    public void testScaledWritersEnabled(boolean isScaleWritersEnabled)
    {
        Session session = testSessionBuilder()
                .setSystemProperty("scale_writers", Boolean.toString(isScaleWritersEnabled))
                .build();

        @Language("SQL")
        String query = "CREATE TABLE mock_report_written_bytes.mock.test AS SELECT * FROM tpch.tiny.nation";
        SubPlan subPlan = subplan(query, LogicalPlanner.Stage.OPTIMIZED_AND_VALIDATED, false, session);
        if (isScaleWritersEnabled) {
            assertThat(subPlan.getAllFragments().get(1).getPartitioning().getConnectorHandle()).isEqualTo(SCALED_WRITER_ROUND_ROBIN_DISTRIBUTION.getConnectorHandle());
        }
        else {
            subPlan.getAllFragments().forEach(
                    fragment -> assertThat(fragment.getPartitioning().getConnectorHandle()).isNotEqualTo(SCALED_WRITER_ROUND_ROBIN_DISTRIBUTION.getConnectorHandle()));
        }
    }

    @Test(dataProvider = "scale_writers")
    public void testScaledWritersDisabled(boolean isScaleWritersEnabled)
    {
        Session session = testSessionBuilder()
                .setSystemProperty("scale_writers", Boolean.toString(isScaleWritersEnabled))
                .build();

        @Language("SQL")
        String query = "CREATE TABLE mock_dont_report_written_bytes.mock.test AS SELECT * FROM tpch.tiny.nation";
        SubPlan subPlan = subplan(query, LogicalPlanner.Stage.OPTIMIZED_AND_VALIDATED, false, session);
        subPlan.getAllFragments().forEach(
                fragment -> assertThat(fragment.getPartitioning().getConnectorHandle()).isNotEqualTo(SCALED_WRITER_ROUND_ROBIN_DISTRIBUTION.getConnectorHandle()));
    }
}
