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
package io.trino.execution;

import com.google.common.collect.ImmutableMap;
import io.trino.Session.SessionBuilder;
import io.trino.execution.TestEventListenerPlugin.TestingEventListenerPlugin;
import io.trino.execution.warnings.WarningCollectorConfig;
import io.trino.spi.TrinoWarning;
import io.trino.spi.WarningCode;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingWarningCollector;
import io.trino.testing.TestingWarningCollectorConfig;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestCompletedEventWarnings
{
    private static final int EXPECTED_EVENTS = 3;
    private static final int TEST_WARNINGS = 5;
    private QueryRunner queryRunner;
    private EventsCollector generatedEvents;

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        SessionBuilder sessionBuilder = testSessionBuilder();
        generatedEvents = new EventsCollector();
        queryRunner = DistributedQueryRunner.builder(sessionBuilder.build())
                .setExtraProperties(ImmutableMap.of("testing-warning-collector.preloaded-warnings", String.valueOf(TEST_WARNINGS)))
                .setNodeCount(1)
                .build();
        queryRunner.installPlugin(new TestingEventListenerPlugin(generatedEvents));
        generatedEvents.reset(EXPECTED_EVENTS);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        queryRunner.close();
        queryRunner = null;
        generatedEvents = null;
    }

    @Test
    public void testCompletedEventWarnings()
            throws InterruptedException
    {
        TestingWarningCollectorConfig warningCollectorConfig = new TestingWarningCollectorConfig().setPreloadedWarnings(TEST_WARNINGS);
        TestingWarningCollector testingWarningCollector = new TestingWarningCollector(new WarningCollectorConfig(), warningCollectorConfig);
        assertWarnings(
                "select 1",
                ImmutableMap.of(),
                testingWarningCollector.getWarnings().stream()
                        .map(TrinoWarning::getWarningCode)
                        .collect(toImmutableList()));
    }

    private void assertWarnings(@Language("SQL") String sql, Map<String, String> sessionProperties, List<WarningCode> expectedWarnings)
            throws InterruptedException
    {
        // Task concurrency must be 1 otherwise these tests fail due to change in the number of EXPECTED_EVENTS
        SessionBuilder sessionBuilder = testSessionBuilder()
                .setSystemProperty("task_concurrency", "1");
        sessionProperties.forEach(sessionBuilder::setSystemProperty);
        queryRunner.execute(sessionBuilder.build(), sql);
        generatedEvents.waitForEvents(10);

        Set<WarningCode> warnings = getOnlyElement(generatedEvents.getQueryCompletedEvents())
                .getWarnings()
                .stream()
                .map(TrinoWarning::getWarningCode)
                .collect(toImmutableSet());
        for (WarningCode warningCode : expectedWarnings) {
            if (!warnings.contains(warningCode)) {
                fail("Expected warning: " + warningCode);
            }
        }
    }
}
