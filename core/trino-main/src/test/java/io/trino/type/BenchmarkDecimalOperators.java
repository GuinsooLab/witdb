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
package io.trino.type;

import com.google.common.collect.ImmutableList;
import io.trino.RowPagesBuilder;
import io.trino.operator.DriverYieldSignal;
import io.trino.operator.project.PageProcessor;
import io.trino.spi.Page;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.SqlDecimal;
import io.trino.spi.type.Type;
import io.trino.sql.gen.ExpressionCompiler;
import io.trino.sql.gen.PageFunctionCompiler;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeAnalyzer;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.relational.RowExpression;
import io.trino.sql.relational.SqlToRowExpressionTranslator;
import io.trino.sql.tree.Expression;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;
import org.testng.annotations.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.jmh.Benchmarks.benchmark;
import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.sql.ExpressionTestUtils.createExpression;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static java.lang.String.format;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.openjdk.jmh.annotations.Scope.Thread;

/**
 * This benchmark is known to produce non-deterministic results, because of the nature of JIT compiler.
 * Using -Xbatch flag reduces the impact of this flaw while greatly increasing startup time.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = {
        "-Xbatch",
        "-server",
})
@Warmup(iterations = 30, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 50, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkDecimalOperators
{
    private static final int PAGE_SIZE = 30000;

    private static final DecimalType SHORT_DECIMAL_TYPE = createDecimalType(10, 0);
    private static final DecimalType LONG_DECIMAL_TYPE = createDecimalType(20, 0);

    @State(Thread)
    public static class CastDoubleToDecimalBenchmarkState
            extends BaseState
    {
        private static final int SCALE = 2;

        @Param({"10", "35", "BIGINT"})
        private String precision = "10";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);

            String expression;
            if (precision.equals("BIGINT")) {
                setDoubleMaxValue(Long.MAX_VALUE);
                expression = "CAST(d1 AS BIGINT)";
            }
            else {
                setDoubleMaxValue(Math.pow(9, Integer.parseInt(precision) - SCALE));
                expression = format("CAST(d1 AS DECIMAL(%s, %d))", precision, SCALE);
            }
            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object castDoubleToDecimalBenchmark(CastDoubleToDecimalBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testCastDoubleToDecimalBenchmark()
    {
        CastDoubleToDecimalBenchmarkState state = new CastDoubleToDecimalBenchmarkState();
        state.setup();
        castDoubleToDecimalBenchmark(state);
    }

    @State(Thread)
    public static class CastDecimalToDoubleBenchmarkState
            extends BaseState
    {
        private static final int SCALE = 10;

        @Param({"15", "35"})
        private String precision = "15";

        @Setup
        public void setup()
        {
            addSymbol("v1", createDecimalType(Integer.parseInt(precision), SCALE));

            String expression = "CAST(v1 AS DOUBLE)";
            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object castDecimalToDoubleBenchmark(CastDecimalToDoubleBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testCastDecimalToDoubleBenchmark()
    {
        CastDecimalToDoubleBenchmarkState state = new CastDecimalToDoubleBenchmarkState();
        state.setup();
        castDecimalToDoubleBenchmark(state);
    }

    @State(Thread)
    public static class CastDecimalToVarcharBenchmarkState
            extends BaseState
    {
        private static final int SCALE = 10;

        @Param({"15", "35"})
        private String precision = "35";

        @Setup
        public void setup()
        {
            addSymbol("v1", createDecimalType(Integer.parseInt(precision), SCALE));

            String expression = "CAST(v1 AS VARCHAR)";
            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object castDecimalToVarcharBenchmark(CastDecimalToVarcharBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testCastDecimalToVarcharBenchmark()
    {
        CastDecimalToVarcharBenchmarkState state = new CastDecimalToVarcharBenchmarkState();
        state.setup();
        castDecimalToVarcharBenchmark(state);
    }

    @State(Thread)
    public static class AdditionBenchmarkState
            extends BaseState
    {
        @Param({"d1 + d2",
                "d1 + d2 + d3 + d4",
                "s1 + s2",
                "s1 + s2 + s3 + s4",
                "l1 + l2",
                "l1 + l2 + l3 + l4",
                "s2 + l3 + l1 + s4",
                "lz1 + lz2",
                "lz1 + lz2 + lz3 + lz4",
                "s2 + lz3 + lz1 + s4"})
        private String expression = "d1 + d2";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);
            addSymbol("d2", DOUBLE);
            addSymbol("d3", DOUBLE);
            addSymbol("d4", DOUBLE);

            addSymbol("s1", createDecimalType(10, 5));
            addSymbol("s2", createDecimalType(7, 2));
            addSymbol("s3", createDecimalType(12, 2));
            addSymbol("s4", createDecimalType(2, 1));

            addSymbol("l1", createDecimalType(35, 10));
            addSymbol("l2", createDecimalType(25, 5));
            addSymbol("l3", createDecimalType(20, 6));
            addSymbol("l4", createDecimalType(25, 8));

            addSymbol("lz1", createDecimalType(35, 0));
            addSymbol("lz2", createDecimalType(25, 0));
            addSymbol("lz3", createDecimalType(20, 0));
            addSymbol("lz4", createDecimalType(25, 0));

            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object additionBenchmark(AdditionBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testAdditionBenchmark()
    {
        AdditionBenchmarkState state = new AdditionBenchmarkState();
        state.setup();
        additionBenchmark(state);
    }

    @State(Thread)
    public static class MultiplyBenchmarkState
            extends BaseState
    {
        @Param({"d1 * d2",
                "d1 * d2 * d3 * d4",
                "i1 * i2",
                // short short -> short
                "s1 * s2",
                "s1 * s2 * s5 * s6",
                // short short -> long
                "s3 * s4",
                // long short -> long
                "l2 * s2",
                "l2 * s2 * s5 * s6",
                // short long -> long
                "s1 * l2",
                // long long -> long
                "l1 * l2"})
        private String expression = "d1 * d2";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);
            addSymbol("d2", DOUBLE);
            addSymbol("d3", DOUBLE);
            addSymbol("d4", DOUBLE);

            addSymbol("i1", BIGINT);
            addSymbol("i2", BIGINT);

            addSymbol("s1", createDecimalType(5, 2));
            addSymbol("s2", createDecimalType(3, 1));
            addSymbol("s3", createDecimalType(10, 5));
            addSymbol("s4", createDecimalType(10, 2));
            addSymbol("s5", createDecimalType(3, 2));
            addSymbol("s6", createDecimalType(2, 1));

            addSymbol("l1", createDecimalType(19, 10));
            addSymbol("l2", createDecimalType(19, 5));

            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object multiplyBenchmark(MultiplyBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testMultiplyBenchmark()
    {
        MultiplyBenchmarkState state = new MultiplyBenchmarkState();
        state.setup();
        multiplyBenchmark(state);
    }

    @State(Thread)
    public static class DivisionBenchmarkState
            extends BaseState
    {
        @Param({"d1 / d2",
                "d1 / d2 / d3 / d4",
                "i1 / i2",
                "i1 / i2 / i3 / i4",
                // short short -> short
                "s1 / s2",
                "s1 / s2 / s2 / s2",
                // short short -> long
                "s1 / s3",
                // short long -> short
                "s2 / l1",
                // long short -> long
                "l1 / s2",
                // short long -> long
                "s3 / l1",
                // long long -> long
                "l2 / l3",
                "l2 / l4 / l4 / l4",
                "l2 / s4 / s4 / s4"})
        private String expression = "d1 / d2";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);
            addSymbol("d2", DOUBLE);
            addSymbol("d3", DOUBLE);
            addSymbol("d4", DOUBLE);

            addSymbol("i1", BIGINT);
            addSymbol("i2", BIGINT);
            addSymbol("i3", BIGINT);
            addSymbol("i4", BIGINT);

            addSymbol("s1", createDecimalType(8, 3));
            addSymbol("s2", createDecimalType(6, 2));
            addSymbol("s3", createDecimalType(17, 7));
            addSymbol("s4", createDecimalType(3, 2));

            addSymbol("l1", createDecimalType(19, 3));
            addSymbol("l2", createDecimalType(20, 3));
            addSymbol("l3", createDecimalType(21, 10));
            addSymbol("l4", createDecimalType(19, 4));

            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object divisionBenchmark(DivisionBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testDivisionBenchmark()
    {
        DivisionBenchmarkState state = new DivisionBenchmarkState();
        state.setup();
        divisionBenchmark(state);
    }

    @State(Thread)
    public static class ModuloBenchmarkState
            extends BaseState
    {
        @Param({"d1 % d2",
                "d1 % d2 % d3 % d4",
                "i1 % i2",
                "i1 % i2 % i3 % i4",
                // short short -> short
                "s1 % s2",
                "s1 % s2 % s2 % s2",
                // short long -> short
                "s2 % l2",
                // long short -> long
                "l3 % s3",
                // short long -> long
                "s4 % l3",
                // long long -> long
                "l2 % l3",
                "l2 % l3 % l4 % l1"})
        private String expression = "d1 % d2";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);
            addSymbol("d2", DOUBLE);
            addSymbol("d3", DOUBLE);
            addSymbol("d4", DOUBLE);

            addSymbol("i1", BIGINT);
            addSymbol("i2", BIGINT);
            addSymbol("i3", BIGINT);
            addSymbol("i4", BIGINT);

            addSymbol("s1", createDecimalType(8, 3));
            addSymbol("s2", createDecimalType(6, 2));
            addSymbol("s3", createDecimalType(9, 0));
            addSymbol("s4", createDecimalType(12, 2));

            addSymbol("l1", createDecimalType(19, 3));
            addSymbol("l2", createDecimalType(20, 3));
            addSymbol("l3", createDecimalType(21, 10));
            addSymbol("l4", createDecimalType(19, 4));

            generateRandomInputPage();
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object moduloBenchmark(ModuloBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testModuloBenchmark()
    {
        ModuloBenchmarkState state = new ModuloBenchmarkState();
        state.setup();
        moduloBenchmark(state);
    }

    @State(Thread)
    public static class InequalityBenchmarkState
            extends BaseState
    {
        @Param({"d1 < d2",
                "d1 < d2 AND d1 < d3 AND d1 < d4 AND d2 < d3 AND d2 < d4 AND d3 < d4",
                "s1 < s2",
                "s1 < s2 AND s1 < s3 AND s1 < s4 AND s2 < s3 AND s2 < s4 AND s3 < s4",
                "l1 < l2",
                "l1 < l2 AND l1 < l3 AND l1 < l4 AND l2 < l3 AND l2 < l4 AND l3 < l4"})
        private String expression = "d1 < d2";

        @Setup
        public void setup()
        {
            addSymbol("d1", DOUBLE);
            addSymbol("d2", DOUBLE);
            addSymbol("d3", DOUBLE);
            addSymbol("d4", DOUBLE);

            addSymbol("s1", SHORT_DECIMAL_TYPE);
            addSymbol("s2", SHORT_DECIMAL_TYPE);
            addSymbol("s3", SHORT_DECIMAL_TYPE);
            addSymbol("s4", SHORT_DECIMAL_TYPE);

            addSymbol("l1", LONG_DECIMAL_TYPE);
            addSymbol("l2", LONG_DECIMAL_TYPE);
            addSymbol("l3", LONG_DECIMAL_TYPE);
            addSymbol("l4", LONG_DECIMAL_TYPE);

            generateInputPage(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object inequalityBenchmark(InequalityBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testInequalityBenchmark()
    {
        InequalityBenchmarkState state = new InequalityBenchmarkState();
        state.setup();
        inequalityBenchmark(state);
    }

    @State(Thread)
    public static class DecimalToShortDecimalCastBenchmarkState
            extends BaseState
    {
        @Param({"cast(l_38_30 as decimal(8, 0))",
                "cast(l_26_18 as decimal(8, 0))",
                "cast(l_20_12 as decimal(8, 0))",
                "cast(l_20_8 as decimal(8, 0))",
                "cast(s_17_9 as decimal(8, 0))"})
        private String expression = "cast(l_38_30 as decimal(8, 0))";

        @Setup
        public void setup()
        {
            addSymbol("l_38_30", createDecimalType(38, 30));
            addSymbol("l_26_18", createDecimalType(26, 18));
            addSymbol("l_20_12", createDecimalType(20, 12));
            addSymbol("l_20_8", createDecimalType(20, 8));
            addSymbol("s_17_9", createDecimalType(17, 9));

            generateInputPage(10000, 10000, 10000, 10000, 10000);
            generateProcessor(expression);
        }
    }

    @Benchmark
    public Object decimalToShortDecimalCastBenchmark(DecimalToShortDecimalCastBenchmarkState state)
    {
        return execute(state);
    }

    @Test
    public void testDecimalToShortDecimalCastBenchmark()
    {
        DecimalToShortDecimalCastBenchmarkState state = new DecimalToShortDecimalCastBenchmarkState();
        state.setup();
        decimalToShortDecimalCastBenchmark(state);
    }

    private Object execute(BaseState state)
    {
        return ImmutableList.copyOf(
                state.getProcessor().process(
                        SESSION,
                        new DriverYieldSignal(),
                        newSimpleAggregatedMemoryContext().newLocalMemoryContext(PageProcessor.class.getSimpleName()),
                        state.getInputPage()));
    }

    private static class BaseState
    {
        private final TypeAnalyzer typeAnalyzer = createTestingTypeAnalyzer(PLANNER_CONTEXT);
        private final Random random = new Random();

        protected final Map<String, Symbol> symbols = new HashMap<>();
        protected final Map<Symbol, Type> symbolTypes = new HashMap<>();
        private final Map<Symbol, Integer> sourceLayout = new HashMap<>();
        protected final List<Type> types = new LinkedList<>();

        protected Page inputPage;
        private PageProcessor processor;
        private double doubleMaxValue = 2L << 31;

        public Page getInputPage()
        {
            return inputPage;
        }

        public PageProcessor getProcessor()
        {
            return processor;
        }

        protected void addSymbol(String name, Type type)
        {
            Symbol symbol = new Symbol(name);
            symbols.put(name, symbol);
            symbolTypes.put(symbol, type);
            sourceLayout.put(symbol, types.size());
            types.add(type);
        }

        protected void generateRandomInputPage()
        {
            RowPagesBuilder buildPagesBuilder = rowPagesBuilder(types);

            for (int i = 0; i < PAGE_SIZE; i++) {
                Object[] values = types.stream()
                        .map(this::generateRandomValue)
                        .toArray();

                buildPagesBuilder.row(values);
            }

            inputPage = getOnlyElement(buildPagesBuilder.build());
        }

        protected void generateInputPage(int... initialValues)
        {
            RowPagesBuilder buildPagesBuilder = rowPagesBuilder(types);
            buildPagesBuilder.addSequencePage(PAGE_SIZE, initialValues);
            inputPage = getOnlyElement(buildPagesBuilder.build());
        }

        protected void generateProcessor(String expression)
        {
            processor = new ExpressionCompiler(PLANNER_CONTEXT.getFunctionManager(), new PageFunctionCompiler(PLANNER_CONTEXT.getFunctionManager(), 0)).compilePageProcessor(Optional.empty(), ImmutableList.of(rowExpression(expression))).get();
        }

        protected void setDoubleMaxValue(double doubleMaxValue)
        {
            this.doubleMaxValue = doubleMaxValue;
        }

        private RowExpression rowExpression(String value)
        {
            Expression expression = createExpression(value, PLANNER_CONTEXT, TypeProvider.copyOf(symbolTypes));

            return SqlToRowExpressionTranslator.translate(
                    expression,
                    typeAnalyzer.getTypes(TEST_SESSION, TypeProvider.copyOf(symbolTypes), expression),
                    sourceLayout,
                    PLANNER_CONTEXT.getMetadata(),
                    PLANNER_CONTEXT.getFunctionManager(),
                    TEST_SESSION,
                    true);
        }

        private Object generateRandomValue(Type type)
        {
            if (type instanceof DoubleType) {
                return random.nextDouble() * (2L * doubleMaxValue) - doubleMaxValue;
            }
            if (type instanceof DecimalType) {
                return randomDecimal((DecimalType) type);
            }
            if (type instanceof BigintType) {
                int randomInt = random.nextInt();
                return randomInt == 0 ? 1 : randomInt;
            }
            throw new UnsupportedOperationException(type.toString());
        }

        private SqlDecimal randomDecimal(DecimalType type)
        {
            int maxBits = (int) (Math.log(Math.pow(10, type.getPrecision())) / Math.log(2));
            BigInteger bigInteger = new BigInteger(maxBits, random);

            if (bigInteger.equals(ZERO)) {
                bigInteger = ONE;
            }

            if (random.nextBoolean()) {
                bigInteger = bigInteger.negate();
            }

            return new SqlDecimal(bigInteger, type.getPrecision(), type.getScale());
        }
    }

    public static void main(String[] args)
            throws RunnerException
    {
        benchmark(BenchmarkDecimalOperators.class).run();
    }
}
