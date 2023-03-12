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

import io.trino.sql.query.QueryAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.assertions.TrinoExceptionAssert.assertTrinoExceptionThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestConcatWsFunction
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
    public void testSimple()
    {
        assertThat(assertions.function("concat_ws", "'abc'", "'def'"))
                .hasType(VARCHAR)
                .isEqualTo("def");

        assertThat(assertions.function("concat_ws", "','", "'def'"))
                .hasType(VARCHAR)
                .isEqualTo("def");

        assertThat(assertions.function("concat_ws", "','", "'def'", "'pqr'", "'mno'"))
                .hasType(VARCHAR)
                .isEqualTo("def,pqr,mno");

        assertThat(assertions.function("concat_ws", "'abc'", "'def'", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("defabcpqr");
    }

    @Test
    public void testEmpty()
    {
        assertThat(assertions.function("concat_ws", "''", "'def'"))
                .hasType(VARCHAR)
                .isEqualTo("def");

        assertThat(assertions.function("concat_ws", "''", "'def'", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("defpqr");

        assertThat(assertions.function("concat_ws", "''", "''", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("pqr");

        assertThat(assertions.function("concat_ws", "''", "'def'", "''"))
                .hasType(VARCHAR)
                .isEqualTo("def");

        assertThat(assertions.function("concat_ws", "''", "''", "''"))
                .hasType(VARCHAR)
                .isEqualTo("");

        assertThat(assertions.function("concat_ws", "','", "'def'", "''"))
                .hasType(VARCHAR)
                .isEqualTo("def,");

        assertThat(assertions.function("concat_ws", "','", "'def'", "''", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("def,,pqr");

        assertThat(assertions.function("concat_ws", "','", "''", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo(",pqr");
    }

    @Test
    public void testNull()
    {
        assertThat(assertions.function("concat_ws", "NULL", "'def'"))
                .isNull(VARCHAR);

        assertThat(assertions.function("concat_ws", "NULL", "cast(NULL as VARCHAR)"))
                .isNull(VARCHAR);

        assertThat(assertions.function("concat_ws", "NULL", "'def'", "'pqr'"))
                .isNull(VARCHAR);

        assertThat(assertions.function("concat_ws", "','", "cast(NULL as VARCHAR)"))
                .hasType(VARCHAR)
                .isEqualTo("");

        assertThat(assertions.function("concat_ws", "','", "NULL", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("pqr");

        assertThat(assertions.function("concat_ws", "','", "'def'", "NULL"))
                .hasType(VARCHAR)
                .isEqualTo("def");

        assertThat(assertions.function("concat_ws", "','", "'def'", "NULL", "'pqr'"))
                .hasType(VARCHAR)
                .isEqualTo("def,pqr");

        assertThat(assertions.function("concat_ws", "','", "'def'", "NULL", "NULL", "'mno'", "'xyz'", "NULL", "'box'"))
                .hasType(VARCHAR)
                .isEqualTo("def,mno,xyz,box");
    }

    @Test
    public void testArray()
    {
        assertThat(assertions.function("concat_ws", "','", "ARRAY[]"))
                .hasType(VARCHAR)
                .isEqualTo("");

        assertThat(assertions.function("concat_ws", "','", "ARRAY['abc']"))
                .hasType(VARCHAR)
                .isEqualTo("abc");

        assertThat(assertions.function("concat_ws", "','", "ARRAY['abc', 'def', 'pqr', 'xyz']"))
                .hasType(VARCHAR)
                .isEqualTo("abc,def,pqr,xyz");

        assertThat(assertions.function("concat_ws", "null", "ARRAY['abc']"))
                .isNull(VARCHAR);

        assertThat(assertions.function("concat_ws", "','", "cast(NULL as array(varchar))"))
                .isNull(VARCHAR);

        assertThat(assertions.function("concat_ws", "','", "ARRAY['abc', null, null, 'xyz']"))
                .hasType(VARCHAR)
                .isEqualTo("abc,xyz");

        assertThat(assertions.function("concat_ws", "','", "ARRAY['abc', '', '', 'xyz','abcdefghi']"))
                .hasType(VARCHAR)
                .isEqualTo("abc,,,xyz,abcdefghi");
    }

    @Test
    public void testBadArray()
    {
        assertTrinoExceptionThrownBy(() -> assertions.function("concat_ws", "','", "ARRAY[1, 15]").evaluate())
                .hasMessageContaining("Unexpected parameters");
    }

    @Test
    public void testBadArguments()
    {
        assertTrinoExceptionThrownBy(() -> assertions.function("concat_ws", "','", "1", "15").evaluate())
                .hasMessageContaining("Unexpected parameters");
    }

    @Test
    public void testLowArguments()
    {
        assertTrinoExceptionThrownBy(() -> assertions.function("concat_ws", "','").evaluate())
                .hasMessage("There must be two or more arguments");
    }
}
