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
import org.apache.commons.math3.stat.correlation.Covariance;

import java.util.List;

import static io.trino.block.BlockAssertions.createSequenceBlockOfReal;
import static io.trino.operator.aggregation.AggregationTestUtils.constructDoublePrimitiveArray;
import static io.trino.spi.type.RealType.REAL;

public class TestRealCovarianceSampAggregation
        extends AbstractTestAggregationFunction
{
    @Override
    protected Block[] getSequenceBlocks(int start, int length)
    {
        return new Block[] {createSequenceBlockOfReal(start, start + length), createSequenceBlockOfReal(start + 5, start + 5 + length)};
    }

    @Override
    protected String getFunctionName()
    {
        return "covar_samp";
    }

    @Override
    protected List<Type> getFunctionParameterTypes()
    {
        return ImmutableList.of(REAL, REAL);
    }

    @Override
    protected Object getExpectedValue(int start, int length)
    {
        if (length <= 1) {
            return null;
        }
        return (float) new Covariance().covariance(constructDoublePrimitiveArray(start + 5, length), constructDoublePrimitiveArray(start, length), true);
    }
}
