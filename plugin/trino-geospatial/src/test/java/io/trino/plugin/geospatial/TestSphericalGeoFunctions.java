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

import com.google.common.collect.ImmutableList;
import io.trino.operator.scalar.AbstractTestFunctions;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.io.Resources.getResource;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.geospatial.SphericalGeographyType.SPHERICAL_GEOGRAPHY;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;

public class TestSphericalGeoFunctions
        extends AbstractTestFunctions
{
    @BeforeClass
    public void registerFunctions()
    {
        functionAssertions.installPlugin(new GeoPlugin());
    }

    @Test
    public void testGetObjectValue()
    {
        List<String> wktList = ImmutableList.of(
                "POINT EMPTY",
                "MULTIPOINT EMPTY",
                "LINESTRING EMPTY",
                "MULTILINESTRING EMPTY",
                "POLYGON EMPTY",
                "MULTIPOLYGON EMPTY",
                "GEOMETRYCOLLECTION EMPTY",
                "POINT (-40.2 28.9)",
                "MULTIPOINT ((-40.2 28.9), (-40.2 31.9))",
                "LINESTRING (-40.2 28.9, -40.2 31.9, -37.2 31.9)",
                "MULTILINESTRING ((-40.2 28.9, -40.2 31.9), (-40.2 31.9, -37.2 31.9))",
                "POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9))",
                "POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9), (-39.2 29.9, -39.2 30.9, -38.2 30.9, -38.2 29.9, -39.2 29.9))",
                "MULTIPOLYGON (((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9)), ((-39.2 29.9, -38.2 29.9, -38.2 30.9, -39.2 30.9, -39.2 29.9)))",
                "GEOMETRYCOLLECTION (POINT (-40.2 28.9), LINESTRING (-40.2 28.9, -40.2 31.9, -37.2 31.9), POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9)))");

        BlockBuilder builder = SPHERICAL_GEOGRAPHY.createBlockBuilder(null, wktList.size());
        for (String wkt : wktList) {
            SPHERICAL_GEOGRAPHY.writeSlice(builder, GeoFunctions.toSphericalGeography(GeoFunctions.stGeometryFromText(utf8Slice(wkt))));
        }
        Block block = builder.build();
        for (int i = 0; i < wktList.size(); i++) {
            assertEquals(wktList.get(i), SPHERICAL_GEOGRAPHY.getObjectValue(null, block, i));
        }
    }

    @Test
    public void testToAndFromSphericalGeography()
    {
        // empty geometries
        assertToAndFromSphericalGeography("POINT EMPTY");
        assertToAndFromSphericalGeography("MULTIPOINT EMPTY");
        assertToAndFromSphericalGeography("LINESTRING EMPTY");
        assertToAndFromSphericalGeography("MULTILINESTRING EMPTY");
        assertToAndFromSphericalGeography("POLYGON EMPTY");
        assertToAndFromSphericalGeography("MULTIPOLYGON EMPTY");
        assertToAndFromSphericalGeography("GEOMETRYCOLLECTION EMPTY");

        // valid nonempty geometries
        assertToAndFromSphericalGeography("POINT (-40.2 28.9)");
        assertToAndFromSphericalGeography("MULTIPOINT ((-40.2 28.9), (-40.2 31.9))");
        assertToAndFromSphericalGeography("LINESTRING (-40.2 28.9, -40.2 31.9, -37.2 31.9)");
        assertToAndFromSphericalGeography("MULTILINESTRING ((-40.2 28.9, -40.2 31.9), (-40.2 31.9, -37.2 31.9))");
        assertToAndFromSphericalGeography("POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9))");
        assertToAndFromSphericalGeography("POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9), " +
                "(-39.2 29.9, -39.2 30.9, -38.2 30.9, -38.2 29.9, -39.2 29.9))");
        assertToAndFromSphericalGeography("MULTIPOLYGON (((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9)), " +
                "((-39.2 29.9, -38.2 29.9, -38.2 30.9, -39.2 30.9, -39.2 29.9)))");
        assertToAndFromSphericalGeography("GEOMETRYCOLLECTION (POINT (-40.2 28.9), LINESTRING (-40.2 28.9, -40.2 31.9, -37.2 31.9), " +
                "POLYGON ((-40.2 28.9, -37.2 28.9, -37.2 31.9, -40.2 31.9, -40.2 28.9)))");

        // geometries containing invalid latitude or longitude values
        assertInvalidLongitude("POINT (-340.2 28.9)");
        assertInvalidLatitude("MULTIPOINT ((-40.2 128.9), (-40.2 31.9))");
        assertInvalidLongitude("LINESTRING (-40.2 28.9, -40.2 31.9, 237.2 31.9)");
        assertInvalidLatitude("MULTILINESTRING ((-40.2 28.9, -40.2 31.9), (-40.2 131.9, -37.2 31.9))");
        assertInvalidLongitude("POLYGON ((-40.2 28.9, -40.2 31.9, 237.2 31.9, -37.2 28.9, -40.2 28.9))");
        assertInvalidLatitude("POLYGON ((-40.2 28.9, -40.2 31.9, -37.2 131.9, -37.2 28.9, -40.2 28.9), (-39.2 29.9, -39.2 30.9, -38.2 30.9, -38.2 29.9, -39.2 29.9))");
        assertInvalidLongitude("MULTIPOLYGON (((-40.2 28.9, -40.2 31.9, -37.2 31.9, -37.2 28.9, -40.2 28.9)), " +
                "((-39.2 29.9, -39.2 30.9, 238.2 30.9, -38.2 29.9, -39.2 29.9)))");
        assertInvalidLatitude("GEOMETRYCOLLECTION (POINT (-40.2 28.9), LINESTRING (-40.2 28.9, -40.2 131.9, -37.2 31.9), " +
                "POLYGON ((-40.2 28.9, -40.2 31.9, -37.2 31.9, -37.2 28.9, -40.2 28.9)))");
    }

    private void assertToAndFromSphericalGeography(String wkt)
    {
        assertFunction(format("ST_AsText(to_geometry(to_spherical_geography(ST_GeometryFromText('%s'))))", wkt), VARCHAR, wkt);
    }

    private void assertInvalidLongitude(String wkt)
    {
        assertInvalidFunction(format("to_spherical_geography(ST_GeometryFromText('%s'))", wkt), "Longitude must be between -180 and 180");
    }

    private void assertInvalidLatitude(String wkt)
    {
        assertInvalidFunction(format("to_spherical_geography(ST_GeometryFromText('%s'))", wkt), "Latitude must be between -90 and 90");
    }

    @Test
    public void testDistance()
    {
        assertDistance("POINT (-86.67 36.12)", "POINT (-118.40 33.94)", 2886448.9734367016);
        assertDistance("POINT (-118.40 33.94)", "POINT (-86.67 36.12)", 2886448.9734367016);
        assertDistance("POINT (-71.0589 42.3601)", "POINT (-71.2290 42.4430)", 16734.69743457383);
        assertDistance("POINT (-86.67 36.12)", "POINT (-86.67 36.12)", 0.0);

        assertDistance("POINT EMPTY", "POINT (40 30)", null);
        assertDistance("POINT (20 10)", "POINT EMPTY", null);
        assertDistance("POINT EMPTY", "POINT EMPTY", null);
    }

    private void assertDistance(String wkt, String otherWkt, Double expectedDistance)
    {
        assertFunction(format("ST_Distance(to_spherical_geography(ST_GeometryFromText('%s')), to_spherical_geography(ST_GeometryFromText('%s')))", wkt, otherWkt), DOUBLE, expectedDistance);
    }

    @Test
    public void testArea()
            throws Exception
    {
        // Empty polygon
        assertFunction("ST_Area(to_spherical_geography(ST_GeometryFromText('POLYGON EMPTY')))", DOUBLE, null);

        // Invalid polygon (too few vertices)
        assertInvalidFunction("ST_Area(to_spherical_geography(ST_GeometryFromText('POLYGON((90 0, 0 0))')))", "Polygon is not valid: a loop contains less then 3 vertices.");

        // Invalid data type (point)
        assertInvalidFunction("ST_Area(to_spherical_geography(ST_GeometryFromText('POINT (0 1)')))", "When applied to SphericalGeography inputs, ST_Area only supports POLYGON or MULTI_POLYGON. Input type is: POINT");

        //Invalid Polygon (duplicated point)
        assertInvalidFunction("ST_Area(to_spherical_geography(ST_GeometryFromText('POLYGON((0 0, 0 1, 1 1, 1 1, 1 0, 0 0))')))", "Polygon is not valid: it has two identical consecutive vertices");

        // A polygon around the North Pole
        assertArea("POLYGON((-135 85, -45 85, 45 85, 135 85, -135 85))", 619.00E9);

        assertArea("POLYGON((0 0, 0 1, 1 1, 1 0))", 123.64E8);

        assertArea("POLYGON((-122.150124 37.486095, -122.149201 37.486606,  -122.145725 37.486580, -122.145923 37.483961 , -122.149324 37.482480 ,  -122.150837 37.483238,  -122.150901 37.485392))", 163290.93943446054);

        double angleOfOneKm = 0.008993201943349;
        assertArea(format("POLYGON((0 0, %.15f 0, %.15f %.15f, 0 %.15f))", angleOfOneKm, angleOfOneKm, angleOfOneKm, angleOfOneKm), 1E6);

        // 1/4th of an hemisphere, ie 1/8th of the planet, should be close to 4PiR2/8 = 637.58E11
        assertArea("POLYGON((90 0, 0 0, 0 90))", 637.58E11);

        //A Polygon with a large hole
        assertArea("POLYGON((90 0, 0 0, 0 90), (89 1, 1 1, 1 89))", 348.04E10);

        Path geometryPath = new File(getResource("us-states.tsv").toURI()).toPath();
        Map<String, String> stateGeometries;
        try (Stream<String> lines = Files.lines(geometryPath)) {
            stateGeometries = lines
                    .map(line -> line.split("\t"))
                    .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        }

        Path areaPath = new File(getResource("us-state-areas.tsv").toURI()).toPath();
        Map<String, Double> stateAreas;
        try (Stream<String> lines = Files.lines(areaPath)) {
            stateAreas = lines
                    .map(line -> line.split("\t"))
                    .filter(parts -> parts.length >= 2)
                    .collect(Collectors.toMap(parts -> parts[0], parts -> Double.valueOf(parts[1])));
        }

        for (String state : stateGeometries.keySet()) {
            assertArea(stateGeometries.get(state), stateAreas.get(state));
        }
    }

    private void assertArea(String wkt, double expectedArea)
    {
        assertFunction(format("ABS(ROUND((ST_Area(to_spherical_geography(ST_GeometryFromText('%s'))) / %f - 1 ) * %d, 0))", wkt, expectedArea, 10000), DOUBLE, 0.0);
    }
}
