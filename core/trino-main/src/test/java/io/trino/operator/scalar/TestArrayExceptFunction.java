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
import io.trino.spi.type.ArrayType;
import org.testng.annotations.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.type.UnknownType.UNKNOWN;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class TestArrayExceptFunction
        extends AbstractTestFunctions
{
    @Test
    public void testBasic()
    {
        assertFunction("array_except(ARRAY[1, 5, 3], ARRAY[3])", new ArrayType(INTEGER), ImmutableList.of(1, 5));
        assertFunction("array_except(ARRAY[CAST(1 as BIGINT), 5, 3], ARRAY[5])", new ArrayType(BIGINT), ImmutableList.of(1L, 3L));
        assertFunction("array_except(ARRAY[VARCHAR 'x', 'y', 'z'], ARRAY['x'])", new ArrayType(VARCHAR), ImmutableList.of("y", "z"));
        assertFunction("array_except(ARRAY[true, false, null], ARRAY[true])", new ArrayType(BOOLEAN), asList(false, null));
        assertFunction("array_except(ARRAY[1.1E0, 5.4E0, 3.9E0], ARRAY[5, 5.4E0])", new ArrayType(DOUBLE), ImmutableList.of(1.1, 3.9));
    }

    @Test
    public void testEmpty()
    {
        assertFunction("array_except(ARRAY[], ARRAY[])", new ArrayType(UNKNOWN), ImmutableList.of());
        assertFunction("array_except(ARRAY[], ARRAY[1, 3])", new ArrayType(INTEGER), ImmutableList.of());
        assertFunction("array_except(ARRAY[VARCHAR 'abc'], ARRAY[])", new ArrayType(VARCHAR), ImmutableList.of("abc"));
    }

    @Test
    public void testNull()
    {
        assertFunction("array_except(ARRAY[NULL], NULL)", new ArrayType(UNKNOWN), null);
        assertFunction("array_except(NULL, NULL)", new ArrayType(UNKNOWN), null);
        assertFunction("array_except(NULL, ARRAY[NULL])", new ArrayType(UNKNOWN), null);
        assertFunction("array_except(ARRAY[NULL], ARRAY[NULL])", new ArrayType(UNKNOWN), ImmutableList.of());
        assertFunction("array_except(ARRAY[], ARRAY[NULL])", new ArrayType(UNKNOWN), ImmutableList.of());
        assertFunction("array_except(ARRAY[NULL], ARRAY[])", new ArrayType(UNKNOWN), singletonList(null));
    }

    @Test
    public void testDuplicates()
    {
        assertFunction("array_except(ARRAY[1, 5, 3, 5, 1], ARRAY[3])", new ArrayType(INTEGER), ImmutableList.of(1, 5));
        assertFunction("array_except(ARRAY[CAST(1 as BIGINT), 5, 5, 3, 3, 3, 1], ARRAY[3, 5])", new ArrayType(BIGINT), ImmutableList.of(1L));
        assertFunction("array_except(ARRAY[VARCHAR 'x', 'x', 'y', 'z'], ARRAY['x', 'y', 'x'])", new ArrayType(VARCHAR), ImmutableList.of("z"));
        assertFunction("array_except(ARRAY[true, false, null, true, false, null], ARRAY[true, true, true])", new ArrayType(BOOLEAN), asList(false, null));
    }

    @Test
    public void testNonDistinctNonEqualValues()
    {
        assertFunction("array_except(ARRAY[NaN()], ARRAY[NaN()])", new ArrayType(DOUBLE), ImmutableList.of());
        assertFunction("array_except(ARRAY[1, NaN(), 3], ARRAY[NaN(), 3])", new ArrayType(DOUBLE), ImmutableList.of(1.0));
    }
}
