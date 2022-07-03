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
import io.trino.spi.type.Type;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.JoinNode.EquiJoinClause;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.LongLiteral;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.function.Function;

import static io.trino.SystemSessionProperties.JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR;
import static io.trino.cost.FilterStatsCalculator.UNKNOWN_FILTER_COEFFICIENT;
import static io.trino.cost.PlanNodeStatsAssertion.assertThat;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.sql.planner.plan.JoinNode.Type.FULL;
import static io.trino.sql.planner.plan.JoinNode.Type.INNER;
import static io.trino.sql.planner.plan.JoinNode.Type.LEFT;
import static io.trino.sql.planner.plan.JoinNode.Type.RIGHT;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.Double.NaN;
import static org.testng.Assert.assertEquals;

public class TestJoinStatsRule
        extends BaseStatsCalculatorTest
{
    private static final String LEFT_JOIN_COLUMN = "left_join_column";
    private static final String LEFT_JOIN_COLUMN_2 = "left_join_column_2";
    private static final String RIGHT_JOIN_COLUMN = "right_join_column";
    private static final String RIGHT_JOIN_COLUMN_2 = "right_join_column_2";
    private static final String LEFT_OTHER_COLUMN = "left_column";
    private static final String RIGHT_OTHER_COLUMN = "right_column";

    private static final double LEFT_ROWS_COUNT = 500.0;
    private static final double RIGHT_ROWS_COUNT = 1000.0;
    private static final double TOTAL_ROWS_COUNT = LEFT_ROWS_COUNT + RIGHT_ROWS_COUNT;
    private static final double LEFT_JOIN_COLUMN_NULLS = 0.3;
    private static final double LEFT_JOIN_COLUMN_2_NULLS = 0.4;
    private static final double LEFT_JOIN_COLUMN_NON_NULLS = 0.7;
    private static final double LEFT_JOIN_COLUMN_2_NON_NULLS = 1 - LEFT_JOIN_COLUMN_2_NULLS;
    private static final int LEFT_JOIN_COLUMN_NDV = 20;
    private static final int LEFT_JOIN_COLUMN_2_NDV = 50;
    private static final double RIGHT_JOIN_COLUMN_NULLS = 0.6;
    private static final double RIGHT_JOIN_COLUMN_2_NULLS = 0.8;
    private static final double RIGHT_JOIN_COLUMN_NON_NULLS = 0.4;
    private static final double RIGHT_JOIN_COLUMN_2_NON_NULLS = 1 - RIGHT_JOIN_COLUMN_2_NULLS;
    private static final int RIGHT_JOIN_COLUMN_NDV = 15;
    private static final int RIGHT_JOIN_COLUMN_2_NDV = 15;

    private static final SymbolStatistics LEFT_JOIN_COLUMN_STATS =
            symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, LEFT_JOIN_COLUMN_NULLS, LEFT_JOIN_COLUMN_NDV);
    private static final SymbolStatistics LEFT_JOIN_COLUMN_2_STATS =
            symbolStatistics(LEFT_JOIN_COLUMN_2, 0.0, 200.0, LEFT_JOIN_COLUMN_2_NULLS, LEFT_JOIN_COLUMN_2_NDV);
    private static final SymbolStatistics LEFT_OTHER_COLUMN_STATS =
            symbolStatistics(LEFT_OTHER_COLUMN, 42, 42, 0.42, 1);
    private static final SymbolStatistics RIGHT_JOIN_COLUMN_STATS =
            symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, RIGHT_JOIN_COLUMN_NULLS, RIGHT_JOIN_COLUMN_NDV);
    private static final SymbolStatistics RIGHT_JOIN_COLUMN_2_STATS =
            symbolStatistics(RIGHT_JOIN_COLUMN_2, 100.0, 200.0, RIGHT_JOIN_COLUMN_2_NULLS, RIGHT_JOIN_COLUMN_2_NDV);
    private static final SymbolStatistics RIGHT_OTHER_COLUMN_STATS =
            symbolStatistics(RIGHT_OTHER_COLUMN, 24, 24, 0.24, 1);
    private static final PlanNodeStatsEstimate LEFT_STATS = planNodeStats(LEFT_ROWS_COUNT,
            LEFT_JOIN_COLUMN_STATS,
            LEFT_OTHER_COLUMN_STATS);
    private static final PlanNodeStatsEstimate RIGHT_STATS = planNodeStats(RIGHT_ROWS_COUNT,
            RIGHT_JOIN_COLUMN_STATS,
            RIGHT_OTHER_COLUMN_STATS);

    private static final StatsNormalizer NORMALIZER = new StatsNormalizer();
    private static final JoinStatsRule JOIN_STATS_RULE = new JoinStatsRule(
            new FilterStatsCalculator(PLANNER_CONTEXT, new ScalarStatsCalculator(PLANNER_CONTEXT, createTestingTypeAnalyzer(PLANNER_CONTEXT)), NORMALIZER),
            NORMALIZER,
            1.0);
    private static final TypeProvider TYPES = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(new Symbol(LEFT_JOIN_COLUMN), BIGINT)
            .put(new Symbol(LEFT_JOIN_COLUMN_2), DOUBLE)
            .put(new Symbol(RIGHT_JOIN_COLUMN), BIGINT)
            .put(new Symbol(RIGHT_JOIN_COLUMN_2), DOUBLE)
            .put(new Symbol(LEFT_OTHER_COLUMN), DOUBLE)
            .put(new Symbol(RIGHT_OTHER_COLUMN), BIGINT)
            .buildOrThrow());

    @Test
    public void testStatsForInnerJoin()
    {
        double innerJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        PlanNodeStatsEstimate innerJoinStats = planNodeStats(
                innerJoinRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                LEFT_OTHER_COLUMN_STATS, RIGHT_OTHER_COLUMN_STATS);

        assertJoinStats(INNER, LEFT_STATS, RIGHT_STATS, innerJoinStats);
    }

    @Test
    public void testStatsForInnerJoinWithRepeatedClause()
    {
        double clauseSelectivity = 1.0 / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        double innerJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT * clauseSelectivity * Math.pow(clauseSelectivity, 0.5);
        PlanNodeStatsEstimate innerJoinStats = planNodeStats(
                innerJoinRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                LEFT_OTHER_COLUMN_STATS, RIGHT_OTHER_COLUMN_STATS);

        tester().assertStatsFor(
                        testSessionBuilder().setSystemProperty(JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR, "0.5").build(),
                        pb -> {
                            Symbol leftJoinColumnSymbol = pb.symbol(LEFT_JOIN_COLUMN, BIGINT);
                            Symbol rightJoinColumnSymbol = pb.symbol(RIGHT_JOIN_COLUMN, DOUBLE);
                            Symbol leftOtherColumnSymbol = pb.symbol(LEFT_OTHER_COLUMN, BIGINT);
                            Symbol rightOtherColumnSymbol = pb.symbol(RIGHT_OTHER_COLUMN, DOUBLE);
                            return pb.join(
                                    INNER,
                                    pb.values(leftJoinColumnSymbol, leftOtherColumnSymbol),
                                    pb.values(rightJoinColumnSymbol, rightOtherColumnSymbol),
                                    new EquiJoinClause(leftJoinColumnSymbol, rightJoinColumnSymbol), new EquiJoinClause(leftJoinColumnSymbol, rightJoinColumnSymbol));
                        })
                .withSourceStats(0, LEFT_STATS)
                .withSourceStats(1, RIGHT_STATS)
                .check(stats -> stats.equalTo(innerJoinStats));
    }

    @Test
    public void testStatsForInnerJoinWithTwoEquiClauses()
    {
        double crossJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT;
        PlanNodeStatsEstimate innerJoinStats = planNodeStats(crossJoinRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(LEFT_JOIN_COLUMN_2, 100.0, 200.0, 0.0, RIGHT_JOIN_COLUMN_2_NDV),
                symbolStatistics(RIGHT_JOIN_COLUMN_2, 100.0, 200.0, 0.0, RIGHT_JOIN_COLUMN_2_NDV));

        Function<PlanBuilder, PlanNode> planProvider = pb -> {
            Symbol leftJoinColumnSymbol = pb.symbol(LEFT_JOIN_COLUMN, BIGINT);
            Symbol rightJoinColumnSymbol = pb.symbol(RIGHT_JOIN_COLUMN, DOUBLE);
            Symbol leftJoinColumnSymbol2 = pb.symbol(LEFT_JOIN_COLUMN_2, BIGINT);
            Symbol rightJoinColumnSymbol2 = pb.symbol(RIGHT_JOIN_COLUMN_2, DOUBLE);
            return pb.join(
                    INNER,
                    pb.values(leftJoinColumnSymbol, leftJoinColumnSymbol2),
                    pb.values(rightJoinColumnSymbol, rightJoinColumnSymbol2),
                    new EquiJoinClause(leftJoinColumnSymbol2, rightJoinColumnSymbol2), new EquiJoinClause(leftJoinColumnSymbol, rightJoinColumnSymbol));
        };

        // LEFT_JOIN_COLUMN_2 = RIGHT_JOIN_COLUMN_2 is the more selective clause
        double firstClauseSelectivity = 1.0 / LEFT_JOIN_COLUMN_2_NDV * LEFT_JOIN_COLUMN_2_NON_NULLS * RIGHT_JOIN_COLUMN_2_NON_NULLS;
        tester().assertStatsFor(testSessionBuilder().setSystemProperty(JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR, "0").build(), planProvider)
                .withSourceStats(0, planNodeStats(LEFT_ROWS_COUNT, LEFT_JOIN_COLUMN_STATS, LEFT_JOIN_COLUMN_2_STATS))
                .withSourceStats(1, planNodeStats(RIGHT_ROWS_COUNT, RIGHT_JOIN_COLUMN_STATS, RIGHT_JOIN_COLUMN_2_STATS))
                .check(stats -> stats.equalTo(innerJoinStats.mapOutputRowCount(rowCount -> rowCount * firstClauseSelectivity)));

        double secondClauseSelectivity = 1.0 / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        tester().assertStatsFor(testSessionBuilder().setSystemProperty(JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR, "1").build(), planProvider)
                .withSourceStats(0, planNodeStats(LEFT_ROWS_COUNT, LEFT_JOIN_COLUMN_STATS, LEFT_JOIN_COLUMN_2_STATS))
                .withSourceStats(1, planNodeStats(RIGHT_ROWS_COUNT, RIGHT_JOIN_COLUMN_STATS, RIGHT_JOIN_COLUMN_2_STATS))
                .check(stats -> stats.equalTo(innerJoinStats.mapOutputRowCount(rowCount -> rowCount * firstClauseSelectivity * secondClauseSelectivity)));

        tester().assertStatsFor(testSessionBuilder().setSystemProperty(JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR, "0.5").build(), planProvider)
                .withSourceStats(0, planNodeStats(LEFT_ROWS_COUNT, LEFT_JOIN_COLUMN_STATS, LEFT_JOIN_COLUMN_2_STATS))
                .withSourceStats(1, planNodeStats(RIGHT_ROWS_COUNT, RIGHT_JOIN_COLUMN_STATS, RIGHT_JOIN_COLUMN_2_STATS))
                .check(stats -> stats.equalTo(innerJoinStats.mapOutputRowCount(
                                rowCount -> rowCount * firstClauseSelectivity * Math.pow(secondClauseSelectivity, 0.5))));
    }

    @Test
    public void testStatsForInnerJoinWithTwoEquiClausesAndNonEqualityFunction()
    {
        // LEFT_JOIN_COLUMN_2 = RIGHT_JOIN_COLUMN_2 is the more selective clause
        double firstClauseSelectivity = 1.0 / LEFT_JOIN_COLUMN_2_NDV * LEFT_JOIN_COLUMN_2_NON_NULLS * RIGHT_JOIN_COLUMN_2_NON_NULLS;
        double secondClauseSelectivity = 1.0 / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        double innerJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT * firstClauseSelectivity
                * Math.pow(secondClauseSelectivity, 0.5)
                * 0.3333333333; // LEFT_JOIN_COLUMN < 10 non equality filter
        PlanNodeStatsEstimate innerJoinStats = planNodeStats(innerJoinRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 5.0, 10.0, 0.0, RIGHT_JOIN_COLUMN_NDV * 0.3333333333),
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, 0.0, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(LEFT_JOIN_COLUMN_2, 100.0, 200.0, 0.0, RIGHT_JOIN_COLUMN_2_NDV),
                symbolStatistics(RIGHT_JOIN_COLUMN_2, 100.0, 200.0, 0.0, RIGHT_JOIN_COLUMN_2_NDV));

        tester().assertStatsFor(
                        testSessionBuilder().setSystemProperty(JOIN_MULTI_CLAUSE_INDEPENDENCE_FACTOR, "0.5").build(),
                        pb -> {
                            Symbol leftJoinColumnSymbol = pb.symbol(LEFT_JOIN_COLUMN, BIGINT);
                            Symbol rightJoinColumnSymbol = pb.symbol(RIGHT_JOIN_COLUMN, DOUBLE);
                            Symbol leftJoinColumnSymbol2 = pb.symbol(LEFT_JOIN_COLUMN_2, BIGINT);
                            Symbol rightJoinColumnSymbol2 = pb.symbol(RIGHT_JOIN_COLUMN_2, DOUBLE);
                            ComparisonExpression leftJoinColumnLessThanTen = new ComparisonExpression(ComparisonExpression.Operator.LESS_THAN, leftJoinColumnSymbol.toSymbolReference(), new LongLiteral("10"));
                            return pb.join(
                                    INNER,
                                    pb.values(leftJoinColumnSymbol, leftJoinColumnSymbol2),
                                    pb.values(rightJoinColumnSymbol, rightJoinColumnSymbol2),
                                    ImmutableList.of(new EquiJoinClause(leftJoinColumnSymbol2, rightJoinColumnSymbol2), new EquiJoinClause(leftJoinColumnSymbol, rightJoinColumnSymbol)),
                                    ImmutableList.of(leftJoinColumnSymbol, leftJoinColumnSymbol2),
                                    ImmutableList.of(rightJoinColumnSymbol, rightJoinColumnSymbol2),
                                    Optional.of(leftJoinColumnLessThanTen));
                        })
                .withSourceStats(0, planNodeStats(LEFT_ROWS_COUNT, LEFT_JOIN_COLUMN_STATS, LEFT_JOIN_COLUMN_2_STATS))
                .withSourceStats(1, planNodeStats(RIGHT_ROWS_COUNT, RIGHT_JOIN_COLUMN_STATS, RIGHT_JOIN_COLUMN_2_STATS))
                .check(stats -> stats.equalTo(innerJoinStats));
    }

    @Test
    public void testJoinComplementStats()
    {
        PlanNodeStatsEstimate expected = planNodeStats(
                LEFT_ROWS_COUNT * (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4),
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, LEFT_JOIN_COLUMN_NULLS / (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4), 5),
                LEFT_OTHER_COLUMN_STATS);
        PlanNodeStatsEstimate actual = JOIN_STATS_RULE.calculateJoinComplementStats(
                Optional.empty(),
                ImmutableList.of(new EquiJoinClause(new Symbol(LEFT_JOIN_COLUMN), new Symbol(RIGHT_JOIN_COLUMN))),
                LEFT_STATS,
                RIGHT_STATS,
                TYPES);
        assertEquals(actual, expected);
    }

    @Test
    public void testRightJoinComplementStats()
    {
        PlanNodeStatsEstimate expected = NORMALIZER.normalize(
                planNodeStats(
                        RIGHT_ROWS_COUNT * RIGHT_JOIN_COLUMN_NULLS,
                        symbolStatistics(RIGHT_JOIN_COLUMN, NaN, NaN, 1.0, 0),
                        RIGHT_OTHER_COLUMN_STATS),
                TYPES);
        PlanNodeStatsEstimate actual = JOIN_STATS_RULE.calculateJoinComplementStats(
                Optional.empty(),
                ImmutableList.of(new EquiJoinClause(new Symbol(RIGHT_JOIN_COLUMN), new Symbol(LEFT_JOIN_COLUMN))),
                RIGHT_STATS,
                LEFT_STATS,
                TYPES);
        assertEquals(actual, expected);
    }

    @Test
    public void testLeftJoinComplementStatsWithNoClauses()
    {
        PlanNodeStatsEstimate expected = NORMALIZER.normalize(LEFT_STATS.mapOutputRowCount(rowCount -> 0.0), TYPES);
        PlanNodeStatsEstimate actual = JOIN_STATS_RULE.calculateJoinComplementStats(
                Optional.empty(),
                ImmutableList.of(),
                LEFT_STATS,
                RIGHT_STATS,
                TYPES);
        assertEquals(actual, expected);
    }

    @Test
    public void testLeftJoinComplementStatsWithMultipleClauses()
    {
        PlanNodeStatsEstimate expected = planNodeStats(
                LEFT_ROWS_COUNT * (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4),
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, LEFT_JOIN_COLUMN_NULLS / (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4), 5),
                LEFT_OTHER_COLUMN_STATS)
                .mapOutputRowCount(rowCount -> rowCount / UNKNOWN_FILTER_COEFFICIENT);
        PlanNodeStatsEstimate actual = JOIN_STATS_RULE.calculateJoinComplementStats(
                Optional.empty(),
                ImmutableList.of(new EquiJoinClause(new Symbol(LEFT_JOIN_COLUMN), new Symbol(RIGHT_JOIN_COLUMN)), new EquiJoinClause(new Symbol(LEFT_OTHER_COLUMN), new Symbol(RIGHT_OTHER_COLUMN))),
                LEFT_STATS,
                RIGHT_STATS,
                TYPES);
        assertEquals(actual, expected);
    }

    @Test
    public void testStatsForLeftAndRightJoin()
    {
        double innerJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        double joinComplementRowCount = LEFT_ROWS_COUNT * (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4);
        double joinComplementColumnNulls = LEFT_JOIN_COLUMN_NULLS / (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4);
        double totalRowCount = innerJoinRowCount + joinComplementRowCount;

        PlanNodeStatsEstimate leftJoinStats = planNodeStats(
                totalRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, joinComplementColumnNulls * joinComplementRowCount / totalRowCount, LEFT_JOIN_COLUMN_NDV),
                LEFT_OTHER_COLUMN_STATS,
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, joinComplementRowCount / totalRowCount, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(RIGHT_OTHER_COLUMN, 24, 24, (0.24 * innerJoinRowCount + joinComplementRowCount) / totalRowCount, 1));

        assertJoinStats(LEFT, LEFT_STATS, RIGHT_STATS, leftJoinStats);
        assertJoinStats(RIGHT, RIGHT_JOIN_COLUMN, RIGHT_OTHER_COLUMN, LEFT_JOIN_COLUMN, LEFT_OTHER_COLUMN, RIGHT_STATS, LEFT_STATS, leftJoinStats);
    }

    @Test
    public void testLeftJoinMissingStats()
    {
        PlanNodeStatsEstimate leftStats = planNodeStats(
                1,
                new SymbolStatistics(LEFT_JOIN_COLUMN, SymbolStatsEstimate.unknown()),
                new SymbolStatistics(LEFT_OTHER_COLUMN, SymbolStatsEstimate.unknown()));
        PlanNodeStatsEstimate rightStats = planNodeStats(
                1,
                new SymbolStatistics(RIGHT_JOIN_COLUMN, SymbolStatsEstimate.unknown()),
                new SymbolStatistics(RIGHT_OTHER_COLUMN, SymbolStatsEstimate.unknown()));
        assertJoinStats(LEFT, leftStats, rightStats, PlanNodeStatsEstimate.unknown());
    }

    @Test
    public void testStatsForFullJoin()
    {
        double innerJoinRowCount = LEFT_ROWS_COUNT * RIGHT_ROWS_COUNT / LEFT_JOIN_COLUMN_NDV * LEFT_JOIN_COLUMN_NON_NULLS * RIGHT_JOIN_COLUMN_NON_NULLS;
        double leftJoinComplementRowCount = LEFT_ROWS_COUNT * (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4);
        double leftJoinComplementColumnNulls = LEFT_JOIN_COLUMN_NULLS / (LEFT_JOIN_COLUMN_NULLS + LEFT_JOIN_COLUMN_NON_NULLS / 4);
        double rightJoinComplementRowCount = RIGHT_ROWS_COUNT * RIGHT_JOIN_COLUMN_NULLS;
        double rightJoinComplementColumnNulls = 1.0;
        double totalRowCount = innerJoinRowCount + leftJoinComplementRowCount + rightJoinComplementRowCount;

        PlanNodeStatsEstimate leftJoinStats = planNodeStats(
                totalRowCount,
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, (leftJoinComplementColumnNulls * leftJoinComplementRowCount + rightJoinComplementRowCount) / totalRowCount, LEFT_JOIN_COLUMN_NDV),
                symbolStatistics(LEFT_OTHER_COLUMN, 42, 42, (0.42 * (innerJoinRowCount + leftJoinComplementRowCount) + rightJoinComplementRowCount) / totalRowCount, 1),
                symbolStatistics(RIGHT_JOIN_COLUMN, 5.0, 20.0, (rightJoinComplementColumnNulls * rightJoinComplementRowCount + leftJoinComplementRowCount) / totalRowCount, RIGHT_JOIN_COLUMN_NDV),
                symbolStatistics(RIGHT_OTHER_COLUMN, 24, 24, (0.24 * (innerJoinRowCount + rightJoinComplementRowCount) + leftJoinComplementRowCount) / totalRowCount, 1));

        assertJoinStats(FULL, LEFT_STATS, RIGHT_STATS, leftJoinStats);
    }

    @Test
    public void testAddJoinComplementStats()
    {
        double statsToAddNdv = 5;
        PlanNodeStatsEstimate statsToAdd = planNodeStats(
                RIGHT_ROWS_COUNT,
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 5.0, 0.2, statsToAddNdv));

        PlanNodeStatsEstimate addedStats = planNodeStats(
                TOTAL_ROWS_COUNT,
                symbolStatistics(LEFT_JOIN_COLUMN, 0.0, 20.0, (LEFT_ROWS_COUNT * LEFT_JOIN_COLUMN_NULLS + RIGHT_ROWS_COUNT * 0.2) / TOTAL_ROWS_COUNT, LEFT_JOIN_COLUMN_NDV),
                symbolStatistics(LEFT_OTHER_COLUMN, 42, 42, (0.42 * LEFT_ROWS_COUNT + RIGHT_ROWS_COUNT) / TOTAL_ROWS_COUNT, 1));

        assertThat(JOIN_STATS_RULE.addJoinComplementStats(
                LEFT_STATS,
                LEFT_STATS,
                statsToAdd))
                .equalTo(addedStats);
    }

    @Test
    public void testUnknownInputStats()
    {
        assertJoinStats(INNER, PlanNodeStatsEstimate.unknown(), RIGHT_STATS, PlanNodeStatsEstimate.unknown());
        assertJoinStats(INNER, LEFT_STATS, PlanNodeStatsEstimate.unknown(), PlanNodeStatsEstimate.unknown());
        assertJoinStats(INNER, PlanNodeStatsEstimate.unknown(), PlanNodeStatsEstimate.unknown(), PlanNodeStatsEstimate.unknown());
    }

    @Test
    public void testZeroInputStats()
    {
        PlanNodeStatsEstimate zeroLeftStats = planNodeStats(0,
                new SymbolStatistics(LEFT_JOIN_COLUMN, SymbolStatsEstimate.zero()),
                new SymbolStatistics(LEFT_OTHER_COLUMN, SymbolStatsEstimate.zero()));
        PlanNodeStatsEstimate zeroRightStats = planNodeStats(0,
                new SymbolStatistics(RIGHT_JOIN_COLUMN, SymbolStatsEstimate.zero()),
                new SymbolStatistics(RIGHT_OTHER_COLUMN, SymbolStatsEstimate.zero()));
        PlanNodeStatsEstimate zeroResultStats = planNodeStats(0,
                new SymbolStatistics(LEFT_JOIN_COLUMN, SymbolStatsEstimate.zero()),
                new SymbolStatistics(LEFT_OTHER_COLUMN, SymbolStatsEstimate.zero()),
                new SymbolStatistics(RIGHT_JOIN_COLUMN, SymbolStatsEstimate.zero()),
                new SymbolStatistics(RIGHT_OTHER_COLUMN, SymbolStatsEstimate.zero()));

        assertJoinStats(INNER, zeroLeftStats, RIGHT_STATS, zeroResultStats);
        assertJoinStats(INNER, LEFT_STATS, zeroRightStats, zeroResultStats);
        assertJoinStats(INNER, zeroLeftStats, zeroRightStats, zeroResultStats);
    }

    private void assertJoinStats(JoinNode.Type joinType, PlanNodeStatsEstimate leftStats, PlanNodeStatsEstimate rightStats, PlanNodeStatsEstimate resultStats)
    {
        assertJoinStats(joinType, LEFT_JOIN_COLUMN, LEFT_OTHER_COLUMN, RIGHT_JOIN_COLUMN, RIGHT_OTHER_COLUMN, leftStats, rightStats, resultStats);
    }

    private void assertJoinStats(JoinNode.Type joinType, String leftJoinColumn, String leftOtherColumn, String rightJoinColumn, String rightOtherColumn, PlanNodeStatsEstimate leftStats, PlanNodeStatsEstimate rightStats, PlanNodeStatsEstimate resultStats)
    {
        tester().assertStatsFor(pb -> {
            Symbol leftJoinColumnSymbol = pb.symbol(leftJoinColumn, BIGINT);
            Symbol rightJoinColumnSymbol = pb.symbol(rightJoinColumn, DOUBLE);
            Symbol leftOtherColumnSymbol = pb.symbol(leftOtherColumn, BIGINT);
            Symbol rightOtherColumnSymbol = pb.symbol(rightOtherColumn, DOUBLE);
            return pb
                    .join(
                            joinType,
                            pb.values(leftJoinColumnSymbol, leftOtherColumnSymbol),
                            pb.values(rightJoinColumnSymbol, rightOtherColumnSymbol),
                            new EquiJoinClause(leftJoinColumnSymbol, rightJoinColumnSymbol));
        }).withSourceStats(0, leftStats)
                .withSourceStats(1, rightStats)
                .check(JOIN_STATS_RULE, stats -> stats.equalTo(resultStats));
    }

    private static PlanNodeStatsEstimate planNodeStats(double rowCount, SymbolStatistics... symbolStatistics)
    {
        PlanNodeStatsEstimate.Builder builder = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(rowCount);
        for (SymbolStatistics symbolStatistic : symbolStatistics) {
            builder.addSymbolStatistics(symbolStatistic.symbol, symbolStatistic.estimate);
        }
        return builder.build();
    }

    private static SymbolStatistics symbolStatistics(String symbolName, double low, double high, double nullsFraction, double ndv)
    {
        return new SymbolStatistics(
                new Symbol(symbolName),
                SymbolStatsEstimate.builder()
                        .setLowValue(low)
                        .setHighValue(high)
                        .setNullsFraction(nullsFraction)
                        .setDistinctValuesCount(ndv)
                        .build());
    }

    private static class SymbolStatistics
    {
        final Symbol symbol;
        final SymbolStatsEstimate estimate;

        SymbolStatistics(String symbolName, SymbolStatsEstimate estimate)
        {
            this(new Symbol(symbolName), estimate);
        }

        SymbolStatistics(Symbol symbol, SymbolStatsEstimate estimate)
        {
            this.symbol = symbol;
            this.estimate = estimate;
        }
    }
}
