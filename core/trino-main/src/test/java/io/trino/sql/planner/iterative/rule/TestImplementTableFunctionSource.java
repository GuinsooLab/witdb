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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.sql.planner.OrderingScheme;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.DataOrganizationSpecification;
import io.trino.sql.planner.plan.TableFunctionNode.PassThroughColumn;
import io.trino.sql.planner.plan.TableFunctionNode.PassThroughSpecification;
import io.trino.sql.planner.plan.TableFunctionNode.TableArgumentProperties;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.spi.connector.SortOrder.ASC_NULLS_LAST;
import static io.trino.spi.connector.SortOrder.DESC_NULLS_FIRST;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.functionCall;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.specification;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableFunctionProcessor;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.assertions.PlanMatchPattern.window;
import static io.trino.sql.planner.plan.JoinNode.Type.FULL;
import static io.trino.sql.planner.plan.JoinNode.Type.INNER;
import static io.trino.sql.planner.plan.JoinNode.Type.LEFT;

public class TestImplementTableFunctionSource
        extends BaseRuleTest
{
    @Test
    public void testNoSources()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> p.tableFunction(
                        "test_function",
                        ImmutableList.of(p.symbol("a")),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of()))
                .matches(tableFunctionProcessor(builder -> builder
                        .name("test_function")
                        .properOutputs(ImmutableList.of("a"))));
    }

    @Test
    public void testSingleSourceWithRowSemantics()
    {
        // no pass-through columns
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(p.values(c)),
                            ImmutableList.of(new TableArgumentProperties(
                                    "table_argument",
                                    true,
                                    true,
                                    new PassThroughSpecification(false, ImmutableList.of()),
                                    ImmutableList.of(c),
                                    Optional.empty())),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b")),
                        values("c")));

        // pass-through columns
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(p.values(c)),
                            ImmutableList.of(new TableArgumentProperties(
                                    "table_argument",
                                    true,
                                    true,
                                    new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(c, false))),
                                    ImmutableList.of(c),
                                    Optional.empty())),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c")),
                        values("c")));
    }

    @Test
    public void testSingleSourceWithSetSemantics()
    {
        // no pass-through columns, no partition by
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(p.values(c, d)),
                            ImmutableList.of(new TableArgumentProperties(
                                    "table_argument",
                                    false,
                                    false,
                                    new PassThroughSpecification(false, ImmutableList.of()),
                                    ImmutableList.of(c, d),
                                    Optional.of(new DataOrganizationSpecification(ImmutableList.of(), Optional.of(new OrderingScheme(ImmutableList.of(d), ImmutableMap.of(d, ASC_NULLS_LAST))))))),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .specification(specification(ImmutableList.of(), ImmutableList.of("d"), ImmutableMap.of("d", ASC_NULLS_LAST))),
                        values("c", "d")));

        // no pass-through columns, partitioning column present
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(p.values(c, d)),
                            ImmutableList.of(new TableArgumentProperties(
                                    "table_argument",
                                    false,
                                    false,
                                    new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                    ImmutableList.of(c, d),
                                    Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.of(new OrderingScheme(ImmutableList.of(d), ImmutableMap.of(d, ASC_NULLS_LAST))))))),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c"))
                                .specification(specification(ImmutableList.of("c"), ImmutableList.of("d"), ImmutableMap.of("d", ASC_NULLS_LAST))),
                        values("c", "d")));

        // pass-through columns
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(p.values(c, d)),
                            ImmutableList.of(new TableArgumentProperties(
                                    "table_argument",
                                    false,
                                    false,
                                    new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(c, true), new PassThroughColumn(d, false))),
                                    ImmutableList.of(d),
                                    Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty())))),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d"))
                                .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of())),
                        values("c", "d")));
    }

    @Test
    public void testTwoSourcesWithSetSemantics()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c, d),
                                    p.values(e, f)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, false), new PassThroughColumn(f, false))),
                                            ImmutableList.of(f),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(), Optional.empty())))),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2"))
                                .specification(specification(ImmutableList.of("c"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper symbols for joined nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)")),
                                        join(// join nodes using helper symbols
                                                FULL,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                input_1_row_number = input_2_row_number OR
                                                                input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1'
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c", "d")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("e", "f"))))))));
    }

    @Test
    public void testThreeSourcesWithSetSemantics()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    Symbol g = p.symbol("g");
                    Symbol h = p.symbol("h");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c, d),
                                    p.values(e, f),
                                    p.values(g, h)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, false), new PassThroughColumn(f, false))),
                                            ImmutableList.of(f),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_3",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of()),
                                            ImmutableList.of(h),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(), Optional.of(new OrderingScheme(ImmutableList.of(h), ImmutableMap.of(h, DESC_NULLS_FIRST))))))),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2",
                                        "g", "marker_3",
                                        "h", "marker_3"))
                                .specification(specification(ImmutableList.of("c"), ImmutableList.of("combined_row_number_1_2_3"), ImmutableMap.of("combined_row_number_1_2_3", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number_1_2_3, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number_1_2_3, input_2_row_number, CAST(null AS bigint))"),
                                        "marker_3", expression("IF(input_3_row_number = combined_row_number_1_2_3, input_3_row_number, CAST(null AS bigint))")),
                                project(// append helper symbols for joined nodes
                                        ImmutableMap.of(
                                                "combined_row_number_1_2_3", expression("IF(COALESCE(combined_row_number_1_2, BIGINT '-1') > COALESCE(input_3_row_number, BIGINT '-1'), combined_row_number_1_2, input_3_row_number)"),
                                                "combined_partition_size_1_2_3", expression("IF(COALESCE(combined_partition_size_1_2, BIGINT '-1') > COALESCE(input_3_partition_size, BIGINT '-1'), combined_partition_size_1_2, input_3_partition_size)")),
                                        join(// join nodes using helper symbols
                                                FULL,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                combined_row_number_1_2 = input_3_row_number OR
                                                                combined_row_number_1_2 > input_3_partition_size AND input_3_row_number = BIGINT '1' OR
                                                                input_3_row_number > combined_partition_size_1_2 AND combined_row_number_1_2 = BIGINT '1'
                                                                """)
                                                        .left(project(// append helper symbols for joined nodes
                                                                ImmutableMap.of(
                                                                        "combined_row_number_1_2", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                                        "combined_partition_size_1_2", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)")),
                                                                join(// join nodes using helper symbols
                                                                        FULL,
                                                                        nestedJoinBuilder -> nestedJoinBuilder
                                                                                .filter("""
                                                                                        input_1_row_number = input_2_row_number OR
                                                                                        input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                                        input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1'
                                                                                        """)
                                                                                .left(window(// append helper symbols for source input_1
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_1
                                                                                        values("c", "d")))
                                                                                .right(window(// append helper symbols for source input_2
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_2
                                                                                        values("e", "f"))))))
                                                        .right(window(// append helper symbols for source input_3
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of(), ImmutableList.of("h"), ImmutableMap.of("h", DESC_NULLS_FIRST)))
                                                                        .addFunction("input_3_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_3_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_3
                                                                values("g", "h"))))))));
    }

    @Test
    public void testTwoCoPartitionedSources()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c, d),
                                    p.values(e, f)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c, d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, true), new PassThroughColumn(f, false))),
                                            ImmutableList.of(f),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e), Optional.of(new OrderingScheme(ImmutableList.of(f), ImmutableMap.of(f, DESC_NULLS_FIRST))))))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(c, e)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c IS DISTINCT FROM e)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c", "d")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("e"), ImmutableList.of("f"), ImmutableMap.of("f", DESC_NULLS_FIRST)))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("e", "f"))))))));
    }

    @Test
    public void testCoPartitionJoinTypes()
    {
        // both sources are prune when empty, so they are combined using inner join
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(c, d)")),
                                        join(// co-partition nodes
                                                INNER,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c IS DISTINCT FROM d)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("d"))))))));

        // only the left source is prune when empty, so sources are combined using left join
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(c, d)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c IS DISTINCT FROM d)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("d"))))))));

        // only the right source is prune when empty. the sources are reordered so that the prune when empty source is first. they are combined using left join
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_2_row_number, BIGINT '-1') > COALESCE(input_1_row_number, BIGINT '-1'), input_2_row_number, input_1_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_2_partition_size, BIGINT '-1') > COALESCE(input_1_partition_size, BIGINT '-1'), input_2_partition_size, input_1_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(d, c)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (d IS DISTINCT FROM c)
                                                                AND (
                                                                     input_2_row_number = input_1_row_number OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1' OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("d")))
                                                        .right(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c"))))))));

        // neither source is prune when empty, so sources are combined using full join
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(c, d)")),
                                        join(// co-partition nodes
                                                FULL,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c IS DISTINCT FROM d)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("d"))))))));
    }

    @Test
    public void testThreeCoPartitionedSources()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d),
                                    p.values(e)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_3",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(e, true))),
                                            ImmutableList.of(e),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2", "input_3")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d", "e"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2",
                                        "e", "marker_3"))
                                .specification(specification(ImmutableList.of("combined_partition_column_1_2_3"), ImmutableList.of("combined_row_number_1_2_3"), ImmutableMap.of("combined_row_number_1_2_3", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number_1_2_3, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number_1_2_3, input_2_row_number, CAST(null AS bigint))"),
                                        "marker_3", expression("IF(input_3_row_number = combined_row_number_1_2_3, input_3_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number_1_2_3", expression("IF(COALESCE(combined_row_number_1_2, BIGINT '-1') > COALESCE(input_3_row_number, BIGINT '-1'), combined_row_number_1_2, input_3_row_number)"),
                                                "combined_partition_size_1_2_3", expression("IF(COALESCE(combined_partition_size_1_2, BIGINT '-1') > COALESCE(input_3_partition_size, BIGINT '-1'), combined_partition_size_1_2, input_3_partition_size)"),
                                                "combined_partition_column_1_2_3", expression("COALESCE(combined_partition_column_1_2, e)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (combined_partition_column_1_2 IS DISTINCT FROM e)
                                                                AND (
                                                                     combined_row_number_1_2 = input_3_row_number OR
                                                                     combined_row_number_1_2 > input_3_partition_size AND input_3_row_number = BIGINT '1' OR
                                                                     input_3_row_number > combined_partition_size_1_2 AND combined_row_number_1_2 = BIGINT '1')
                                                                """)
                                                        .left(project(// append helper and partitioning symbols for co-partitioned nodes
                                                                ImmutableMap.of(
                                                                        "combined_row_number_1_2", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                                        "combined_partition_size_1_2", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                                        "combined_partition_column_1_2", expression("COALESCE(c, d)")),
                                                                join(// co-partition nodes
                                                                        INNER,
                                                                        nestedJoinBuilder -> nestedJoinBuilder
                                                                                .filter("""
                                                                                        NOT (c IS DISTINCT FROM d)
                                                                                        AND (
                                                                                             input_1_row_number = input_2_row_number OR
                                                                                             input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                                             input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                                        """)
                                                                                .left(window(// append helper symbols for source input_1
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_1
                                                                                        values("c")))
                                                                                .right(window(// append helper symbols for source input_2
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_2
                                                                                        values("d"))))))
                                                        .right(window(// append helper symbols for source input_3
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("e"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_3_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_3_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_3
                                                                values("e"))))))));
    }

    @Test
    public void testTwoCoPartitionLists()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    Symbol g = p.symbol("g");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d),
                                    p.values(e),
                                    p.values(f, g)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_3",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(e, true))),
                                            ImmutableList.of(e),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_4",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(f, true))),
                                            ImmutableList.of(g),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(f), Optional.of(new OrderingScheme(ImmutableList.of(g), ImmutableMap.of(g, DESC_NULLS_FIRST))))))),
                            ImmutableList.of(
                                    ImmutableList.of("input_1", "input_2"),
                                    ImmutableList.of("input_3", "input_4")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2",
                                        "e", "marker_3",
                                        "f", "marker_4",
                                        "g", "marker_4"))
                                .specification(specification(ImmutableList.of("combined_partition_column_1_2", "combined_partition_column_3_4"), ImmutableList.of("combined_row_number_1_2_3_4"), ImmutableMap.of("combined_row_number_1_2_3_4", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number_1_2_3_4, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number_1_2_3_4, input_2_row_number, CAST(null AS bigint))"),
                                        "marker_3", expression("IF(input_3_row_number = combined_row_number_1_2_3_4, input_3_row_number, CAST(null AS bigint))"),
                                        "marker_4", expression("IF(input_4_row_number = combined_row_number_1_2_3_4, input_4_row_number, CAST(null AS bigint))")),
                                project(// append helper symbols for joined nodes
                                        ImmutableMap.of(
                                                "combined_row_number_1_2_3_4", expression("IF(COALESCE(combined_row_number_1_2, BIGINT '-1') > COALESCE(combined_row_number_3_4, BIGINT '-1'), combined_row_number_1_2, combined_row_number_3_4)"),
                                                "combined_partition_size_1_2_3_4", expression("IF(COALESCE(combined_partition_size_1_2, BIGINT '-1') > COALESCE(combined_partition_size_3_4, BIGINT '-1'), combined_partition_size_1_2, combined_partition_size_3_4)")),
                                        join(// join nodes using helper symbols
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                combined_row_number_1_2 = combined_row_number_3_4 OR
                                                                combined_row_number_1_2 > combined_partition_size_3_4 AND combined_row_number_3_4 = BIGINT '1' OR
                                                                combined_row_number_3_4 > combined_partition_size_1_2 AND combined_row_number_1_2 = BIGINT '1'
                                                                """)
                                                        .left(project(// append helper and partitioning symbols for co-partitioned nodes
                                                                ImmutableMap.of(
                                                                        "combined_row_number_1_2", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                                        "combined_partition_size_1_2", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                                        "combined_partition_column_1_2", expression("COALESCE(c, d)")),
                                                                join(// co-partition nodes
                                                                        INNER,
                                                                        nestedJoinBuilder -> nestedJoinBuilder
                                                                                .filter("""
                                                                                        NOT (c IS DISTINCT FROM d)
                                                                                        AND (
                                                                                             input_1_row_number = input_2_row_number OR
                                                                                             input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                                             input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                                        """)
                                                                                .left(window(// append helper symbols for source input_1
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_1
                                                                                        values("c")))
                                                                                .right(window(// append helper symbols for source input_2
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_2
                                                                                        values("d"))))))
                                                        .right(project(// append helper and partitioning symbols for co-partitioned nodes
                                                                ImmutableMap.of(
                                                                        "combined_row_number_3_4", expression("IF(COALESCE(input_3_row_number, BIGINT '-1') > COALESCE(input_4_row_number, BIGINT '-1'), input_3_row_number, input_4_row_number)"),
                                                                        "combined_partition_size_3_4", expression("IF(COALESCE(input_3_partition_size, BIGINT '-1') > COALESCE(input_4_partition_size, BIGINT '-1'), input_3_partition_size, input_4_partition_size)"),
                                                                        "combined_partition_column_3_4", expression("COALESCE(e, f)")),
                                                                join(// co-partition nodes
                                                                        FULL,
                                                                        nestedJoinBuilder -> nestedJoinBuilder
                                                                                .filter("""
                                                                                        NOT (e IS DISTINCT FROM f)
                                                                                        AND (
                                                                                             input_3_row_number = input_4_row_number OR
                                                                                             input_3_row_number > input_4_partition_size AND input_4_row_number = BIGINT '1' OR
                                                                                             input_4_row_number > input_3_partition_size AND input_3_row_number = BIGINT '1')
                                                                                        """)
                                                                                .left(window(// append helper symbols for source input_3
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("e"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_3_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_3_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_3
                                                                                        values("e")))
                                                                                .right(window(// append helper symbols for source input_4
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("f"), ImmutableList.of("g"), ImmutableMap.of("g", DESC_NULLS_FIRST)))
                                                                                                .addFunction("input_4_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_4_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_4
                                                                                        values("f", "g")))))))))));
    }

    @Test
    public void testCoPartitionedAndNotCoPartitionedSources()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c),
                                    p.values(d),
                                    p.values(e)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(d, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(d), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_3",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(e, true))),
                                            ImmutableList.of(e),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_2", "input_3")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d", "e"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_2",
                                        "e", "marker_3"))
                                .specification(specification(ImmutableList.of("combined_partition_column_2_3", "c"), ImmutableList.of("combined_row_number_2_3_1"), ImmutableMap.of("combined_row_number_2_3_1", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number_2_3_1, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number_2_3_1, input_2_row_number, CAST(null AS bigint))"),
                                        "marker_3", expression("IF(input_3_row_number = combined_row_number_2_3_1, input_3_row_number, CAST(null AS bigint))")),
                                project(// append helper symbols for joined nodes
                                        ImmutableMap.of(
                                                "combined_row_number_2_3_1", expression("IF(COALESCE(combined_row_number_2_3, BIGINT '-1') > COALESCE(input_1_row_number, BIGINT '-1'), combined_row_number_2_3, input_1_row_number)"),
                                                "combined_partition_size_2_3_1", expression("IF(COALESCE(combined_partition_size_2_3, BIGINT '-1') > COALESCE(input_1_partition_size, BIGINT '-1'), combined_partition_size_2_3, input_1_partition_size)")),
                                        join(// join nodes using helper symbols
                                                INNER,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                combined_row_number_2_3 = input_1_row_number OR
                                                                combined_row_number_2_3 > input_1_partition_size AND input_1_row_number = BIGINT '1' OR
                                                                input_1_row_number > combined_partition_size_2_3 AND combined_row_number_2_3 = BIGINT '1'
                                                                """)
                                                        .left(project(// append helper and partitioning symbols for co-partitioned nodes
                                                                ImmutableMap.of(
                                                                        "combined_row_number_2_3", expression("IF(COALESCE(input_2_row_number, BIGINT '-1') > COALESCE(input_3_row_number, BIGINT '-1'), input_2_row_number, input_3_row_number)"),
                                                                        "combined_partition_size_2_3", expression("IF(COALESCE(input_2_partition_size, BIGINT '-1') > COALESCE(input_3_partition_size, BIGINT '-1'), input_2_partition_size, input_3_partition_size)"),
                                                                        "combined_partition_column_2_3", expression("COALESCE(d, e)")),
                                                                join(// co-partition nodes
                                                                        LEFT,
                                                                        nestedJoinBuilder -> nestedJoinBuilder
                                                                                .filter("""
                                                                                        NOT (d IS DISTINCT FROM e)
                                                                                        AND (
                                                                                             input_2_row_number = input_3_row_number OR
                                                                                             input_2_row_number > input_3_partition_size AND input_3_row_number = BIGINT '1' OR
                                                                                             input_3_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1')
                                                                                        """)
                                                                                .left(window(// append helper symbols for source input_2
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("d"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_2
                                                                                        values("d")))
                                                                                .right(window(// append helper symbols for source input_3
                                                                                        builder -> builder
                                                                                                .specification(specification(ImmutableList.of("e"), ImmutableList.of(), ImmutableMap.of()))
                                                                                                .addFunction("input_3_row_number", functionCall("row_number", ImmutableList.of()))
                                                                                                .addFunction("input_3_partition_size", functionCall("count", ImmutableList.of())),
                                                                                        // input_3
                                                                                        values("e"))))))
                                                        .right(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c"))))))));
    }

    @Test
    public void testCoerceForCopartitioning()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c", TINYINT);
                    Symbol cCoerced = p.symbol("c_coerced", INTEGER);
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e", INTEGER);
                    Symbol f = p.symbol("f");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    // coerce column c for co-partitioning
                                    p.project(
                                            Assignments.builder()
                                                    .put(c, PlanBuilder.expression("c"))
                                                    .put(d, PlanBuilder.expression("d"))
                                                    .put(cCoerced, PlanBuilder.expression("CAST(c AS INTEGER)"))
                                                    .build(),
                                            p.values(c, d)),
                                    p.values(e, f)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(c, d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(cCoerced), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, true), new PassThroughColumn(f, false))),
                                            ImmutableList.of(f),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e), Optional.of(new OrderingScheme(ImmutableList.of(f), ImmutableMap.of(f, DESC_NULLS_FIRST))))))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "c_coerced", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column", expression("COALESCE(c_coerced, e)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c_coerced IS DISTINCT FROM e)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c_coerced"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                project(
                                                                        ImmutableMap.of("c_coerced", expression("CAST(c AS INTEGER)")),
                                                                        values("c", "d"))))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("e"), ImmutableList.of("f"), ImmutableMap.of("f", DESC_NULLS_FIRST)))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("e", "f"))))))));
    }

    @Test
    public void testTwoCoPartitioningColumns()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c, d),
                                    p.values(e, f)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            true,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true), new PassThroughColumn(d, true))),
                                            ImmutableList.of(c),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c, d), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            false,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, true), new PassThroughColumn(f, true))),
                                            ImmutableList.of(e),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(e, f), Optional.empty())))),
                            ImmutableList.of(ImmutableList.of("input_1", "input_2")));
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "d", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2"))
                                .specification(specification(ImmutableList.of("combined_partition_column_1", "combined_partition_column_2"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper and partitioning symbols for co-partitioned nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)"),
                                                "combined_partition_column_1", expression("COALESCE(c, e)"),
                                                "combined_partition_column_2", expression("COALESCE(d, f)")),
                                        join(// co-partition nodes
                                                LEFT,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                NOT (c IS DISTINCT FROM e)
                                                                AND NOT (d IS DISTINCT FROM f)
                                                                AND (
                                                                     input_1_row_number = input_2_row_number OR
                                                                     input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                     input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1')
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c", "d"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c", "d")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("e", "f"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("e", "f"))))))));
    }

    @Test
    public void testTwoSourcesWithRowAndSetSemantics()
    {
        tester().assertThat(new ImplementTableFunctionSource(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    Symbol d = p.symbol("d");
                    Symbol e = p.symbol("e");
                    Symbol f = p.symbol("f");
                    return p.tableFunction(
                            "test_function",
                            ImmutableList.of(a, b),
                            ImmutableList.of(
                                    p.values(c, d),
                                    p.values(e, f)),
                            ImmutableList.of(
                                    new TableArgumentProperties(
                                            "input_1",
                                            false,
                                            false,
                                            new PassThroughSpecification(false, ImmutableList.of(new PassThroughColumn(c, true))),
                                            ImmutableList.of(d),
                                            Optional.of(new DataOrganizationSpecification(ImmutableList.of(c), Optional.empty()))),
                                    new TableArgumentProperties(
                                            "input_2",
                                            true,
                                            false,
                                            new PassThroughSpecification(true, ImmutableList.of(new PassThroughColumn(e, false), new PassThroughColumn(f, false))),
                                            ImmutableList.of(e),
                                            Optional.empty())),
                            ImmutableList.of());
                })
                .matches(PlanMatchPattern.tableFunctionProcessor(builder -> builder
                                .name("test_function")
                                .properOutputs(ImmutableList.of("a", "b"))
                                .passThroughSymbols(ImmutableSet.of("c", "e", "f"))
                                .markerSymbols(ImmutableMap.of(
                                        "c", "marker_1",
                                        "d", "marker_1",
                                        "e", "marker_2",
                                        "f", "marker_2"))
                                .specification(specification(ImmutableList.of("c"), ImmutableList.of("combined_row_number"), ImmutableMap.of("combined_row_number", ASC_NULLS_LAST))),
                        project(// append marker symbols
                                ImmutableMap.of(
                                        "marker_1", expression("IF(input_1_row_number = combined_row_number, input_1_row_number, CAST(null AS bigint))"),
                                        "marker_2", expression("IF(input_2_row_number = combined_row_number, input_2_row_number, CAST(null AS bigint))")),
                                project(// append helper symbols for joined nodes
                                        ImmutableMap.of(
                                                "combined_row_number", expression("IF(COALESCE(input_1_row_number, BIGINT '-1') > COALESCE(input_2_row_number, BIGINT '-1'), input_1_row_number, input_2_row_number)"),
                                                "combined_partition_size", expression("IF(COALESCE(input_1_partition_size, BIGINT '-1') > COALESCE(input_2_partition_size, BIGINT '-1'), input_1_partition_size, input_2_partition_size)")),
                                        join(// join nodes using helper symbols
                                                FULL,
                                                joinBuilder -> joinBuilder
                                                        .filter("""
                                                                input_1_row_number = input_2_row_number OR
                                                                input_1_row_number > input_2_partition_size AND input_2_row_number = BIGINT '1' OR
                                                                input_2_row_number > input_1_partition_size AND input_1_row_number = BIGINT '1'
                                                                """)
                                                        .left(window(// append helper symbols for source input_1
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of("c"), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_1_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_1_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_1
                                                                values("c", "d")))
                                                        .right(window(// append helper symbols for source input_2
                                                                builder -> builder
                                                                        .specification(specification(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of()))
                                                                        .addFunction("input_2_row_number", functionCall("row_number", ImmutableList.of()))
                                                                        .addFunction("input_2_partition_size", functionCall("count", ImmutableList.of())),
                                                                // input_2
                                                                values("e", "f"))))))));
    }
}
