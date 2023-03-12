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
import com.google.common.collect.ImmutableMap;
import io.trino.spi.type.ArrayType;
import io.trino.sql.query.QueryAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.type.UnknownType.UNKNOWN;
import static io.trino.util.StructuralTestUtil.mapType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestMapTransformValuesFunction
{
    private QueryAssertions assertions;

    @BeforeAll
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterAll
    public void teardown()
    {
        assertions.close();
        assertions = null;
    }

    @Test
    public void testEmpty()
    {
        assertThat(assertions.expression("transform_values(a, (k, v) -> NULL)")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, UNKNOWN))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> k)")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, UNKNOWN))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> v)")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, UNKNOWN))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> 0)")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, INTEGER))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> true)")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, BOOLEAN))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> 'value')")
                .binding("a", "map(ARRAY[], ARRAY[])"))
                .hasType(mapType(UNKNOWN, createVarcharType(5)))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> k + CAST(v as BIGINT))")
                .binding("a", "CAST (map(ARRAY[], ARRAY[]) AS MAP(BIGINT,VARCHAR))"))
                .hasType(mapType(BIGINT, BIGINT))
                .isEqualTo(ImmutableMap.of());

        assertThat(assertions.expression("transform_values(a, (k, v) -> CAST(k AS VARCHAR) || v)")
                .binding("a", "CAST (map(ARRAY[], ARRAY[]) AS MAP(BIGINT,VARCHAR))"))
                .hasType(mapType(BIGINT, VARCHAR))
                .isEqualTo(ImmutableMap.of());
    }

    @Test
    public void testNullValue()
    {
        Map<Integer, Void> sequenceToNullMap = new HashMap<>();
        sequenceToNullMap.put(1, null);
        sequenceToNullMap.put(2, null);
        sequenceToNullMap.put(3, null);
        assertThat(assertions.expression("transform_values(a, (k, v) -> NULL)")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY['a', 'b', 'c'])"))
                .hasType(mapType(INTEGER, UNKNOWN))
                .isEqualTo(sequenceToNullMap);

        Map<Integer, String> mapWithNullValue = new HashMap<>();
        mapWithNullValue.put(1, "a");
        mapWithNullValue.put(2, "b");
        mapWithNullValue.put(3, null);
        assertThat(assertions.expression("transform_values(a, (k, v) -> v)")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY['a', 'b', NULL])"))
                .hasType(mapType(INTEGER, createVarcharType(1)))
                .isEqualTo(mapWithNullValue);

        assertThat(assertions.expression("transform_values(a, (k, v) -> to_base(v, 16))")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY[10, 11, NULL])"))
                .hasType(mapType(INTEGER, createVarcharType(64)))
                .isEqualTo(mapWithNullValue);

        assertThat(assertions.expression("transform_values(a, (k, v) -> to_base(TRY_CAST(v as BIGINT), 16))")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY['10', '11', 'Invalid'])"))
                .hasType(mapType(INTEGER, createVarcharType(64)))
                .isEqualTo(mapWithNullValue);

        assertThat(assertions.expression("transform_values(a, (k, v) -> element_at(map(ARRAY[1, 2], ARRAY['a', 'b']), k + v))")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY[0, 0, 0])"))
                .hasType(mapType(INTEGER, createVarcharType(1)))
                .isEqualTo(mapWithNullValue);

        assertThat(assertions.expression("transform_values(a, (k, v) -> IF(v IS NULL, k + 1.0E0, k + 0.5E0))")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY['a', 'b', NULL])"))
                .hasType(mapType(INTEGER, DOUBLE))
                .isEqualTo(ImmutableMap.of(1, 1.5, 2, 2.5, 3, 4.0));
    }

    @Test
    public void testBasic()
    {
        assertThat(assertions.expression("transform_values(a, (k, v) -> k + v)")
                .binding("a", "map(ARRAY[1, 2, 3, 4], ARRAY[10, 20, 30, 40])"))
                .hasType(mapType(INTEGER, INTEGER))
                .isEqualTo(ImmutableMap.of(1, 11, 2, 22, 3, 33, 4, 44));

        assertThat(assertions.expression("transform_values(a, (k, v) -> v * v)")
                .binding("a", "map(ARRAY['a', 'b', 'c', 'd'], ARRAY[1, 2, 3, 4])"))
                .hasType(mapType(createVarcharType(1), INTEGER))
                .isEqualTo(ImmutableMap.of("a", 1, "b", 4, "c", 9, "d", 16));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || CAST(v as VARCHAR))")
                .binding("a", "map(ARRAY['a', 'b', 'c', 'd'], ARRAY[1, 2, 3, 4])"))
                .hasType(mapType(createVarcharType(1), VARCHAR))
                .isEqualTo(ImmutableMap.of("a", "a1", "b", "b2", "c", "c3", "d", "d4"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> map(ARRAY[1, 2, 3], ARRAY['one', 'two', 'three'])[k] || '_' || CAST(v AS VARCHAR))")
                .binding("a", "map(ARRAY[1, 2, 3], ARRAY[1.0E0, 1.4E0, 1.7E0])"))
                .hasType(mapType(INTEGER, VARCHAR))
                .isEqualTo(ImmutableMap.of(1, "one_1.0E0", 2, "two_1.4E0", 3, "three_1.7E0"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> date_add('year', 1, v))")
                .binding("a", "map(ARRAY[1, 2], ARRAY[TIMESTAMP '2020-05-10 12:34:56.123456789', TIMESTAMP '2010-05-10 12:34:56.123456789'])"))
                .matches("map_from_entries(ARRAY[(1, TIMESTAMP '2021-05-10 12:34:56.123456789'), (2, TIMESTAMP '2011-05-10 12:34:56.123456789')])");
    }

    @Test
    public void testTypeCombinations()
    {
        assertThat(assertions.expression("transform_values(a, (k, v) -> k + v)")
                .binding("a", "map(ARRAY[25, 26, 27], ARRAY[25, 26, 27])"))
                .hasType(mapType(INTEGER, INTEGER))
                .isEqualTo(ImmutableMap.of(25, 50, 26, 52, 27, 54));

        assertThat(assertions.expression("transform_values(a, (k, v) -> CAST(v - k AS BIGINT))")
                .binding("a", "map(ARRAY[25, 26, 27], ARRAY[26.1E0, 31.2E0, 37.1E0])"))
                .hasType(mapType(INTEGER, BIGINT))
                .isEqualTo(ImmutableMap.of(25, 1L, 26, 5L, 27, 10L));

        assertThat(assertions.expression("transform_values(a, (k, v) -> if(v, k + 1, k + 2))")
                .binding("a", "map(ARRAY[25, 27], ARRAY[false, true])"))
                .hasType(mapType(INTEGER, INTEGER))
                .isEqualTo(ImmutableMap.of(25, 27, 27, 28));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k + length(v))")
                .binding("a", "map(ARRAY[25, 26, 27], ARRAY['abc', 'd', 'xy'])"))
                .hasType(mapType(INTEGER, BIGINT))
                .isEqualTo(ImmutableMap.of(25, 28L, 26, 27L, 27, 29L));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k + cardinality(v))")
                .binding("a", "map(ARRAY[25, 26, 27], ARRAY[ARRAY['a'], ARRAY['a', 'c'], ARRAY['a', 'b', 'c']])"))
                .hasType(mapType(INTEGER, BIGINT))
                .isEqualTo(ImmutableMap.of(25, 26L, 26, 28L, 27, 30L));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k - v)")
                .binding("a", "map(ARRAY[25.5E0, 26.75E0, 27.875E0], ARRAY[25, 26, 27])"))
                .hasType(mapType(DOUBLE, DOUBLE))
                .isEqualTo(ImmutableMap.of(25.5, 0.5, 26.75, 0.75, 27.875, 0.875));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k - v)")
                .binding("a", "map(ARRAY[25.5E0, 26.75E0, 27.875E0], ARRAY[25.0E0, 26.0E0, 27.0E0])"))
                .hasType(mapType(DOUBLE, DOUBLE))
                .isEqualTo(ImmutableMap.of(25.5, 0.5, 26.75, 0.75, 27.875, 0.875));

        assertThat(assertions.expression("transform_values(a, (k, v) -> if(v, k + 0.1E0, k + 0.2E0))")
                .binding("a", "map(ARRAY[25.5E0, 27.5E0], ARRAY[false, true])"))
                .hasType(mapType(DOUBLE, DOUBLE))
                .isEqualTo(ImmutableMap.of(25.5, 25.7, 27.5, 27.6));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k + length(v))")
                .binding("a", "map(ARRAY[25.5E0, 26.5E0, 27.5E0], ARRAY['a', 'def', 'xy'])"))
                .hasType(mapType(DOUBLE, DOUBLE))
                .isEqualTo(ImmutableMap.of(25.5, 26.5, 26.5, 29.5, 27.5, 29.5));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k + cardinality(v))")
                .binding("a", "map(ARRAY[25.5E0, 26.5E0, 27.5E0], ARRAY[ARRAY['a'], ARRAY['a', 'c'], ARRAY['a', 'b', 'c']])"))
                .hasType(mapType(DOUBLE, DOUBLE))
                .isEqualTo(ImmutableMap.of(25.5, 26.5, 26.5, 28.5, 27.5, 30.5));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k AND v = 25)")
                .binding("a", "map(ARRAY[true, false], ARRAY[25, 26])"))
                .hasType(mapType(BOOLEAN, BOOLEAN))
                .isEqualTo(ImmutableMap.of(true, true, false, false));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k OR v > 100)")
                .binding("a", "map(ARRAY[false, true], ARRAY[25.5E0, 26.5E0])"))
                .hasType(mapType(BOOLEAN, BOOLEAN))
                .isEqualTo(ImmutableMap.of(false, false, true, true));

        assertThat(assertions.expression("transform_values(a, (k, v) -> NOT k OR v)")
                .binding("a", "map(ARRAY[true, false], ARRAY[false, null])"))
                .hasType(mapType(BOOLEAN, BOOLEAN))
                .isEqualTo(ImmutableMap.of(false, true, true, false));

        assertThat(assertions.expression("transform_values(a, (k, v) -> NOT k AND v = 'abc')")
                .binding("a", "map(ARRAY[false, true], ARRAY['abc', 'def'])"))
                .hasType(mapType(BOOLEAN, BOOLEAN))
                .isEqualTo(ImmutableMap.of(false, true, true, false));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k OR cardinality(v) = 3)")
                .binding("a", "map(ARRAY[true, false], ARRAY[ARRAY['a', 'b'], ARRAY['a', 'b', 'c']])"))
                .hasType(mapType(BOOLEAN, BOOLEAN))
                .isEqualTo(ImmutableMap.of(false, true, true, true));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || ':' || CAST(v as VARCHAR))")
                .binding("a", "map(ARRAY['s0', 's1', 's2'], ARRAY[25, 26, 27])"))
                .hasType(mapType(createVarcharType(2), VARCHAR))
                .isEqualTo(ImmutableMap.of("s0", "s0:25", "s1", "s1:26", "s2", "s2:27"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || ':' || CAST(v as VARCHAR))")
                .binding("a", "map(ARRAY['s0', 's1', 's2'], ARRAY[25.5E0, 26.5E0, 27.5E0])"))
                .hasType(mapType(createVarcharType(2), VARCHAR))
                .isEqualTo(ImmutableMap.of("s0", "s0:2.55E1", "s1", "s1:2.65E1", "s2", "s2:2.75E1"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> if(v, k, CAST(v AS VARCHAR)))")
                .binding("a", "map(ARRAY['s0', 's2'], ARRAY[false, true])"))
                .hasType(mapType(createVarcharType(2), VARCHAR))
                .isEqualTo(ImmutableMap.of("s0", "false", "s2", "s2"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || ':' || v)")
                .binding("a", "map(ARRAY['s0', 's1', 's2'], ARRAY['abc', 'def', 'xyz'])"))
                .hasType(mapType(createVarcharType(2), VARCHAR))
                .isEqualTo(ImmutableMap.of("s0", "s0:abc", "s1", "s1:def", "s2", "s2:xyz"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || ':' || array_max(v))")
                .binding("a", "map(ARRAY['s0', 's1', 's2'], ARRAY[ARRAY['a', 'b'], ARRAY['a', 'c'], ARRAY['a', 'b', 'c']])"))
                .hasType(mapType(createVarcharType(2), VARCHAR))
                .isEqualTo(ImmutableMap.of("s0", "s0:b", "s1", "s1:c", "s2", "s2:c"));

        assertThat(assertions.expression("transform_values(a, (k, v) -> if(v % 2 = 0, reverse(k), k))")
                .binding("a", "map(ARRAY[ARRAY[1, 2], ARRAY[3, 4]], ARRAY[25, 26])"))
                .hasType(mapType(new ArrayType(INTEGER), new ArrayType(INTEGER)))
                .isEqualTo(ImmutableMap.of(ImmutableList.of(1, 2), ImmutableList.of(1, 2), ImmutableList.of(3, 4), ImmutableList.of(4, 3)));

        assertThat(assertions.expression("transform_values(a, (k, v) -> CAST(k AS ARRAY(DOUBLE)) || v)")
                .binding("a", "map(ARRAY[ARRAY[1, 2], ARRAY[3, 4]], ARRAY[25.5E0, 26.5E0])"))
                .hasType(mapType(new ArrayType(INTEGER), new ArrayType(DOUBLE)))
                .isEqualTo(ImmutableMap.of(ImmutableList.of(1, 2), ImmutableList.of(1., 2., 25.5), ImmutableList.of(3, 4), ImmutableList.of(3., 4., 26.5)));

        assertThat(assertions.expression("transform_values(a, (k, v) -> if(v, reverse(k), k))")
                .binding("a", "map(ARRAY[ARRAY[1, 2], ARRAY[3, 4]], ARRAY[false, true])"))
                .hasType(mapType(new ArrayType(INTEGER), new ArrayType(INTEGER)))
                .isEqualTo(ImmutableMap.of(ImmutableList.of(1, 2), ImmutableList.of(1, 2), ImmutableList.of(3, 4), ImmutableList.of(4, 3)));

        assertThat(assertions.expression("transform_values(a, (k, v) -> k || from_base(v, 16))")
                .binding("a", "map(ARRAY[ARRAY[1, 2], ARRAY[]], ARRAY['a', 'ff'])"))
                .hasType(mapType(new ArrayType(INTEGER), new ArrayType(BIGINT)))
                .isEqualTo(ImmutableMap.of(ImmutableList.of(1, 2), ImmutableList.of(1L, 2L, 10L), ImmutableList.of(), ImmutableList.of(255L)));

        assertThat(assertions.expression("transform_values(a, (k, v) -> transform(k, x -> CAST(x AS VARCHAR)) || v)")
                .binding("a", "map(ARRAY[ARRAY[3, 4], ARRAY[]], ARRAY[ARRAY['a', 'b', 'c'], ARRAY['a', 'c']])"))
                .hasType(mapType(new ArrayType(INTEGER), new ArrayType(VARCHAR)))
                .isEqualTo(ImmutableMap.of(ImmutableList.of(3, 4), ImmutableList.of("3", "4", "a", "b", "c"), ImmutableList.of(), ImmutableList.of("a", "c")));
    }
}
