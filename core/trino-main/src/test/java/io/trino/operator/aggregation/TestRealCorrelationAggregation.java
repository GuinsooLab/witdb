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
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

import java.util.List;

import static io.trino.block.BlockAssertions.createSequenceBlockOfReal;
import static io.trino.operator.aggregation.AggregationTestUtils.constructDoublePrimitiveArray;
import static io.trino.spi.type.RealType.REAL;

public class TestRealCorrelationAggregation
        extends AbstractTestAggregationFunction
{
    @Override
    protected Block[] getSequenceBlocks(int start, int length)
    {
        return new Block[] {createSequenceBlockOfReal(start, start + length), createSequenceBlockOfReal(start + 2, start + 2 + length)};
    }

    @Override
    protected String getFunctionName()
    {
        return "corr";
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
        PearsonsCorrelation corr = new PearsonsCorrelation();
        return (float) corr.correlation(constructDoublePrimitiveArray(start + 2, length), constructDoublePrimitiveArray(start, length));
    }
}
