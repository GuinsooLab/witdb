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
package io.trino.cost;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.Expression;
import io.trino.transaction.TestingTransactionManager;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Consumer;

import static io.trino.SystemSessionProperties.FILTER_CONJUNCTION_INDEPENDENCE_FACTOR;
import static io.trino.sql.ExpressionTestUtils.planExpression;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.sql.planner.iterative.rule.test.PlanBuilder.expression;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.lang.Double.NEGATIVE_INFINITY;
import static java.lang.Double.NaN;
import static java.lang.Double.POSITIVE_INFINITY;
import static java.lang.String.format;

public class TestFilterStatsCalculator
{
    private static final VarcharType MEDIUM_VARCHAR_TYPE = VarcharType.createVarcharType(100);

    private SymbolStatsEstimate xStats;
    private SymbolStatsEstimate yStats;
    private SymbolStatsEstimate zStats;
    private SymbolStatsEstimate leftOpenStats;
    private SymbolStatsEstimate rightOpenStats;
    private SymbolStatsEstimate unknownRangeStats;
    private SymbolStatsEstimate emptyRangeStats;
    private SymbolStatsEstimate mediumVarcharStats;
    private FilterStatsCalculator statsCalculator;
    private PlanNodeStatsEstimate standardInputStatistics;
    private PlanNodeStatsEstimate zeroStatistics;
    private TypeProvider standardTypes;
    private Session session;

    @BeforeClass
    public void setUp()
    {
        xStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(40.0)
                .setLowValue(-10.0)
                .setHighValue(10.0)
                .setNullsFraction(0.25)
                .build();
        yStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(20.0)
                .setLowValue(0.0)
                .setHighValue(5.0)
                .setNullsFraction(0.5)
                .build();
        zStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(5.0)
                .setLowValue(-100.0)
                .setHighValue(100.0)
                .setNullsFraction(0.1)
                .build();
        leftOpenStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(50.0)
                .setLowValue(NEGATIVE_INFINITY)
                .setHighValue(15.0)
                .setNullsFraction(0.1)
                .build();
        rightOpenStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(50.0)
                .setLowValue(-15.0)
                .setHighValue(POSITIVE_INFINITY)
                .setNullsFraction(0.1)
                .build();
        unknownRangeStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(4.0)
                .setDistinctValuesCount(50.0)
                .setLowValue(NEGATIVE_INFINITY)
                .setHighValue(POSITIVE_INFINITY)
                .setNullsFraction(0.1)
                .build();
        emptyRangeStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(0.0)
                .setDistinctValuesCount(0.0)
                .setLowValue(NaN)
                .setHighValue(NaN)
                .setNullsFraction(NaN)
                .build();
        mediumVarcharStats = SymbolStatsEstimate.builder()
                .setAverageRowSize(85.0)
                .setDistinctValuesCount(165)
                .setLowValue(NEGATIVE_INFINITY)
                .setHighValue(POSITIVE_INFINITY)
                .setNullsFraction(0.34)
                .build();
        standardInputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), xStats)
                .addSymbolStatistics(new Symbol("y"), yStats)
                .addSymbolStatistics(new Symbol("z"), zStats)
                .addSymbolStatistics(new Symbol("leftOpen"), leftOpenStats)
                .addSymbolStatistics(new Symbol("rightOpen"), rightOpenStats)
                .addSymbolStatistics(new Symbol("unknownRange"), unknownRangeStats)
                .addSymbolStatistics(new Symbol("emptyRange"), emptyRangeStats)
                .addSymbolStatistics(new Symbol("mediumVarchar"), mediumVarcharStats)
                .setOutputRowCount(1000.0)
                .build();
        zeroStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("y"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("z"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("leftOpen"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("rightOpen"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("unknownRange"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("emptyRange"), SymbolStatsEstimate.zero())
                .addSymbolStatistics(new Symbol("mediumVarchar"), SymbolStatsEstimate.zero())
                .setOutputRowCount(0)
                .build();

        standardTypes = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
                .put(new Symbol("x"), DoubleType.DOUBLE)
                .put(new Symbol("y"), DoubleType.DOUBLE)
                .put(new Symbol("z"), DoubleType.DOUBLE)
                .put(new Symbol("leftOpen"), DoubleType.DOUBLE)
                .put(new Symbol("rightOpen"), DoubleType.DOUBLE)
                .put(new Symbol("unknownRange"), DoubleType.DOUBLE)
                .put(new Symbol("emptyRange"), DoubleType.DOUBLE)
                .put(new Symbol("mediumVarchar"), MEDIUM_VARCHAR_TYPE)
                .buildOrThrow());

        session = testSessionBuilder().build();
        statsCalculator = new FilterStatsCalculator(PLANNER_CONTEXT, new ScalarStatsCalculator(PLANNER_CONTEXT, createTestingTypeAnalyzer(PLANNER_CONTEXT)), new StatsNormalizer());
    }

    @Test
    public void testBooleanLiteralStats()
    {
        assertExpression("true").equalTo(standardInputStatistics);
        assertExpression("false").equalTo(zeroStatistics);
        assertExpression("CAST(NULL AS boolean)").equalTo(zeroStatistics);
    }

    @Test
    public void testComparison()
    {
        double lessThan3Rows = 487.5;
        assertExpression("x < 3e0")
                .outputRowsCount(lessThan3Rows)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-10)
                                .highValue(3)
                                .distinctValuesCount(26)
                                .nullsFraction(0.0));

        assertExpression("-x > -3e0")
                .outputRowsCount(lessThan3Rows);

        for (String minusThree : ImmutableList.of("DECIMAL '-3'", "-3e0", "(4e0-7e0)", "CAST(-3 AS DECIMAL(7,3))"/*, "CAST('1' AS BIGINT) - 4"*/)) {
            for (String xEquals : ImmutableList.of("x = %s", "%s = x", "COALESCE(x * CAST(NULL AS BIGINT), x) = %s", "%s = CAST(x AS DOUBLE)")) {
                assertExpression(format(xEquals, minusThree))
                        .outputRowsCount(18.75)
                        .symbolStats(new Symbol("x"), symbolAssert ->
                                symbolAssert.averageRowSize(4.0)
                                        .lowValue(-3)
                                        .highValue(-3)
                                        .distinctValuesCount(1)
                                        .nullsFraction(0.0));
            }

            for (String xLessThan : ImmutableList.of("x < %s", "%s > x", "%s > CAST(x AS DOUBLE)")) {
                assertExpression(format(xLessThan, minusThree))
                        .outputRowsCount(262.5)
                        .symbolStats(new Symbol("x"), symbolAssert ->
                                symbolAssert.averageRowSize(4.0)
                                        .lowValue(-10)
                                        .highValue(-3)
                                        .distinctValuesCount(14)
                                        .nullsFraction(0.0));
            }
        }
    }

    @Test
    public void testInequalityComparisonApproximation()
    {
        assertExpression("x > emptyRange").outputRowsCount(0);

        assertExpression("x > y + 20").outputRowsCount(0);
        assertExpression("x >= y + 20").outputRowsCount(0);
        assertExpression("x < y - 25").outputRowsCount(0);
        assertExpression("x <= y - 25").outputRowsCount(0);

        double nullsFractionY = 0.5;
        double inputRowCount = standardInputStatistics.getOutputRowCount();
        double nonNullRowCount = inputRowCount * (1 - nullsFractionY);
        SymbolStatsEstimate nonNullStatsX = xStats.mapNullsFraction(nullsFraction -> 0.0);
        assertExpression("x > y - 25")
                .outputRowsCount(nonNullRowCount)
                .symbolStats("x", symbolAssert -> symbolAssert.isEqualTo(nonNullStatsX));
        assertExpression("x >= y - 25")
                .outputRowsCount(nonNullRowCount)
                .symbolStats("x", symbolAssert -> symbolAssert.isEqualTo(nonNullStatsX));
        assertExpression("x < y + 20")
                .outputRowsCount(nonNullRowCount)
                .symbolStats("x", symbolAssert -> symbolAssert.isEqualTo(nonNullStatsX));
        assertExpression("x <= y + 20")
                .outputRowsCount(nonNullRowCount)
                .symbolStats("x", symbolAssert -> symbolAssert.isEqualTo(nonNullStatsX));
    }

    @Test
    public void testOrStats()
    {
        assertExpression("x < 0e0 OR x < DOUBLE '-7.5'")
                .outputRowsCount(375)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-10.0)
                                .highValue(0.0)
                                .distinctValuesCount(20.0)
                                .nullsFraction(0.0));

        assertExpression("x = 0e0 OR x = DOUBLE '-7.5'")
                .outputRowsCount(37.5)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-7.5)
                                .highValue(0.0)
                                .distinctValuesCount(2.0)
                                .nullsFraction(0.0));

        assertExpression("x = 1e0 OR x = 3e0")
                .outputRowsCount(37.5)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(1)
                                .highValue(3)
                                .distinctValuesCount(2)
                                .nullsFraction(0));

        assertExpression("x = 1e0 OR 'a' = 'b' OR x = 3e0")
                .outputRowsCount(37.5)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(1)
                                .highValue(3)
                                .distinctValuesCount(2)
                                .nullsFraction(0));

        assertExpression("x = 1e0 OR (CAST('b' AS VARCHAR(3)) IN (CAST('a' AS VARCHAR(3)), CAST('b' AS VARCHAR(3)))) OR x = 3e0")
                .equalTo(standardInputStatistics);
    }

    @Test
    public void testUnsupportedExpression()
    {
        assertExpression("sin(x)")
                .outputRowsCountUnknown();
        assertExpression("x = sin(x)")
                .outputRowsCountUnknown();
    }

    @Test
    public void testAndStats()
    {
        // unknown input
        assertExpression("x < 0e0 AND x < 1e0", PlanNodeStatsEstimate.unknown()).outputRowsCountUnknown();
        assertExpression("x < 0e0 AND y < 1e0", PlanNodeStatsEstimate.unknown()).outputRowsCountUnknown();
        // zeroStatistics input
        assertExpression("x < 0e0 AND x < 1e0", zeroStatistics).equalTo(zeroStatistics);
        assertExpression("x < 0e0 AND y < 1e0", zeroStatistics).equalTo(zeroStatistics);

        assertExpression("x < 0e0 AND x > 1e0").equalTo(zeroStatistics);

        assertExpression("x < 0e0 AND x > DOUBLE '-7.5'")
                .outputRowsCount(281.25)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-7.5)
                                .highValue(0.0)
                                .distinctValuesCount(15.0)
                                .nullsFraction(0.0));

        // Impossible, with symbol-to-expression comparisons
        assertExpression("x = (0e0 + 1e0) AND x = (0e0 + 3e0)")
                .outputRowsCount(0)
                .symbolStats(new Symbol("x"), SymbolStatsAssertion::emptyRange)
                .symbolStats(new Symbol("y"), SymbolStatsAssertion::emptyRange);

        // first argument unknown
        assertExpression("json_array_contains(JSON '[]', x) AND x < 0e0")
                .outputRowsCount(337.5)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.lowValue(-10)
                                .highValue(0)
                                .distinctValuesCount(20)
                                .nullsFraction(0));

        // second argument unknown
        assertExpression("x < 0e0 AND json_array_contains(JSON '[]', x)")
                .outputRowsCount(337.5)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.lowValue(-10)
                                .highValue(0)
                                .distinctValuesCount(20)
                                .nullsFraction(0));

        // both arguments unknown
        assertExpression("json_array_contains(JSON '[11]', x) AND json_array_contains(JSON '[13]', x)")
                .outputRowsCountUnknown();

        assertExpression("'a' IN ('b', 'c') AND unknownRange = 3e0")
                .outputRowsCount(0);

        assertExpression("CAST(NULL AS boolean) AND CAST(NULL AS boolean)").equalTo(zeroStatistics);
        assertExpression("CAST(NULL AS boolean) AND (x < 0e0 AND x > 1e0)").equalTo(zeroStatistics);

        Consumer<SymbolStatsAssertion> symbolAssertX = symbolAssert -> symbolAssert.averageRowSize(4.0)
                .lowValue(-5.0)
                .highValue(5.0)
                .distinctValuesCount(20.0)
                .nullsFraction(0.0);
        Consumer<SymbolStatsAssertion> symbolAssertY = symbolAssert -> symbolAssert.averageRowSize(4.0)
                .lowValue(1.0)
                .highValue(5.0)
                .distinctValuesCount(16.0)
                .nullsFraction(0.0);

        double inputRowCount = standardInputStatistics.getOutputRowCount();
        double filterSelectivityX = 0.375;
        double inequalityFilterSelectivityY = 0.4;
        assertExpression(
                "(x BETWEEN -5 AND 5) AND y > 1",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0").build())
                .outputRowsCount(filterSelectivityX * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssertY);

        assertExpression(
                "(x BETWEEN -5 AND 5) AND y > 1",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "1").build())
                .outputRowsCount(filterSelectivityX * inequalityFilterSelectivityY * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssertY);

        assertExpression(
                "(x BETWEEN -5 AND 5) AND y > 1",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(filterSelectivityX * (Math.pow(inequalityFilterSelectivityY, 0.5)) * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssertY);

        double nullFilterSelectivityY = 0.5;
        assertExpression(
                "(x BETWEEN -5 AND 5) AND y IS NULL",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "1").build())
                .outputRowsCount(filterSelectivityX * nullFilterSelectivityY * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssert -> symbolAssert.isEqualTo(SymbolStatsEstimate.zero()));

        assertExpression(
                "(x BETWEEN -5 AND 5) AND y IS NULL",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(filterSelectivityX * Math.pow(nullFilterSelectivityY, 0.5) * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssert -> symbolAssert.isEqualTo(SymbolStatsEstimate.zero()));

        assertExpression(
                "(x BETWEEN -5 AND 5) AND y IS NULL",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0").build())
                .outputRowsCount(filterSelectivityX * inputRowCount)
                .symbolStats("x", symbolAssertX)
                .symbolStats("y", symbolAssert -> symbolAssert.isEqualTo(SymbolStatsEstimate.zero()));

        assertExpression(
                "y < 1 AND 0 < y",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(100)
                .symbolStats("y", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(1.0)
                        .distinctValuesCount(4.0)
                        .nullsFraction(0.0));

        assertExpression(
                "x > 0 AND (y < 1 OR y > 2)",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(filterSelectivityX * (Math.pow(inequalityFilterSelectivityY, 0.5)) * inputRowCount)
                .symbolStats("x", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(10.0)
                        .distinctValuesCount(20.0)
                        .nullsFraction(0.0))
                .symbolStats("y", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(5.0)
                        .distinctValuesCount(16.0)
                        .nullsFraction(0.0));

        assertExpression(
                "x > 0 AND (x < 1 OR y > 1)",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(172.0)
                .symbolStats("x", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(10.0)
                        .distinctValuesCount(20.0)
                        .nullsFraction(0.0))
                .symbolStats("y", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(5.0)
                        .distinctValuesCount(20.0)
                        .nullsFraction(0.1053779069));

        assertExpression(
                "x IN (0, 1, 2) AND (x = 0 OR (x = 1 AND y = 1) OR (x = 2 AND y = 1))",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(20.373798)
                .symbolStats("x", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(2.0)
                        .distinctValuesCount(2.623798)
                        .nullsFraction(0.0))
                .symbolStats("y", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(5.0)
                        .distinctValuesCount(15.686298)
                        .nullsFraction(0.2300749269));

        assertExpression(
                "x > 0 AND CAST(NULL AS boolean)",
                Session.builder(session).setSystemProperty(FILTER_CONJUNCTION_INDEPENDENCE_FACTOR, "0.5").build())
                .outputRowsCount(filterSelectivityX * inputRowCount * 0.9)
                .symbolStats("x", symbolAssert -> symbolAssert.averageRowSize(4.0)
                        .lowValue(0.0)
                        .highValue(10.0)
                        .distinctValuesCount(20.0)
                        .nullsFraction(0.0));
    }

    @Test
    public void testNotStats()
    {
        assertExpression("NOT(x < 0e0)")
                .outputRowsCount(625) // FIXME - nulls shouldn't be restored
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-10.0)
                                .highValue(10.0)
                                .distinctValuesCount(20.0)
                                .nullsFraction(0.4)) // FIXME - nulls shouldn't be restored
                .symbolStats(new Symbol("y"), symbolAssert -> symbolAssert.isEqualTo(yStats));

        assertExpression("NOT(x IS NULL)")
                .outputRowsCount(750)
                .symbolStats(new Symbol("x"), symbolAssert ->
                        symbolAssert.averageRowSize(4.0)
                                .lowValue(-10.0)
                                .highValue(10.0)
                                .distinctValuesCount(40.0)
                                .nullsFraction(0))
                .symbolStats(new Symbol("y"), symbolAssert -> symbolAssert.isEqualTo(yStats));

        assertExpression("NOT(json_array_contains(JSON '[]', x))")
                .outputRowsCountUnknown();
    }

    @Test
    public void testIsNullFilter()
    {
        assertExpression("x IS NULL")
                .outputRowsCount(250.0)
                .symbolStats(new Symbol("x"), symbolStats ->
                        symbolStats.distinctValuesCount(0)
                                .emptyRange()
                                .nullsFraction(1.0));

        assertExpression("emptyRange IS NULL")
                .outputRowsCount(1000.0)
                .symbolStats(new Symbol("emptyRange"), SymbolStatsAssertion::empty);
    }

    @Test
    public void testIsNotNullFilter()
    {
        assertExpression("x IS NOT NULL")
                .outputRowsCount(750.0)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(40.0)
                                .lowValue(-10.0)
                                .highValue(10.0)
                                .nullsFraction(0.0));

        assertExpression("emptyRange IS NOT NULL")
                .outputRowsCount(0.0)
                .symbolStats("emptyRange", SymbolStatsAssertion::empty);
    }

    @Test
    public void testBetweenOperatorFilter()
    {
        // Only right side cut
        assertExpression("x BETWEEN 7.5e0 AND 12e0")
                .outputRowsCount(93.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(7.5)
                                .highValue(10.0)
                                .nullsFraction(0.0));

        // Only left side cut
        assertExpression("x BETWEEN DOUBLE '-12' AND DOUBLE '-7.5'")
                .outputRowsCount(93.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(-10)
                                .highValue(-7.5)
                                .nullsFraction(0.0));
        assertExpression("x BETWEEN -12e0 AND -7.5e0")
                .outputRowsCount(93.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(-10)
                                .highValue(-7.5)
                                .nullsFraction(0.0));

        // Both sides cut
        assertExpression("x BETWEEN DOUBLE '-2.5' AND 2.5e0")
                .outputRowsCount(187.5)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(10.0)
                                .lowValue(-2.5)
                                .highValue(2.5)
                                .nullsFraction(0.0));

        // Both sides cut unknownRange
        assertExpression("unknownRange BETWEEN 2.72e0 AND 3.14e0")
                .outputRowsCount(112.5)
                .symbolStats("unknownRange", symbolStats ->
                        symbolStats.distinctValuesCount(6.25)
                                .lowValue(2.72)
                                .highValue(3.14)
                                .nullsFraction(0.0));

        // Left side open, cut on open side
        assertExpression("leftOpen BETWEEN DOUBLE '-10' AND 10e0")
                .outputRowsCount(180.0)
                .symbolStats("leftOpen", symbolStats ->
                        symbolStats.distinctValuesCount(10.0)
                                .lowValue(-10.0)
                                .highValue(10.0)
                                .nullsFraction(0.0));

        // Right side open, cut on open side
        assertExpression("rightOpen BETWEEN DOUBLE '-10' AND 10e0")
                .outputRowsCount(180.0)
                .symbolStats("rightOpen", symbolStats ->
                        symbolStats.distinctValuesCount(10.0)
                                .lowValue(-10.0)
                                .highValue(10.0)
                                .nullsFraction(0.0));

        // Filter all
        assertExpression("y BETWEEN 27.5e0 AND 107e0")
                .outputRowsCount(0.0)
                .symbolStats("y", SymbolStatsAssertion::empty);

        // Filter nothing
        assertExpression("y BETWEEN DOUBLE '-100' AND 100e0")
                .outputRowsCount(500.0)
                .symbolStats("y", symbolStats ->
                        symbolStats.distinctValuesCount(20.0)
                                .lowValue(0.0)
                                .highValue(5.0)
                                .nullsFraction(0.0));

        // Filter non exact match
        assertExpression("z BETWEEN DOUBLE '-100' AND 100e0")
                .outputRowsCount(900.0)
                .symbolStats("z", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(-100.0)
                                .highValue(100.0)
                                .nullsFraction(0.0));

        assertExpression("'a' IN ('a', 'b')").equalTo(standardInputStatistics);
        assertExpression("'a' IN ('b', 'c')").outputRowsCount(0);
        assertExpression("CAST('b' AS VARCHAR(3)) IN (CAST('a' AS VARCHAR(3)), CAST('b' AS VARCHAR(3)))").equalTo(standardInputStatistics);
        assertExpression("CAST('c' AS VARCHAR(3)) IN (CAST('a' AS VARCHAR(3)), CAST('b' AS VARCHAR(3)))").outputRowsCount(0);
    }

    @Test
    public void testSymbolEqualsSameSymbolFilter()
    {
        assertExpression("x = x")
                .outputRowsCount(750)
                .symbolStats("x", symbolStats ->
                        SymbolStatsEstimate.builder()
                                .setAverageRowSize(4.0)
                                .setDistinctValuesCount(40.0)
                                .setLowValue(-10.0)
                                .setHighValue(10.0)
                                .build());
    }

    @Test
    public void testInPredicateFilter()
    {
        // One value in range
        assertExpression("x IN (7.5e0)")
                .outputRowsCount(18.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(1.0)
                                .lowValue(7.5)
                                .highValue(7.5)
                                .nullsFraction(0.0));
        assertExpression("x IN (DOUBLE '-7.5')")
                .outputRowsCount(18.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(1.0)
                                .lowValue(-7.5)
                                .highValue(-7.5)
                                .nullsFraction(0.0));
        assertExpression("x IN (BIGINT '2' + 5.5e0)")
                .outputRowsCount(18.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(1.0)
                                .lowValue(7.5)
                                .highValue(7.5)
                                .nullsFraction(0.0));
        assertExpression("x IN (-7.5e0)")
                .outputRowsCount(18.75)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(1.0)
                                .lowValue(-7.5)
                                .highValue(-7.5)
                                .nullsFraction(0.0));

        // Multiple values in range
        assertExpression("x IN (1.5e0, 2.5e0, 7.5e0)")
                .outputRowsCount(56.25)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(3.0)
                                .lowValue(1.5)
                                .highValue(7.5)
                                .nullsFraction(0.0))
                .symbolStats("y", symbolStats ->
                        // Symbol not involved in the comparison should have stats basically unchanged
                        symbolStats.distinctValuesCount(20.0)
                                .lowValue(0.0)
                                .highValue(5)
                                .nullsFraction(0.5));

        // Multiple values some in some out of range
        assertExpression("x IN (DOUBLE '-42', 1.5e0, 2.5e0, 7.5e0, 314e0)")
                .outputRowsCount(56.25)
                .symbolStats("x", symbolStats ->
                        symbolStats.distinctValuesCount(3.0)
                                .lowValue(1.5)
                                .highValue(7.5)
                                .nullsFraction(0.0));

        // Multiple values in unknown range
        assertExpression("unknownRange IN (DOUBLE '-42', 1.5e0, 2.5e0, 7.5e0, 314e0)")
                .outputRowsCount(90.0)
                .symbolStats("unknownRange", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(-42.0)
                                .highValue(314.0)
                                .nullsFraction(0.0));

        // Casted literals as value
        assertExpression(format("mediumVarchar IN (CAST('abc' AS %s))", MEDIUM_VARCHAR_TYPE))
                .outputRowsCount(4)
                .symbolStats("mediumVarchar", symbolStats ->
                        symbolStats.distinctValuesCount(1)
                                .nullsFraction(0.0));

        assertExpression(format("mediumVarchar IN (CAST('abc' AS %1$s), CAST('def' AS %1$s))", MEDIUM_VARCHAR_TYPE))
                .outputRowsCount(8)
                .symbolStats("mediumVarchar", symbolStats ->
                        symbolStats.distinctValuesCount(2)
                                .nullsFraction(0.0));

        // No value in range
        assertExpression("y IN (DOUBLE '-42', 6e0, 31.1341e0, DOUBLE '-0.000000002', 314e0)")
                .outputRowsCount(0.0)
                .symbolStats("y", SymbolStatsAssertion::empty);

        // More values in range than distinct values
        assertExpression("z IN (DOUBLE '-1', 3.14e0, 0e0, 1e0, 2e0, 3e0, 4e0, 5e0, 6e0, 7e0, 8e0, DOUBLE '-2')")
                .outputRowsCount(900.0)
                .symbolStats("z", symbolStats ->
                        symbolStats.distinctValuesCount(5.0)
                                .lowValue(-2.0)
                                .highValue(8.0)
                                .nullsFraction(0.0));

        // Values in weird order
        assertExpression("z IN (DOUBLE '-1', 1e0, 0e0)")
                .outputRowsCount(540.0)
                .symbolStats("z", symbolStats ->
                        symbolStats.distinctValuesCount(3.0)
                                .lowValue(-1.0)
                                .highValue(1.0)
                                .nullsFraction(0.0));
    }

    private PlanNodeStatsAssertion assertExpression(String expression)
    {
        return assertExpression(expression, session);
    }

    private PlanNodeStatsAssertion assertExpression(String expression, PlanNodeStatsEstimate inputStatistics)
    {
        return assertExpression(planExpression(PLANNER_CONTEXT, session, standardTypes, expression(expression)), session, inputStatistics);
    }

    private PlanNodeStatsAssertion assertExpression(String expression, Session session)
    {
        return assertExpression(planExpression(PLANNER_CONTEXT, session, standardTypes, expression(expression)), session, standardInputStatistics);
    }

    private PlanNodeStatsAssertion assertExpression(Expression expression, Session session, PlanNodeStatsEstimate inputStatistics)
    {
        return transaction(new TestingTransactionManager(), new AllowAllAccessControl())
                .singleStatement()
                .execute(session, transactionSession -> {
                    return PlanNodeStatsAssertion.assertThat(statsCalculator.filterStats(
                            inputStatistics,
                            expression,
                            transactionSession,
                            standardTypes));
                });
    }
}
