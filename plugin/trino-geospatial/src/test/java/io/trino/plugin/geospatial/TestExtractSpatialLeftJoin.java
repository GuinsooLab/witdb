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
package io.trino.plugin.geospatial;

import com.google.common.collect.ImmutableMap;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.ExtractSpatialJoins.ExtractSpatialLeftJoin;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.RuleAssert;
import io.trino.sql.planner.iterative.rule.test.RuleTester;
import org.testng.annotations.Test;

import static io.trino.plugin.geospatial.GeometryType.GEOMETRY;
import static io.trino.plugin.geospatial.SphericalGeographyType.SPHERICAL_GEOGRAPHY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.spatialLeftJoin;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.iterative.rule.test.PlanBuilder.expression;
import static io.trino.sql.planner.plan.JoinNode.Type.LEFT;

public class TestExtractSpatialLeftJoin
        extends BaseRuleTest
{
    public TestExtractSpatialLeftJoin()
    {
        super(new GeoPlugin());
    }

    @Test
    public void testDoesNotFire()
    {
        // scalar expression
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(),
                                p.values(p.symbol("b")),
                                expression("ST_Contains(ST_GeometryFromText('POLYGON ...'), b)")))
                .doesNotFire();

        // OR operand
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                p.values(p.symbol("point", GEOMETRY), p.symbol("name_2")),
                                expression("ST_Contains(ST_GeometryFromText(wkt), point) OR name_1 != name_2")))
                .doesNotFire();

        // NOT operator
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                p.values(p.symbol("point", GEOMETRY), p.symbol("name_2")),
                                expression("NOT ST_Contains(ST_GeometryFromText(wkt), point)")))
                .doesNotFire();

        // ST_Distance(...) > r
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("a", GEOMETRY)),
                                p.values(p.symbol("b", GEOMETRY)),
                                expression("ST_Distance(a, b) > 5")))
                .doesNotFire();

        // SphericalGeography operand
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("a", SPHERICAL_GEOGRAPHY)),
                                p.values(p.symbol("b", SPHERICAL_GEOGRAPHY)),
                                expression("ST_Distance(a, b) < 5")))
                .doesNotFire();

        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("polygon", SPHERICAL_GEOGRAPHY)),
                                p.values(p.symbol("point", SPHERICAL_GEOGRAPHY)),
                                expression("ST_Contains(polygon, point)")))
                .doesNotFire();

        // to_spherical_geography() operand
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR)),
                                p.values(p.symbol("point", SPHERICAL_GEOGRAPHY)),
                                expression("ST_Distance(to_spherical_geography(ST_GeometryFromText(wkt)), point) < 5")))
                .doesNotFire();

        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR)),
                                p.values(p.symbol("point", SPHERICAL_GEOGRAPHY)),
                                expression("ST_Contains(to_spherical_geography(ST_GeometryFromText(wkt)), point)")))
                .doesNotFire();
    }

    @Test
    public void testConvertToSpatialJoin()
    {
        // symbols
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("a")),
                                p.values(p.symbol("b")),
                                expression("ST_Contains(a, b)")))
                .matches(
                        spatialLeftJoin("ST_Contains(a, b)",
                                values(ImmutableMap.of("a", 0)),
                                values(ImmutableMap.of("b", 0))));

        // AND
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("a"), p.symbol("name_1")),
                                p.values(p.symbol("b"), p.symbol("name_2")),
                                expression("name_1 != name_2 AND ST_Contains(a, b)")))
                .matches(
                        spatialLeftJoin("name_1 != name_2 AND ST_Contains(a, b)",
                                values(ImmutableMap.of("a", 0, "name_1", 1)),
                                values(ImmutableMap.of("b", 0, "name_2", 1))));

        // AND
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("a1"), p.symbol("a2")),
                                p.values(p.symbol("b1"), p.symbol("b2")),
                                expression("ST_Contains(a1, b1) AND ST_Contains(a2, b2)")))
                .matches(
                        spatialLeftJoin("ST_Contains(a1, b1) AND ST_Contains(a2, b2)",
                                values(ImmutableMap.of("a1", 0, "a2", 1)),
                                values(ImmutableMap.of("b1", 0, "b2", 1))));
    }

    @Test
    public void testPushDownFirstArgument()
    {
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR)),
                                p.values(p.symbol("point", GEOMETRY)),
                                expression("ST_Contains(ST_GeometryFromText(wkt), point)")))
                .matches(
                        spatialLeftJoin("ST_Contains(st_geometryfromtext, point)",
                                project(ImmutableMap.of("st_geometryfromtext", PlanMatchPattern.expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0))),
                                values(ImmutableMap.of("point", 0))));

        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR)),
                                p.values(),
                                expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(0, 0))")))
                .doesNotFire();
    }

    @Test
    public void testPushDownSecondArgument()
    {
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("polygon", GEOMETRY)),
                                p.values(p.symbol("lat"), p.symbol("lng")),
                                expression("ST_Contains(polygon, ST_Point(lng, lat))")))
                .matches(
                        spatialLeftJoin("ST_Contains(polygon, st_point)",
                                values(ImmutableMap.of("polygon", 0)),
                                project(ImmutableMap.of("st_point", PlanMatchPattern.expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1)))));

        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(),
                                p.values(p.symbol("lat"), p.symbol("lng")),
                                expression("ST_Contains(ST_GeometryFromText('POLYGON ...'), ST_Point(lng, lat))")))
                .doesNotFire();
    }

    @Test
    public void testPushDownBothArguments()
    {
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR)),
                                p.values(p.symbol("lat"), p.symbol("lng")),
                                expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))")))
                .matches(
                        spatialLeftJoin("ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_geometryfromtext", PlanMatchPattern.expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0))),
                                project(ImmutableMap.of("st_point", PlanMatchPattern.expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1)))));
    }

    @Test
    public void testPushDownOppositeOrder()
    {
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("lat"), p.symbol("lng")),
                                p.values(p.symbol("wkt", VARCHAR)),
                                expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))")))
                .matches(
                        spatialLeftJoin("ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_point", PlanMatchPattern.expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1))),
                                project(ImmutableMap.of("st_geometryfromtext", PlanMatchPattern.expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0)))));
    }

    @Test
    public void testPushDownAnd()
    {
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                p.values(p.symbol("lat"), p.symbol("lng"), p.symbol("name_2")),
                                expression("name_1 != name_2 AND ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))")))
                .matches(
                        spatialLeftJoin("name_1 != name_2 AND ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_geometryfromtext", PlanMatchPattern.expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0, "name_1", 1))),
                                project(ImmutableMap.of("st_point", PlanMatchPattern.expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1, "name_2", 2)))));

        // Multiple spatial functions - only the first one is being processed
        assertRuleApplication()
                .on(p ->
                        p.join(LEFT,
                                p.values(p.symbol("wkt1", VARCHAR), p.symbol("wkt2", VARCHAR)),
                                p.values(p.symbol("geometry1"), p.symbol("geometry2")),
                                expression("ST_Contains(ST_GeometryFromText(wkt1), geometry1) AND ST_Contains(ST_GeometryFromText(wkt2), geometry2)")))
                .matches(
                        spatialLeftJoin("ST_Contains(st_geometryfromtext, geometry1) AND ST_Contains(ST_GeometryFromText(wkt2), geometry2)",
                                project(ImmutableMap.of("st_geometryfromtext", PlanMatchPattern.expression("ST_GeometryFromText(wkt1)")), values(ImmutableMap.of("wkt1", 0, "wkt2", 1))),
                                values(ImmutableMap.of("geometry1", 0, "geometry2", 1))));
    }

    private RuleAssert assertRuleApplication()
    {
        RuleTester tester = tester();
        return tester().assertThat(new ExtractSpatialLeftJoin(tester.getPlannerContext(), tester.getSplitManager(), tester.getPageSourceManager(), tester.getTypeAnalyzer()));
    }
}
