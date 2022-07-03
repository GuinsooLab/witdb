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
import io.trino.type.BlockTypeOperators.BlockPositionEqual;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * {@link PositionsAppender} that will produce {@link RunLengthEncodedBlock} output if possible,
 * that is all inputs are {@link RunLengthEncodedBlock} blocks with the same value.
 */
public class RleAwarePositionsAppender
        implements PositionsAppender
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(RleAwarePositionsAppender.class).instanceSize();
    private static final int NO_RLE = -1;

    private final BlockPositionEqual equalOperator;
    private final PositionsAppender delegate;

    @Nullable
    private Block rleValue;

    // NO_RLE means flat state, 0 means initial empty state, positive means RLE state and the current RLE position count.
    private int rlePositionCount;

    public RleAwarePositionsAppender(BlockPositionEqual equalOperator, PositionsAppender delegate)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
        this.equalOperator = requireNonNull(equalOperator, "equalOperator is null");
    }

    @Override
    public void append(IntArrayList positions, Block source)
    {
        // RleAwarePositionsAppender should be used with FlatteningPositionsAppender that makes sure
        // append is called only with flat block
        checkArgument(!(source instanceof RunLengthEncodedBlock));
        switchToFlat();
        delegate.append(positions, source);
    }

    @Override
    public void appendRle(RunLengthEncodedBlock source)
    {
        if (source.getPositionCount() == 0) {
            return;
        }

        if (rlePositionCount == 0) {
            // initial empty state, switch to RLE state
            rleValue = source.getValue();
            rlePositionCount = source.getPositionCount();
        }
        else if (rleValue != null) {
            // we are in the RLE state
            if (equalOperator.equalNullSafe(rleValue, 0, source.getValue(), 0)) {
                // the values match. we can just add positions.
                this.rlePositionCount += source.getPositionCount();
                return;
            }
            // RLE values do not match. switch to flat state
            switchToFlat();
            delegate.appendRle(source);
        }
        else {
            // flat state
            delegate.appendRle(source);
        }
    }

    @Override
    public Block build()
    {
        Block result;
        if (rleValue != null) {
            result = new RunLengthEncodedBlock(rleValue, rlePositionCount);
        }
        else {
            result = delegate.build();
        }

        reset();
        return result;
    }

    private void reset()
    {
        rleValue = null;
        rlePositionCount = 0;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        long retainedRleSize = rleValue != null ? rleValue.getRetainedSizeInBytes() : 0;
        return INSTANCE_SIZE + retainedRleSize + delegate.getRetainedSizeInBytes();
    }

    @Override
    public long getSizeInBytes()
    {
        long rleSize = rleValue != null ? rleValue.getSizeInBytes() : 0;
        return rleSize + delegate.getSizeInBytes();
    }

    private void switchToFlat()
    {
        if (rleValue != null) {
            // we are in the RLE state, flatten all RLE blocks
            delegate.appendRle(new RunLengthEncodedBlock(rleValue, rlePositionCount));
            rleValue = null;
        }
        rlePositionCount = NO_RLE;
    }
}
