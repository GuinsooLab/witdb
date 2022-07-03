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
package io.trino.sql.query;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestGrouping
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
    public void testImplicitCoercions()
    {
        // GROUPING + implicit coercions (see https://github.com/prestodb/presto/issues/8738)
        assertThat(assertions.query(
                "SELECT GROUPING(k), SUM(v) + 1e0 FROM (VALUES (1, 1)) AS t(k,v) GROUP BY k"))
                .matches("VALUES (0, 2e0)");

        assertThat(assertions.query(
                "SELECT\n" +
                        "    1e0 * count(*), " +
                        "    grouping(x) " +
                        "FROM (VALUES 1) t(x) " +
                        "GROUP BY GROUPING SETS ((x), ()) "))
                .matches("VALUES (1e0, 1), (1e0, 0)");
    }

    @Test
    public void testFilter()
    {
        assertThat(assertions.query(
                "SELECT a, b, grouping(a, b) " +
                        "FROM (VALUES ('x0', 'y0'), ('x1', 'y1') ) AS t (a, b) " +
                        "GROUP BY CUBE (a, b)" +
                        "HAVING grouping(a, b) = 0"))
                .matches("VALUES ('x0', 'y0', 0), ('x1', 'y1', 0)");
    }
}
