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
package io.trino.operator.output;

import io.trino.spi.block.Block;
import io.trino.spi.block.RunLengthEncodedBlock;
import it.unimi.dsi.fastutil.ints.IntArrayList;

public interface PositionsAppender
{
    void append(IntArrayList positions, Block source);

    /**
     * Appends value from the {@code rleBlock} to this appender {@link RunLengthEncodedBlock#getPositionCount()} times.
     * The result is the same as with using {@link PositionsAppender#append(IntArrayList, Block)} with
     * positions list [0...{@link RunLengthEncodedBlock#getPositionCount()} -1]
     * but with possible performance optimizations for {@link RunLengthEncodedBlock}.
     */
    void appendRle(RunLengthEncodedBlock rleBlock);

    /**
     * Creates the block from the appender data.
     * After this, appender is reset to the initial state, and it is ready to build a new block.
     */
    Block build();

    /**
     * Returns number of bytes retained by this instance in memory including over-allocations.
     */
    long getRetainedSizeInBytes();

    /**
     * Returns the size of memory in bytes used by this appender.
     */
    long getSizeInBytes();
}
