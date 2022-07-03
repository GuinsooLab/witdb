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
public class TestReduceAgg
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
    public void testInWindowFunction()
    {
        assertThat(assertions.query(
                "SELECT reduce_agg(value, 0, (a, b) -> a + b, (a, b) -> a + b) OVER () " +
                        "FROM (VALUES 1, 2, 3, 4) t(value)"))
                .matches("VALUES 10, 10, 10, 10");

        assertThat(assertions.query(
                "SELECT k, reduce_agg(value, 0, (a, b) -> a + b, (a, b) -> a + b) OVER (PARTITION BY k) " +
                        "FROM (VALUES ('a', 1), ('a', 2), ('b', 3), ('b', 4)) t(k, value)"))
                .matches("VALUES ('a', 3), ('a', 3), ('b', 7), ('b', 7)");
    }
}
