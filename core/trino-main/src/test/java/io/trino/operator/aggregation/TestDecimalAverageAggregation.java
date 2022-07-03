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
import io.trino.operator.aggregation.state.LongDecimalWithOverflowAndLongState;
import io.trino.operator.aggregation.state.LongDecimalWithOverflowAndLongStateFactory;
import io.trino.operator.aggregation.state.LongDecimalWithOverflowState;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Int128;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;

import static io.trino.operator.aggregation.DecimalAverageAggregation.average;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.TEN;
import static java.math.BigInteger.ZERO;
import static java.math.RoundingMode.HALF_UP;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestDecimalAverageAggregation
{
    private static final BigInteger TWO = new BigInteger("2");
    private static final BigInteger ONE_HUNDRED = new BigInteger("100");
    private static final BigInteger TWO_HUNDRED = new BigInteger("200");
    private static final DecimalType TYPE = createDecimalType(38, 0);

    private LongDecimalWithOverflowAndLongState state;

    @BeforeMethod
    public void setUp()
    {
        state = new LongDecimalWithOverflowAndLongStateFactory().createSingleState();
    }

    @Test
    public void testOverflow()
    {
        addToState(state, TWO.pow(126));

        assertEquals(state.getLong(), 1);
        assertEquals(state.getOverflow(), 0);
        assertEquals(getDecimal(state), Int128.valueOf(TWO.pow(126)));

        addToState(state, TWO.pow(126));

        assertEquals(state.getLong(), 2);
        assertEquals(state.getOverflow(), 1);
        assertEquals(getDecimal(state), Int128.valueOf(1L << 63, 0));

        assertAverageEquals(TWO.pow(126));
    }

    @Test
    public void testUnderflow()
    {
        addToState(state, Decimals.MIN_UNSCALED_DECIMAL.toBigInteger());

        assertEquals(state.getLong(), 1);
        assertEquals(state.getOverflow(), 0);
        assertEquals(getDecimal(state), Decimals.MIN_UNSCALED_DECIMAL);

        addToState(state, Decimals.MIN_UNSCALED_DECIMAL.toBigInteger());

        assertEquals(state.getLong(), 2);
        assertEquals(state.getOverflow(), -1);
        assertEquals(getDecimal(state), Int128.valueOf(0x698966AF4AF2770BL, 0xECEBBB8000000002L));

        assertAverageEquals(Decimals.MIN_UNSCALED_DECIMAL.toBigInteger());
    }

    @Test
    public void testUnderflowAfterOverflow()
    {
        addToState(state, TWO.pow(126));
        addToState(state, TWO.pow(126));
        addToState(state, TWO.pow(125));

        assertEquals(state.getOverflow(), 1);
        assertEquals(getDecimal(state), Int128.valueOf((1L << 63) | (1L << 61), 0));

        addToState(state, TWO.pow(126).negate());
        addToState(state, TWO.pow(126).negate());
        addToState(state, TWO.pow(126).negate());

        assertEquals(state.getOverflow(), 0);
        assertEquals(getDecimal(state), Int128.valueOf(TWO.pow(125).negate()));

        assertAverageEquals(TWO.pow(125).negate().divide(BigInteger.valueOf(6)));
    }

    @Test
    public void testCombineOverflow()
    {
        addToState(state, TWO.pow(126));
        addToState(state, TWO.pow(126));

        LongDecimalWithOverflowAndLongState otherState = new LongDecimalWithOverflowAndLongStateFactory().createSingleState();

        addToState(otherState, TWO.pow(126));
        addToState(otherState, TWO.pow(126));

        DecimalAverageAggregation.combine(state, otherState);
        assertEquals(state.getLong(), 4);
        assertEquals(state.getOverflow(), 1);
        assertEquals(getDecimal(state), Int128.ZERO);

        BigInteger expectedAverage = BigInteger.ZERO
                .add(TWO.pow(126))
                .add(TWO.pow(126))
                .add(TWO.pow(126))
                .add(TWO.pow(126))
                .divide(BigInteger.valueOf(4));

        assertAverageEquals(expectedAverage);
    }

    @Test
    public void testCombineUnderflow()
    {
        addToState(state, TWO.pow(125).negate());
        addToState(state, TWO.pow(126).negate());

        LongDecimalWithOverflowAndLongState otherState = new LongDecimalWithOverflowAndLongStateFactory().createSingleState();

        addToState(otherState, TWO.pow(125).negate());
        addToState(otherState, TWO.pow(126).negate());

        DecimalAverageAggregation.combine(state, otherState);
        assertEquals(state.getLong(), 4);
        assertEquals(state.getOverflow(), -1);
        assertEquals(getDecimal(state), Int128.valueOf(1L << 62, 0));

        BigInteger expectedAverage = BigInteger.ZERO
                .add(TWO.pow(126))
                .add(TWO.pow(126))
                .add(TWO.pow(125))
                .add(TWO.pow(125))
                .negate()
                .divide(BigInteger.valueOf(4));

        assertAverageEquals(expectedAverage);
    }

    @Test(dataProvider = "testNoOverflowDataProvider")
    public void testNoOverflow(List<BigInteger> numbers)
    {
        testNoOverflow(createDecimalType(38, 0), numbers);
        testNoOverflow(createDecimalType(38, 2), numbers);
    }

    private void testNoOverflow(DecimalType type, List<BigInteger> numbers)
    {
        LongDecimalWithOverflowAndLongState state = new LongDecimalWithOverflowAndLongStateFactory().createSingleState();
        for (BigInteger number : numbers) {
            addToState(type, state, number);
        }

        assertEquals(state.getOverflow(), 0);
        BigInteger sum = numbers.stream().reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(getDecimal(state), Int128.valueOf(sum));

        BigDecimal expectedAverage = new BigDecimal(sum, type.getScale()).divide(BigDecimal.valueOf(numbers.size()), type.getScale(), HALF_UP);
        assertEquals(decodeBigDecimal(type, average(state, type)), expectedAverage);
    }

    @DataProvider
    public static Object[][] testNoOverflowDataProvider()
    {
        return new Object[][] {
                {ImmutableList.of(TEN.pow(37), ZERO)},
                {ImmutableList.of(TEN.pow(37).negate(), ZERO)},
                {ImmutableList.of(TWO, ONE)},
                {ImmutableList.of(ZERO, ONE)},
                {ImmutableList.of(TWO.negate(), ONE.negate())},
                {ImmutableList.of(ONE.negate(), ZERO)},
                {ImmutableList.of(ONE.negate(), ZERO, ZERO)},
                {ImmutableList.of(TWO.negate(), ZERO, ZERO)},
                {ImmutableList.of(TWO.negate(), ZERO)},
                {ImmutableList.of(TWO_HUNDRED, ONE_HUNDRED)},
                {ImmutableList.of(ZERO, ONE_HUNDRED)},
                {ImmutableList.of(TWO_HUNDRED.negate(), ONE_HUNDRED.negate())},
                {ImmutableList.of(ONE_HUNDRED.negate(), ZERO)}
        };
    }

    private static BigDecimal decodeBigDecimal(DecimalType type, Int128 average)
    {
        BigInteger unscaledVal = average.toBigInteger();
        return new BigDecimal(unscaledVal, type.getScale(), new MathContext(type.getPrecision()));
    }

    private void assertAverageEquals(BigInteger expectedAverage)
    {
        assertAverageEquals(expectedAverage, TYPE);
    }

    private void assertAverageEquals(BigInteger expectedAverage, DecimalType type)
    {
        assertEquals(average(state, type).toBigInteger(), expectedAverage);
    }

    private static void addToState(LongDecimalWithOverflowAndLongState state, BigInteger value)
    {
        addToState(TYPE, state, value);
    }

    private static void addToState(DecimalType type, LongDecimalWithOverflowAndLongState state, BigInteger value)
    {
        BlockBuilder blockBuilder = type.createFixedSizeBlockBuilder(1);
        type.writeObject(blockBuilder, Int128.valueOf(value));
        if (type.isShort()) {
            DecimalAverageAggregation.inputShortDecimal(state, blockBuilder.build(), 0);
        }
        else {
            DecimalAverageAggregation.inputLongDecimal(state, blockBuilder.build(), 0);
        }
    }

    private Int128 getDecimal(LongDecimalWithOverflowState state)
    {
        long[] decimal = state.getDecimalArray();
        int offset = state.getDecimalArrayOffset();

        return Int128.valueOf(decimal[offset], decimal[offset + 1]);
    }
}
