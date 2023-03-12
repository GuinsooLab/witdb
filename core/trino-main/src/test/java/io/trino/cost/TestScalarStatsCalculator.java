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

import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.trino.Session;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.security.AllowAllAccessControl;
import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.Cast;
import io.trino.sql.tree.DecimalLiteral;
import io.trino.sql.tree.DoubleLiteral;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.GenericLiteral;
import io.trino.sql.tree.NullLiteral;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.StringLiteral;
import io.trino.sql.tree.SymbolReference;
import io.trino.transaction.TestingTransactionManager;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ExpressionUtils.rewriteIdentifiersToSymbolReferences;
import static io.trino.sql.analyzer.TypeSignatureTranslator.toSqlType;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.lang.Double.NEGATIVE_INFINITY;
import static java.lang.Double.POSITIVE_INFINITY;

public class TestScalarStatsCalculator
{
    private TestingFunctionResolution functionResolution;
    private ScalarStatsCalculator calculator;
    private Session session;
    private final SqlParser sqlParser = new SqlParser();

    @BeforeClass
    public void setUp()
    {
        functionResolution = new TestingFunctionResolution();
        calculator = new ScalarStatsCalculator(functionResolution.getPlannerContext(), createTestingTypeAnalyzer(functionResolution.getPlannerContext()));
        session = testSessionBuilder().build();
    }

    @Test
    public void testLiteral()
    {
        assertCalculate(new GenericLiteral("TINYINT", "7"))
                .distinctValuesCount(1.0)
                .lowValue(7)
                .highValue(7)
                .nullsFraction(0.0);

        assertCalculate(new GenericLiteral("SMALLINT", "8"))
                .distinctValuesCount(1.0)
                .lowValue(8)
                .highValue(8)
                .nullsFraction(0.0);

        assertCalculate(new GenericLiteral("INTEGER", "9"))
                .distinctValuesCount(1.0)
                .lowValue(9)
                .highValue(9)
                .nullsFraction(0.0);

        assertCalculate(new GenericLiteral("BIGINT", Long.toString(Long.MAX_VALUE)))
                .distinctValuesCount(1.0)
                .lowValue(Long.MAX_VALUE)
                .highValue(Long.MAX_VALUE)
                .nullsFraction(0.0);

        assertCalculate(new DoubleLiteral("7.5"))
                .distinctValuesCount(1.0)
                .lowValue(7.5)
                .highValue(7.5)
                .nullsFraction(0.0);

        assertCalculate(new DecimalLiteral("75.5"))
                .distinctValuesCount(1.0)
                .lowValue(75.5)
                .highValue(75.5)
                .nullsFraction(0.0);

        assertCalculate(new StringLiteral("blah"))
                .distinctValuesCount(1.0)
                .lowValueUnknown()
                .highValueUnknown()
                .nullsFraction(0.0);

        assertCalculate(new NullLiteral())
                .distinctValuesCount(0.0)
                .lowValueUnknown()
                .highValueUnknown()
                .nullsFraction(1.0);
    }

    @Test
    public void testFunctionCall()
    {
        assertCalculate(
                functionResolution
                        .functionCallBuilder(QualifiedName.of("length"))
                        .addArgument(createVarcharType(10), new Cast(new NullLiteral(), toSqlType(createVarcharType(10))))
                        .build())
                .distinctValuesCount(0.0)
                .lowValueUnknown()
                .highValueUnknown()
                .nullsFraction(1.0);

        assertCalculate(
                functionResolution
                        .functionCallBuilder(QualifiedName.of("length"))
                        .addArgument(createVarcharType(2), new SymbolReference("x"))
                        .build(),
                PlanNodeStatsEstimate.unknown(),
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("x"), createVarcharType(2))))
                .distinctValuesCountUnknown()
                .lowValueUnknown()
                .highValueUnknown()
                .nullsFractionUnknown();
    }

    @Test
    public void testVarbinaryConstant()
    {
        LiteralEncoder literalEncoder = new LiteralEncoder(functionResolution.getPlannerContext());
        Expression expression = literalEncoder.toExpression(session, Slices.utf8Slice("ala ma kota"), VARBINARY);

        assertCalculate(expression)
                .distinctValuesCount(1.0)
                .lowValueUnknown()
                .highValueUnknown()
                .nullsFraction(0.0);
    }

    @Test
    public void testSymbolReference()
    {
        SymbolStatsEstimate xStats = SymbolStatsEstimate.builder()
                .setLowValue(-1)
                .setHighValue(10)
                .setDistinctValuesCount(4)
                .setNullsFraction(0.1)
                .setAverageRowSize(2.0)
                .build();
        PlanNodeStatsEstimate inputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), xStats)
                .build();

        assertCalculate(expression("x"), inputStatistics).isEqualTo(xStats);
        assertCalculate(expression("y"), inputStatistics).isEqualTo(SymbolStatsEstimate.unknown());
    }

    @Test
    public void testCastDoubleToBigint()
    {
        PlanNodeStatsEstimate inputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.builder()
                        .setNullsFraction(0.3)
                        .setLowValue(1.6)
                        .setHighValue(17.3)
                        .setDistinctValuesCount(10)
                        .setAverageRowSize(2.0)
                        .build())
                .build();

        assertCalculate(
                new Cast(new SymbolReference("a"), toSqlType(BIGINT)),
                inputStatistics,
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("a"), BIGINT)))
                .lowValue(2.0)
                .highValue(17.0)
                .distinctValuesCount(10)
                .nullsFraction(0.3)
                .dataSizeUnknown();
    }

    @Test
    public void testCastDoubleToShortRange()
    {
        PlanNodeStatsEstimate inputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.builder()
                        .setNullsFraction(0.3)
                        .setLowValue(1.6)
                        .setHighValue(3.3)
                        .setDistinctValuesCount(10)
                        .setAverageRowSize(2.0)
                        .build())
                .build();

        assertCalculate(
                new Cast(new SymbolReference("a"), toSqlType(BIGINT)),
                inputStatistics,
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("a"), BIGINT)))
                .lowValue(2.0)
                .highValue(3.0)
                .distinctValuesCount(2)
                .nullsFraction(0.3)
                .dataSizeUnknown();
    }

    @Test
    public void testCastDoubleToShortRangeUnknownDistinctValuesCount()
    {
        PlanNodeStatsEstimate inputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.builder()
                        .setNullsFraction(0.3)
                        .setLowValue(1.6)
                        .setHighValue(3.3)
                        .setAverageRowSize(2.0)
                        .build())
                .build();

        assertCalculate(
                new Cast(new SymbolReference("a"), toSqlType(BIGINT)),
                inputStatistics,
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("a"), BIGINT)))
                .lowValue(2.0)
                .highValue(3.0)
                .distinctValuesCountUnknown()
                .nullsFraction(0.3)
                .dataSizeUnknown();
    }

    @Test
    public void testCastBigintToDouble()
    {
        PlanNodeStatsEstimate inputStatistics = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.builder()
                        .setNullsFraction(0.3)
                        .setLowValue(2.0)
                        .setHighValue(10.0)
                        .setDistinctValuesCount(4)
                        .setAverageRowSize(2.0)
                        .build())
                .build();

        assertCalculate(
                new Cast(new SymbolReference("a"), toSqlType(DOUBLE)),
                inputStatistics,
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("a"), DOUBLE)))
                .lowValue(2.0)
                .highValue(10.0)
                .distinctValuesCount(4)
                .nullsFraction(0.3)
                .dataSizeUnknown();
    }

    @Test
    public void testCastUnknown()
    {
        assertCalculate(
                new Cast(new SymbolReference("a"), toSqlType(BIGINT)),
                PlanNodeStatsEstimate.unknown(),
                TypeProvider.viewOf(ImmutableMap.of(new Symbol("a"), BIGINT)))
                .lowValueUnknown()
                .highValueUnknown()
                .distinctValuesCountUnknown()
                .nullsFractionUnknown()
                .dataSizeUnknown();
    }

    private SymbolStatsAssertion assertCalculate(Expression scalarExpression)
    {
        return assertCalculate(scalarExpression, PlanNodeStatsEstimate.unknown());
    }

    private SymbolStatsAssertion assertCalculate(Expression scalarExpression, PlanNodeStatsEstimate inputStatistics)
    {
        return assertCalculate(scalarExpression, inputStatistics, TypeProvider.empty());
    }

    private SymbolStatsAssertion assertCalculate(Expression scalarExpression, PlanNodeStatsEstimate inputStatistics, TypeProvider types)
    {
        return transaction(new TestingTransactionManager(), new AllowAllAccessControl())
                .singleStatement()
                .execute(session, transactionSession -> {
                    return SymbolStatsAssertion.assertThat(calculator.calculate(scalarExpression, inputStatistics, transactionSession, types));
                });
    }

    @Test
    public void testNonDivideArithmeticBinaryExpression()
    {
        PlanNodeStatsEstimate relationStats = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), SymbolStatsEstimate.builder()
                        .setLowValue(-1)
                        .setHighValue(10)
                        .setDistinctValuesCount(4)
                        .setNullsFraction(0.1)
                        .setAverageRowSize(2.0)
                        .build())
                .addSymbolStatistics(new Symbol("y"), SymbolStatsEstimate.builder()
                        .setLowValue(-2)
                        .setHighValue(5)
                        .setDistinctValuesCount(3)
                        .setNullsFraction(0.2)
                        .setAverageRowSize(2.0)
                        .build())
                .addSymbolStatistics(new Symbol("unknown"), SymbolStatsEstimate.unknown())
                .setOutputRowCount(10)
                .build();

        assertCalculate(expression("x + y"), relationStats)
                .distinctValuesCount(10.0)
                .lowValue(-3.0)
                .highValue(15.0)
                .nullsFraction(0.28)
                .averageRowSize(2.0);

        assertCalculate(expression("x + unknown"), relationStats)
                .isEqualTo(SymbolStatsEstimate.unknown());
        assertCalculate(expression("unknown + unknown"), relationStats)
                .isEqualTo(SymbolStatsEstimate.unknown());

        assertCalculate(expression("x - y"), relationStats)
                .distinctValuesCount(10.0)
                .lowValue(-6.0)
                .highValue(12.0)
                .nullsFraction(0.28)
                .averageRowSize(2.0);

        assertCalculate(expression("x * y"), relationStats)
                .distinctValuesCount(10.0)
                .lowValue(-20.0)
                .highValue(50.0)
                .nullsFraction(0.28)
                .averageRowSize(2.0);
    }

    @Test
    public void testArithmeticBinaryWithAllNullsSymbol()
    {
        SymbolStatsEstimate allNullStats = SymbolStatsEstimate.zero();
        PlanNodeStatsEstimate relationStats = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), SymbolStatsEstimate.builder()
                        .setLowValue(-1)
                        .setHighValue(10)
                        .setDistinctValuesCount(4)
                        .setNullsFraction(0.1)
                        .setAverageRowSize(0)
                        .build())
                .addSymbolStatistics(new Symbol("all_null"), allNullStats)
                .setOutputRowCount(10)
                .build();

        assertCalculate(expression("x + all_null"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("x - all_null"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("all_null - x"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("all_null * x"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("x % all_null"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("all_null % x"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("x / all_null"), relationStats)
                .isEqualTo(allNullStats);
        assertCalculate(expression("all_null / x"), relationStats)
                .isEqualTo(allNullStats);
    }

    @Test
    public void testDivideArithmeticBinaryExpression()
    {
        assertCalculate(expression("x / y"), xyStats(-11, -3, -5, -4)).lowValue(0.6).highValue(2.75);
        assertCalculate(expression("x / y"), xyStats(-11, -3, -5, 4)).lowValue(NEGATIVE_INFINITY).highValue(POSITIVE_INFINITY);
        assertCalculate(expression("x / y"), xyStats(-11, -3, 4, 5)).lowValue(-2.75).highValue(-0.6);

        assertCalculate(expression("x / y"), xyStats(-11, 0, -5, -4)).lowValue(0).highValue(2.75);
        assertCalculate(expression("x / y"), xyStats(-11, 0, -5, 4)).lowValue(NEGATIVE_INFINITY).highValue(POSITIVE_INFINITY);
        assertCalculate(expression("x / y"), xyStats(-11, 0, 4, 5)).lowValue(-2.75).highValue(0);

        assertCalculate(expression("x / y"), xyStats(-11, 3, -5, -4)).lowValue(-0.75).highValue(2.75);
        assertCalculate(expression("x / y"), xyStats(-11, 3, -5, 4)).lowValue(NEGATIVE_INFINITY).highValue(POSITIVE_INFINITY);
        assertCalculate(expression("x / y"), xyStats(-11, 3, 4, 5)).lowValue(-2.75).highValue(0.75);

        assertCalculate(expression("x / y"), xyStats(0, 3, -5, -4)).lowValue(-0.75).highValue(0);
        assertCalculate(expression("x / y"), xyStats(0, 3, -5, 4)).lowValue(NEGATIVE_INFINITY).highValue(POSITIVE_INFINITY);
        assertCalculate(expression("x / y"), xyStats(0, 3, 4, 5)).lowValue(0).highValue(0.75);

        assertCalculate(expression("x / y"), xyStats(3, 11, -5, -4)).lowValue(-2.75).highValue(-0.6);
        assertCalculate(expression("x / y"), xyStats(3, 11, -5, 4)).lowValue(NEGATIVE_INFINITY).highValue(POSITIVE_INFINITY);
        assertCalculate(expression("x / y"), xyStats(3, 11, 4, 5)).lowValue(0.6).highValue(2.75);
    }

    @Test
    public void testModulusArithmeticBinaryExpression()
    {
        // negative
        assertCalculate(expression("x % y"), xyStats(-1, 0, -6, -4)).lowValue(-1).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-5, 0, -6, -4)).lowValue(-5).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, -6, -4)).lowValue(-6).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, -6, -4)).lowValue(-6).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, -6, 4)).lowValue(-6).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, -6, 6)).lowValue(-6).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, 4, 6)).lowValue(-6).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-1, 0, 4, 6)).lowValue(-1).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-5, 0, 4, 6)).lowValue(-5).highValue(0);
        assertCalculate(expression("x % y"), xyStats(-8, 0, 4, 6)).lowValue(-6).highValue(0);

        // positive
        assertCalculate(expression("x % y"), xyStats(0, 5, -6, -4)).lowValue(0).highValue(5);
        assertCalculate(expression("x % y"), xyStats(0, 8, -6, -4)).lowValue(0).highValue(6);
        assertCalculate(expression("x % y"), xyStats(0, 1, -6, 4)).lowValue(0).highValue(1);
        assertCalculate(expression("x % y"), xyStats(0, 5, -6, 4)).lowValue(0).highValue(5);
        assertCalculate(expression("x % y"), xyStats(0, 8, -6, 4)).lowValue(0).highValue(6);
        assertCalculate(expression("x % y"), xyStats(0, 1, 4, 6)).lowValue(0).highValue(1);
        assertCalculate(expression("x % y"), xyStats(0, 5, 4, 6)).lowValue(0).highValue(5);
        assertCalculate(expression("x % y"), xyStats(0, 8, 4, 6)).lowValue(0).highValue(6);

        // mix
        assertCalculate(expression("x % y"), xyStats(-1, 1, -6, -4)).lowValue(-1).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-1, 5, -6, -4)).lowValue(-1).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 1, -6, -4)).lowValue(-5).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-5, 5, -6, -4)).lowValue(-5).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 8, -6, -4)).lowValue(-5).highValue(6);
        assertCalculate(expression("x % y"), xyStats(-8, 5, -6, -4)).lowValue(-6).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-8, 8, -6, -4)).lowValue(-6).highValue(6);
        assertCalculate(expression("x % y"), xyStats(-1, 1, -6, 4)).lowValue(-1).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-1, 5, -6, 4)).lowValue(-1).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 1, -6, 4)).lowValue(-5).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-5, 5, -6, 4)).lowValue(-5).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 8, -6, 4)).lowValue(-5).highValue(6);
        assertCalculate(expression("x % y"), xyStats(-8, 5, -6, 4)).lowValue(-6).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-8, 8, -6, 4)).lowValue(-6).highValue(6);
        assertCalculate(expression("x % y"), xyStats(-1, 1, 4, 6)).lowValue(-1).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-1, 5, 4, 6)).lowValue(-1).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 1, 4, 6)).lowValue(-5).highValue(1);
        assertCalculate(expression("x % y"), xyStats(-5, 5, 4, 6)).lowValue(-5).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-5, 8, 4, 6)).lowValue(-5).highValue(6);
        assertCalculate(expression("x % y"), xyStats(-8, 5, 4, 6)).lowValue(-6).highValue(5);
        assertCalculate(expression("x % y"), xyStats(-8, 8, 4, 6)).lowValue(-6).highValue(6);
    }

    private PlanNodeStatsEstimate xyStats(double lowX, double highX, double lowY, double highY)
    {
        return PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), SymbolStatsEstimate.builder()
                        .setLowValue(lowX)
                        .setHighValue(highX)
                        .build())
                .addSymbolStatistics(new Symbol("y"), SymbolStatsEstimate.builder()
                        .setLowValue(lowY)
                        .setHighValue(highY)
                        .build())
                .build();
    }

    @Test
    public void testCoalesceExpression()
    {
        PlanNodeStatsEstimate relationStats = PlanNodeStatsEstimate.builder()
                .addSymbolStatistics(new Symbol("x"), SymbolStatsEstimate.builder()
                        .setLowValue(-1)
                        .setHighValue(10)
                        .setDistinctValuesCount(4)
                        .setNullsFraction(0.1)
                        .setAverageRowSize(2.0)
                        .build())
                .addSymbolStatistics(new Symbol("y"), SymbolStatsEstimate.builder()
                        .setLowValue(-2)
                        .setHighValue(5)
                        .setDistinctValuesCount(3)
                        .setNullsFraction(0.2)
                        .setAverageRowSize(2.0)
                        .build())
                .setOutputRowCount(10)
                .build();

        assertCalculate(expression("coalesce(x, y)"), relationStats)
                .distinctValuesCount(5)
                .lowValue(-2)
                .highValue(10)
                .nullsFraction(0.02)
                .averageRowSize(2.0);

        assertCalculate(expression("coalesce(y, x)"), relationStats)
                .distinctValuesCount(5)
                .lowValue(-2)
                .highValue(10)
                .nullsFraction(0.02)
                .averageRowSize(2.0);
    }

    private Expression expression(String sqlExpression)
    {
        return rewriteIdentifiersToSymbolReferences(sqlParser.createExpression(sqlExpression, new ParsingOptions()));
    }
}
