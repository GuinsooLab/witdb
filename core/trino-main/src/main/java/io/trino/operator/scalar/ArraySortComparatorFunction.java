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
package io.trino.operator.scalar;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.trino.spi.PageBuilder;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.function.TypeParameter;
import io.trino.spi.function.TypeParameterSpecialization;
import io.trino.spi.type.Type;
import io.trino.sql.gen.lambda.LambdaFunctionInterface;

import java.util.Comparator;
import java.util.List;

import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.util.Failures.checkCondition;

@ScalarFunction("array_sort")
@Description("Sorts the given array with a lambda comparator.")
public final class ArraySortComparatorFunction
{
    private final PageBuilder pageBuilder;
    private static final int INITIAL_LENGTH = 128;
    private List<Integer> positions = Ints.asList(new int[INITIAL_LENGTH]);

    @TypeParameter("T")
    public ArraySortComparatorFunction(@TypeParameter("T") Type elementType)
    {
        pageBuilder = new PageBuilder(ImmutableList.of(elementType));
    }

    @TypeParameter("T")
    @TypeParameterSpecialization(name = "T", nativeContainerType = long.class)
    @SqlType("array(T)")
    public Block sortLong(
            @TypeParameter("T") Type type,
            @SqlType("array(T)") Block block,
            @SqlType("function(T, T, integer)") ComparatorLongLambda function)
    {
        int arrayLength = block.getPositionCount();
        initPositionsList(arrayLength);

        Comparator<Integer> comparator = (x, y) -> comparatorResult(function.apply(
                block.isNull(x) ? null : type.getLong(block, x),
                block.isNull(y) ? null : type.getLong(block, y)));

        sortPositions(arrayLength, comparator);

        return computeResultBlock(type, block, arrayLength);
    }

    @TypeParameter("T")
    @TypeParameterSpecialization(name = "T", nativeContainerType = double.class)
    @SqlType("array(T)")
    public Block sortDouble(
            @TypeParameter("T") Type type,
            @SqlType("array(T)") Block block,
            @SqlType("function(T, T, integer)") ComparatorDoubleLambda function)
    {
        int arrayLength = block.getPositionCount();
        initPositionsList(arrayLength);

        Comparator<Integer> comparator = (x, y) -> comparatorResult(function.apply(
                block.isNull(x) ? null : type.getDouble(block, x),
                block.isNull(y) ? null : type.getDouble(block, y)));

        sortPositions(arrayLength, comparator);

        return computeResultBlock(type, block, arrayLength);
    }

    @TypeParameter("T")
    @TypeParameterSpecialization(name = "T", nativeContainerType = boolean.class)
    @SqlType("array(T)")
    public Block sortBoolean(
            @TypeParameter("T") Type type,
            @SqlType("array(T)") Block block,
            @SqlType("function(T, T, integer)") ComparatorBooleanLambda function)
    {
        int arrayLength = block.getPositionCount();
        initPositionsList(arrayLength);

        Comparator<Integer> comparator = (x, y) -> comparatorResult(function.apply(
                block.isNull(x) ? null : type.getBoolean(block, x),
                block.isNull(y) ? null : type.getBoolean(block, y)));

        sortPositions(arrayLength, comparator);

        return computeResultBlock(type, block, arrayLength);
    }

    @TypeParameter("T")
    @TypeParameterSpecialization(name = "T", nativeContainerType = Object.class)
    @SqlType("array(T)")
    public Block sortObject(
            @TypeParameter("T") Type type,
            @SqlType("array(T)") Block block,
            @SqlType("function(T, T, integer)") ComparatorObjectLambda function)
    {
        int arrayLength = block.getPositionCount();
        initPositionsList(arrayLength);

        Comparator<Integer> comparator = (x, y) -> comparatorResult(function.apply(
                block.isNull(x) ? null : type.getObject(block, x),
                block.isNull(y) ? null : type.getObject(block, y)));

        sortPositions(arrayLength, comparator);

        return computeResultBlock(type, block, arrayLength);
    }

    private void initPositionsList(int arrayLength)
    {
        if (positions.size() < arrayLength) {
            positions = Ints.asList(new int[arrayLength]);
        }
        for (int i = 0; i < arrayLength; i++) {
            positions.set(i, i);
        }
    }

    private void sortPositions(int arrayLength, Comparator<Integer> comparator)
    {
        List<Integer> list = positions.subList(0, arrayLength);

        try {
            list.sort(comparator);
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "Lambda comparator violates the comparator contract", e);
        }
    }

    private Block computeResultBlock(Type type, Block block, int arrayLength)
    {
        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        BlockBuilder blockBuilder = pageBuilder.getBlockBuilder(0);

        for (int i = 0; i < arrayLength; ++i) {
            type.appendTo(block, positions.get(i), blockBuilder);
        }
        pageBuilder.declarePositions(arrayLength);

        return blockBuilder.getRegion(blockBuilder.getPositionCount() - arrayLength, arrayLength);
    }

    private static int comparatorResult(Long result)
    {
        checkCondition(
                (result != null) && ((result == -1) || (result == 0) || (result == 1)),
                INVALID_FUNCTION_ARGUMENT,
                "Lambda comparator must return either -1, 0, or 1");
        return result.intValue();
    }

    @FunctionalInterface
    public interface ComparatorLongLambda
            extends LambdaFunctionInterface
    {
        Long apply(Long x, Long y);
    }

    @FunctionalInterface
    public interface ComparatorDoubleLambda
            extends LambdaFunctionInterface
    {
        Long apply(Double x, Double y);
    }

    @FunctionalInterface
    public interface ComparatorBooleanLambda
            extends LambdaFunctionInterface
    {
        Long apply(Boolean x, Boolean y);
    }

    @FunctionalInterface
    public interface ComparatorObjectLambda
            extends LambdaFunctionInterface
    {
        Long apply(Object x, Object y);
    }
}
