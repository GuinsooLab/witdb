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
public class TestSetDigestFunctions
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
    public void testCardinalityForBigintSetDigest()
    {
        assertThat(assertions.query(
                "SELECT cardinality(make_set_digest(value)) " +
                        "FROM (VALUES 1, 2, 2, 3, 3, 3, 4, 4, 4, 4, 5) T(value)"))
                .matches("VALUES CAST(5 AS BIGINT)");
    }

    @Test
    public void testCardinalityForVarcharSetDigest()
    {
        assertThat(assertions.query(
                "SELECT cardinality(make_set_digest(value)) " +
                        "FROM (VALUES 'trino', 'sql', 'everything', 'sql', 'trino') T(value)"))
                .matches("VALUES CAST(3 AS BIGINT)");
    }

    @Test
    public void testCardinalityForDateSetDigest()
    {
        assertThat(assertions.query(
                "SELECT cardinality(make_set_digest(value)) " +
                        "FROM (VALUES DATE '2001-08-22', DATE '2001-08-22', DATE '2001-08-23') T(value)"))
                .matches("VALUES CAST(2 AS BIGINT)");
    }

    @Test
    public void testExactIntersectionCardinality()
    {
        assertThat(assertions.query(
                "SELECT intersection_cardinality(make_set_digest(v1), make_set_digest(v2)) " +
                        "FROM (VALUES (1, 1), (NULL, 2), (2, 3), (3, 4)) T(v1, v2)"))
                .matches("VALUES CAST(3 AS BIGINT)");
    }

    @Test
    public void testJaccardIndex()
    {
        assertThat(assertions.query(
                "SELECT jaccard_index(make_set_digest(v1), make_set_digest(v2)) " +
                        "FROM (VALUES (1, 1), (NULL,2), (2, 3), (NULL, 4)) T(v1, v2)"))
                .matches("VALUES CAST(0.5 AS DOUBLE)");
    }

    @Test
    public void hashCounts()
    {
        assertThat(assertions.query(
                "SELECT hash_counts(make_set_digest(value)) " +
                        "FROM (VALUES 1, 1, 1, 2, 2) T(value)"))
                .matches("VALUES map(cast(ARRAY[19144387141682250, -2447670524089286488] AS array(bigint)), cast(ARRAY[3, 2] AS array(smallint)))");
    }
}
