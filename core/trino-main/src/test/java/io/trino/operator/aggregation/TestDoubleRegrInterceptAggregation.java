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

import com.google.common.collect.ImmutableList;
import io.trino.spi.block.Block;
import io.trino.spi.type.Type;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.block.BlockAssertions.createDoubleSequenceBlock;
import static io.trino.block.BlockAssertions.createDoublesBlock;
import static io.trino.spi.type.DoubleType.DOUBLE;

public class TestDoubleRegrInterceptAggregation
        extends AbstractTestAggregationFunction
{
    @Override
    protected Block[] getSequenceBlocks(int start, int length)
    {
        return new Block[] {createDoubleSequenceBlock(start, start + length), createDoubleSequenceBlock(start + 2, start + 2 + length)};
    }

    @Override
    protected String getFunctionName()
    {
        return "regr_intercept";
    }

    @Override
    protected List<Type> getFunctionParameterTypes()
    {
        return ImmutableList.of(DOUBLE, DOUBLE);
    }

    @Override
    protected Object getExpectedValue(int start, int length)
    {
        if (length <= 1) {
            return null;
        }
        SimpleRegression regression = new SimpleRegression();
        for (int i = start; i < start + length; i++) {
            regression.addData(i + 2, i);
        }
        return regression.getIntercept();
    }

    @Test
    public void testNonTrivialResult()
    {
        testNonTrivialAggregation(new Double[] {1.0, 2.0, 3.0, 4.0, 5.0}, new Double[] {1.0, 4.0, 9.0, 16.0, 25.0});
        testNonTrivialAggregation(new Double[] {1.0, 4.0, 9.0, 16.0, 25.0}, new Double[] {1.0, 2.0, 3.0, 4.0, 5.0});
    }

    private void testNonTrivialAggregation(Double[] y, Double[] x)
    {
        SimpleRegression regression = new SimpleRegression();
        for (int i = 0; i < x.length; i++) {
            regression.addData(x[i], y[i]);
        }
        double expected = regression.getIntercept();
        checkArgument(Double.isFinite(expected) && expected != 0., "Expected result is trivial");
        testAggregation(expected, createDoublesBlock(y), createDoublesBlock(x));
    }
}
