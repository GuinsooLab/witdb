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
import io.airlift.stats.cardinality.HyperLogLog;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.Type;

import java.util.List;

import static io.trino.spi.type.HyperLogLogType.HYPER_LOG_LOG;

public class TestMergeHyperLogLogAggregation
        extends AbstractTestAggregationFunction
{
    private static final int NUMBER_OF_BUCKETS = 16;

    // use dense for expected and actual to assure same serialized bytes

    @Override
    protected Block[] getSequenceBlocks(int start, int length)
    {
        BlockBuilder blockBuilder = HYPER_LOG_LOG.createBlockBuilder(null, length);
        for (int i = start; i < start + length; i++) {
            HyperLogLog hll = HyperLogLog.newInstance(NUMBER_OF_BUCKETS);
            hll.add(i);
            hll.makeDense();
            HYPER_LOG_LOG.writeSlice(blockBuilder, hll.serialize());
        }
        return new Block[] {blockBuilder.build()};
    }

    @Override
    protected String getFunctionName()
    {
        return "merge";
    }

    @Override
    protected List<Type> getFunctionParameterTypes()
    {
        return ImmutableList.of(HYPER_LOG_LOG);
    }

    @Override
    protected Object getExpectedValue(int start, int length)
    {
        if (length == 0) {
            return null;
        }

        HyperLogLog hll = HyperLogLog.newInstance(NUMBER_OF_BUCKETS);
        for (int i = start; i < start + length; i++) {
            hll.add(i);
        }
        hll.makeDense();
        return new SqlVarbinary(hll.serialize().getBytes());
    }
}
