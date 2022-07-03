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
package io.trino.operator.aggregation;

import com.google.common.base.Joiner;
import com.google.common.primitives.Floats;
import io.airlift.stats.QuantileDigest;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.operator.scalar.AbstractTestFunctions;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.StandardTypes;
import io.trino.sql.analyzer.TypeSignatureProvider;
import io.trino.sql.tree.QualifiedName;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.block.BlockAssertions.createBlockOfReals;
import static io.trino.block.BlockAssertions.createDoubleSequenceBlock;
import static io.trino.block.BlockAssertions.createDoublesBlock;
import static io.trino.block.BlockAssertions.createLongSequenceBlock;
import static io.trino.block.BlockAssertions.createLongsBlock;
import static io.trino.block.BlockAssertions.createRLEBlock;
import static io.trino.block.BlockAssertions.createSequenceBlockOfReal;
import static io.trino.operator.aggregation.AggregationTestUtils.assertAggregation;
import static io.trino.operator.aggregation.FloatingPointBitsConverterUtil.doubleToSortableLong;
import static io.trino.operator.aggregation.FloatingPointBitsConverterUtil.floatToSortableInt;
import static io.trino.operator.aggregation.TestMergeQuantileDigestFunction.QDIGEST_EQUALITY;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static java.lang.Double.NaN;
import static java.lang.Integer.max;
import static java.lang.Integer.min;
import static java.lang.String.format;

public class TestQuantileDigestAggregationFunction
        extends AbstractTestFunctions
{
    private static final Joiner ARRAY_JOINER = Joiner.on(",");
    private static final TestingFunctionResolution FUNCTION_RESOLUTION = new TestingFunctionResolution();
    private static final QualifiedName NAME = QualifiedName.of("qdigest_agg");

    @Test
    public void testDoublesWithWeights()
    {
        testAggregationDouble(
                createDoublesBlock(1.0, null, 2.0, null, 3.0, null, 4.0, null, 5.0, null),
                createRLEBlock(1, 10),
                0.01, 1.0, 2.0, 3.0, 4.0, 5.0);
        testAggregationDouble(
                createDoublesBlock(null, null, null, null, null),
                createRLEBlock(1, 5),
                NaN);
        testAggregationDouble(
                createDoublesBlock(-1.0, -2.0, -3.0, -4.0, -5.0, -6.0, -7.0, -8.0, -9.0, -10.0),
                createRLEBlock(1, 10),
                0.01, -1.0, -2.0, -3.0, -4.0, -5.0, -6.0, -7.0, -8.0, -9.0, -10.0);
        testAggregationDouble(
                createDoublesBlock(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0),
                createRLEBlock(1, 10),
                0.01, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        testAggregationDouble(
                createDoublesBlock(),
                createRLEBlock(1, 0),
                NaN);
        testAggregationDouble(
                createDoublesBlock(1.0),
                createRLEBlock(1, 1),
                0.01, 1.0);
        testAggregationDouble(
                createDoubleSequenceBlock(-1000, 1000),
                createRLEBlock(1, 2000),
                0.01,
                LongStream.range(-1000, 1000).asDoubleStream().toArray());
    }

    @Test
    public void testRealsWithWeights()
    {
        testAggregationReal(
                createBlockOfReals(1.0F, null, 2.0F, null, 3.0F, null, 4.0F, null, 5.0F, null),
                createRLEBlock(1, 10),
                0.01, 1.0F, 2.0F, 3.0F, 4.0F, 5.0F);
        testAggregationReal(
                createBlockOfReals(null, null, null, null, null),
                createRLEBlock(1, 5),
                NaN);
        testAggregationReal(
                createBlockOfReals(-1.0F, -2.0F, -3.0F, -4.0F, -5.0F, -6.0F, -7.0F, -8.0F, -9.0F, -10.0F),
                createRLEBlock(1, 10),
                0.01, -1.0F, -2.0F, -3.0F, -4.0F, -5.0F, -6.0F, -7.0F, -8.0F, -9.0F, -10.0F);
        testAggregationReal(
                createBlockOfReals(1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.0F, 10.0F),
                createRLEBlock(1, 10),
                0.01, 1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.0F, 10.0F);
        testAggregationReal(
                createBlockOfReals(),
                createRLEBlock(1, 0),
                NaN);
        testAggregationReal(
                createBlockOfReals(1.0F),
                createRLEBlock(1, 1),
                0.01, 1.0F);
        testAggregationReal(
                createSequenceBlockOfReal(-1000, 1000),
                createRLEBlock(1, 2000),
                0.01,
                Floats.toArray(LongStream.range(-1000, 1000).mapToObj(Float::new).collect(toImmutableList())));
    }

    @Test
    public void testBigintsWithWeight()
    {
        testAggregationBigint(
                createLongsBlock(1L, null, 2L, null, 3L, null, 4L, null, 5L, null),
                createRLEBlock(1, 10),
                0.01, 1, 2, 3, 4, 5);
        testAggregationBigint(
                createLongsBlock(null, null, null, null, null),
                createRLEBlock(1, 5),
                NaN);
        testAggregationBigint(
                createLongsBlock(-1, -2, -3, -4, -5, -6, -7, -8, -9, -10),
                createRLEBlock(1, 10),
                0.01, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10);
        testAggregationBigint(
                createLongsBlock(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                createRLEBlock(1, 10),
                0.01, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        testAggregationBigint(
                createLongsBlock(new int[] {}),
                createRLEBlock(1, 0),
                NaN);
        testAggregationBigint(
                createLongsBlock(1),
                createRLEBlock(1, 1),
                0.01, 1);
        testAggregationBigint(
                createLongSequenceBlock(-1000, 1000),
                createRLEBlock(1, 2000),
                0.01,
                LongStream.range(-1000, 1000).toArray());
    }

    private void testAggregationBigint(Block inputBlock, Block weightsBlock, double maxError, long... inputs)
    {
        // Test without weights and accuracy
        testAggregationBigints(
                fromTypes(BIGINT),
                new Page(inputBlock),
                maxError,
                inputs);

        // Test with weights and without accuracy
        testAggregationBigints(
                fromTypes(BIGINT, BIGINT),
                new Page(inputBlock, weightsBlock),
                maxError,
                inputs);
        // Test with weights and accuracy
        testAggregationBigints(
                fromTypes(BIGINT, BIGINT, DOUBLE),
                new Page(inputBlock, weightsBlock, createRLEBlock(maxError, inputBlock.getPositionCount())),
                maxError,
                inputs);
    }

    private void testAggregationReal(Block longsBlock, Block weightsBlock, double maxError, float... inputs)
    {
        // Test without weights and accuracy
        testAggregationReal(
                fromTypes(REAL),
                new Page(longsBlock),
                maxError,
                inputs);
        // Test with weights and without accuracy
        testAggregationReal(
                fromTypes(REAL, BIGINT),
                new Page(longsBlock, weightsBlock),
                maxError,
                inputs);
        // Test with weights and accuracy
        testAggregationReal(
                fromTypes(REAL, BIGINT, DOUBLE),
                new Page(longsBlock, weightsBlock, createRLEBlock(maxError, longsBlock.getPositionCount())),
                maxError,
                inputs);
    }

    private void testAggregationDouble(Block longsBlock, Block weightsBlock, double maxError, double... inputs)
    {
        // Test without weights and accuracy
        testAggregationDoubles(
                fromTypes(DOUBLE),
                new Page(longsBlock),
                maxError,
                inputs);
        // Test with weights and without accuracy
        testAggregationDoubles(
                fromTypes(DOUBLE, BIGINT),
                new Page(longsBlock, weightsBlock),
                maxError,
                inputs);
        // Test with weights and accuracy
        testAggregationDoubles(
                fromTypes(DOUBLE, BIGINT, DOUBLE),
                new Page(longsBlock, weightsBlock, createRLEBlock(maxError, longsBlock.getPositionCount())),
                maxError,
                inputs);
    }

    private void testAggregationBigints(List<TypeSignatureProvider> parameterTypes, Page page, double maxError, long... inputs)
    {
        // aggregate level
        assertAggregation(
                FUNCTION_RESOLUTION,
                NAME,
                parameterTypes,
                QDIGEST_EQUALITY,
                "test multiple positions",
                page,
                getExpectedValueLongs(maxError, inputs));

        // test scalars
        List<Long> rows = Arrays.stream(inputs).sorted().boxed().collect(Collectors.toList());

        SqlVarbinary returned = (SqlVarbinary) AggregationTestUtils.aggregation(FUNCTION_RESOLUTION.getAggregateFunction(NAME, parameterTypes), page);
        assertPercentileWithinError(StandardTypes.BIGINT, returned, maxError, rows, 0.1, 0.5, 0.9, 0.99);
    }

    private void testAggregationDoubles(List<TypeSignatureProvider> parameterTypes, Page page, double maxError, double... inputs)
    {
        assertAggregation(
                FUNCTION_RESOLUTION,
                NAME,
                parameterTypes,
                QDIGEST_EQUALITY,
                "test multiple positions",
                page,
                getExpectedValueDoubles(maxError, inputs));

        // test scalars
        List<Double> rows = Arrays.stream(inputs).sorted().boxed().collect(Collectors.toList());

        SqlVarbinary returned = (SqlVarbinary) AggregationTestUtils.aggregation(FUNCTION_RESOLUTION.getAggregateFunction(NAME, parameterTypes), page);
        assertPercentileWithinError(StandardTypes.DOUBLE, returned, maxError, rows, 0.1, 0.5, 0.9, 0.99);
    }

    private void testAggregationReal(List<TypeSignatureProvider> parameterTypes, Page page, double maxError, float... inputs)
    {
        assertAggregation(
                FUNCTION_RESOLUTION,
                NAME,
                parameterTypes,
                QDIGEST_EQUALITY,
                "test multiple positions",
                page,
                getExpectedValuesFloats(maxError, inputs));

        // test scalars
        List<Double> rows = Floats.asList(inputs).stream().sorted().map(Float::doubleValue).collect(Collectors.toList());

        SqlVarbinary returned = (SqlVarbinary) AggregationTestUtils.aggregation(FUNCTION_RESOLUTION.getAggregateFunction(NAME, parameterTypes), page);
        assertPercentileWithinError(StandardTypes.REAL, returned, maxError, rows, 0.1, 0.5, 0.9, 0.99);
    }

    private Object getExpectedValueLongs(double maxError, long... values)
    {
        if (values.length == 0) {
            return null;
        }
        QuantileDigest qdigest = new QuantileDigest(maxError);
        Arrays.stream(values).forEach(qdigest::add);
        return new SqlVarbinary(qdigest.serialize().getBytes());
    }

    private Object getExpectedValueDoubles(double maxError, double... values)
    {
        if (values.length == 0) {
            return null;
        }
        QuantileDigest qdigest = new QuantileDigest(maxError);
        Arrays.stream(values).forEach(value -> qdigest.add(doubleToSortableLong(value)));
        return new SqlVarbinary(qdigest.serialize().getBytes());
    }

    private Object getExpectedValuesFloats(double maxError, float... values)
    {
        if (values.length == 0) {
            return null;
        }
        QuantileDigest qdigest = new QuantileDigest(maxError);
        Floats.asList(values).forEach(value -> qdigest.add(floatToSortableInt(value)));
        return new SqlVarbinary(qdigest.serialize().getBytes());
    }

    private void assertPercentileWithinError(String type, SqlVarbinary binary, double error, List<? extends Number> rows, double... percentiles)
    {
        if (rows.isEmpty()) {
            // Nothing to assert except that the qdigest is empty
            return;
        }

        // Test each quantile individually (value_at_quantile)
        for (double percentile : percentiles) {
            assertPercentileWithinError(type, binary, error, rows, percentile);
        }

        // Test all the quantiles (values_at_quantiles)
        assertPercentilesWithinError(type, binary, error, rows, percentiles);
    }

    private void assertPercentileWithinError(String type, SqlVarbinary binary, double error, List<? extends Number> rows, double percentile)
    {
        Number lowerBound = getLowerBound(error, rows, percentile);
        Number upperBound = getUpperBound(error, rows, percentile);

        // Check that the chosen quantile is within the upper and lower bound of the error
        functionAssertions.assertFunction(
                format("value_at_quantile(CAST(X'%s' AS qdigest(%s)), %s) >= %s", binary.toString().replaceAll("\\s+", " "), type, percentile, lowerBound),
                BOOLEAN,
                true);
        functionAssertions.assertFunction(
                format("value_at_quantile(CAST(X'%s' AS qdigest(%s)), %s) <= %s", binary.toString().replaceAll("\\s+", " "), type, percentile, upperBound),
                BOOLEAN,
                true);
    }

    private void assertPercentilesWithinError(String type, SqlVarbinary binary, double error, List<? extends Number> rows, double[] percentiles)
    {
        List<Double> boxedPercentiles = Arrays.stream(percentiles).sorted().boxed().collect(toImmutableList());
        List<Number> lowerBounds = boxedPercentiles.stream().map(percentile -> getLowerBound(error, rows, percentile)).collect(toImmutableList());
        List<Number> upperBounds = boxedPercentiles.stream().map(percentile -> getUpperBound(error, rows, percentile)).collect(toImmutableList());

        // Ensure that the lower bound of each item in the distribution is not greater than the chosen quantiles
        functionAssertions.assertFunction(
                format(
                        "zip_with(values_at_quantiles(CAST(X'%s' AS qdigest(%s)), ARRAY[%s]), ARRAY[%s], (value, lowerbound) -> value >= lowerbound)",
                        binary.toString().replaceAll("\\s+", " "),
                        type,
                        ARRAY_JOINER.join(boxedPercentiles),
                        ARRAY_JOINER.join(lowerBounds)),
                new ArrayType(BOOLEAN),
                Collections.nCopies(percentiles.length, true));

        // Ensure that the upper bound of each item in the distribution is not less than the chosen quantiles
        functionAssertions.assertFunction(
                format(
                        "zip_with(values_at_quantiles(CAST(X'%s' AS qdigest(%s)), ARRAY[%s]), ARRAY[%s], (value, upperbound) -> value <= upperbound)",
                        binary.toString().replaceAll("\\s+", " "),
                        type,
                        ARRAY_JOINER.join(boxedPercentiles),
                        ARRAY_JOINER.join(upperBounds)),
                new ArrayType(BOOLEAN),
                Collections.nCopies(percentiles.length, true));
    }

    private Number getLowerBound(double error, List<? extends Number> rows, double percentile)
    {
        int medianIndex = (int) (rows.size() * percentile);
        int marginOfError = (int) (rows.size() * error / 2);
        return rows.get(max(medianIndex - marginOfError, 0));
    }

    private Number getUpperBound(double error, List<? extends Number> rows, double percentile)
    {
        int medianIndex = (int) (rows.size() * percentile);
        int marginOfError = (int) (rows.size() * error / 2);
        return rows.get(min(medianIndex + marginOfError, rows.size() - 1));
    }
}
