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
import io.trino.geospatial.KdbTreeUtils;
import io.trino.geospatial.Rectangle;
import io.trino.operator.scalar.AbstractTestFunctions;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.trino.geospatial.KdbTree.buildKdbTree;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;

public class TestKdbTreeCasts
        extends AbstractTestFunctions
{
    @BeforeClass
    public void registerFunctions()
    {
        functionAssertions.installPlugin(new GeoPlugin());
    }

    @Test
    public void test()
    {
        String kdbTreeJson = makeKdbTreeJson();
        assertFunction(format("typeof(cast('%s' AS KdbTree))", kdbTreeJson), VARCHAR, "KdbTree");
        assertFunction(format("typeof(cast('%s' AS KDBTree))", kdbTreeJson), VARCHAR, "KdbTree");
        assertFunction(format("typeof(cast('%s' AS kdbTree))", kdbTreeJson), VARCHAR, "KdbTree");
        assertFunction(format("typeof(cast('%s' AS kdbtree))", kdbTreeJson), VARCHAR, "KdbTree");

        assertInvalidCast("typeof(cast('' AS KdbTree))", "Invalid JSON string for KDB tree");
    }

    private String makeKdbTreeJson()
    {
        ImmutableList.Builder<Rectangle> rectangles = ImmutableList.builder();
        for (double x = 0; x < 10; x += 1) {
            for (double y = 0; y < 5; y += 1) {
                rectangles.add(new Rectangle(x, y, x + 1, y + 2));
            }
        }
        return KdbTreeUtils.toJson(buildKdbTree(100, new Rectangle(0, 0, 9, 4), rectangles.build()));
    }
}
