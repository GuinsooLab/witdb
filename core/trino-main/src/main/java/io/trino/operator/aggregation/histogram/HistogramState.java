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
package io.trino.operator.aggregation.histogram;

import io.trino.spi.block.Block;
import io.trino.spi.function.AccumulatorState;
import io.trino.spi.function.AccumulatorStateMetadata;

@AccumulatorStateMetadata(
        stateFactoryClass = HistogramStateFactory.class,
        stateSerializerClass = HistogramStateSerializer.class,
        typeParameters = "T",
        serializedType = "map(T, BIGINT)")
public interface HistogramState
        extends AccumulatorState
{
    /**
     * will create an empty histogram if none exists
     *
     * @return histogram based on the type of state (single, grouped). Note that empty histograms will serialize to null as required
     */
    TypedHistogram get();

    void addMemoryUsage(long memory);

    void deserialize(Block block, int expectedSize);
}
