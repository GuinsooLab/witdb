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
package io.trino.plugin.jmx;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.Test;

import java.util.List;

import static java.lang.management.ManagementFactory.getPlatformMBeanServer;
import static java.util.Locale.ENGLISH;
import static org.testng.Assert.assertEquals;

public class TestJmxHistoricalData
{
    private static final String TABLE_NAME = "java.lang:type=classloading";
    private static final String NOT_EXISTING_TABLE_NAME = "not-existing-test";
    private static final int MAX_ENTRIES = 2;

    @Test
    public void testAddingRows()
    {
        JmxHistoricalData jmxHistoricalData = new JmxHistoricalData(MAX_ENTRIES, ImmutableSet.of(TABLE_NAME), getPlatformMBeanServer());

        List<Integer> bothColumns = ImmutableList.of(0, 1);
        List<Integer> secondColumn = ImmutableList.of(1);

        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, bothColumns), ImmutableList.of());
        jmxHistoricalData.addRow(TABLE_NAME, ImmutableList.of(42, "ala"));
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, bothColumns), ImmutableList.of(ImmutableList.<Object>of(42, "ala")));
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, secondColumn), ImmutableList.of(ImmutableList.<Object>of("ala")));
        assertEquals(jmxHistoricalData.getRows(NOT_EXISTING_TABLE_NAME, bothColumns), ImmutableList.of());

        jmxHistoricalData.addRow(TABLE_NAME, ImmutableList.of(42, "ala"));
        jmxHistoricalData.addRow(TABLE_NAME, ImmutableList.of(42, "ala"));
        jmxHistoricalData.addRow(TABLE_NAME, ImmutableList.of(42, "ala"));
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, bothColumns).size(), MAX_ENTRIES);
    }

    @Test
    public void testCaseInsensitive()
    {
        JmxHistoricalData jmxHistoricalData = new JmxHistoricalData(MAX_ENTRIES, ImmutableSet.of(TABLE_NAME.toUpperCase(ENGLISH)), getPlatformMBeanServer());

        List<Integer> columns = ImmutableList.of(0);
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, columns), ImmutableList.of());
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME.toUpperCase(ENGLISH), columns), ImmutableList.of());

        jmxHistoricalData.addRow(TABLE_NAME, ImmutableList.of(42));
        jmxHistoricalData.addRow(TABLE_NAME.toUpperCase(ENGLISH), ImmutableList.of(44));

        assertEquals(jmxHistoricalData.getRows(TABLE_NAME, columns), ImmutableList.of(
                ImmutableList.<Object>of(42), ImmutableList.<Object>of(44)));
        assertEquals(jmxHistoricalData.getRows(TABLE_NAME.toUpperCase(ENGLISH), columns), ImmutableList.of(
                ImmutableList.<Object>of(42), ImmutableList.<Object>of(44)));
    }

    @Test
    public void testWildCardPatterns()
    {
        JmxHistoricalData jmxHistoricalData = new JmxHistoricalData(MAX_ENTRIES, ImmutableSet.of("java.lang:type=c*"), getPlatformMBeanServer());

        assertEquals(jmxHistoricalData.getTables(), ImmutableSet.of("java.lang:type=classloading", "java.lang:type=compilation"));
    }
}
