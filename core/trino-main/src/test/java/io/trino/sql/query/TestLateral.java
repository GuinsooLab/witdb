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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestLateral
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
    public void testUncorrelatedLateral()
    {
        assertThat(assertions.query(
                "SELECT * FROM LATERAL (VALUES 1, 2, 3)"))
                .matches("VALUES 1, 2, 3");

        assertThat(assertions.query(
                "SELECT * FROM LATERAL (VALUES 1), (VALUES 'a')"))
                .matches("VALUES (1, 'a')");

        assertThat(assertions.query(
                "SELECT * FROM LATERAL (VALUES 1) CROSS JOIN (VALUES 'a')"))
                .matches("VALUES (1, 'a')");

        assertThat(assertions.query(
                "SELECT * FROM LATERAL (VALUES 1) t(a)"))
                .matches("VALUES 1");

        // The nested LATERAL is uncorrelated with respect to the subquery it belongs to. The column comes
        // from the outer query
        assertThat(assertions.query(
                "SELECT * FROM (VALUES 1) t(a), LATERAL (SELECT * FROM LATERAL (SELECT a))"))
                .matches("VALUES (1, 1)");

        assertThat(assertions.query(
                "SELECT (SELECT * FROM LATERAL (SELECT a)) FROM (VALUES 1) t(a)"))
                .matches("VALUES 1");
    }

    @Test
    public void testNotInScope()
    {
        assertThatThrownBy(() -> assertions.query("SELECT * FROM (VALUES 1) t(a), (SELECT * FROM LATERAL (SELECT a))"))
                .hasMessage("line 1:63: Column 'a' cannot be resolved");
    }
}
