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
package io.trino.sql.planner.assertions;

import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.SymbolReference;
import org.testng.annotations.Test;

import static io.trino.sql.ExpressionUtils.rewriteIdentifiersToSymbolReferences;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestExpressionVerifier
{
    private final SqlParser parser = new SqlParser();

    @Test
    public void test()
    {
        Expression actual = expression("NOT(orderkey = 3 AND custkey = 3 AND orderkey < 10)");

        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .put("Y", new SymbolReference("custkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);

        assertTrue(verifier.process(actual, expression("NOT(X = 3 AND Y = 3 AND X < 10)")));
        assertThatThrownBy(() -> verifier.process(actual, expression("NOT(X = 3 AND Y = 3 AND Z < 10)")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing expression for alias Z");
        assertFalse(verifier.process(actual, expression("NOT(X = 3 AND X = 3 AND X < 10)")));
    }

    @Test
    public void testCast()
    {
        SymbolAliases aliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(aliases);
        assertTrue(verifier.process(expression("VARCHAR '2'"), expression("VARCHAR '2'")));
        assertFalse(verifier.process(expression("VARCHAR '2'"), expression("CAST('2' AS bigint)")));
        assertTrue(verifier.process(expression("CAST(orderkey AS varchar)"), expression("CAST(X AS varchar)")));
    }

    @Test
    public void testBetween()
    {
        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("X", new SymbolReference("orderkey"))
                .put("Y", new SymbolReference("custkey"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);
        // Complete match
        assertTrue(verifier.process(expression("orderkey BETWEEN 1 AND 2"), expression("X BETWEEN 1 AND 2")));
        // Different value
        assertFalse(verifier.process(expression("orderkey BETWEEN 1 AND 2"), expression("Y BETWEEN 1 AND 2")));
        assertFalse(verifier.process(expression("custkey BETWEEN 1 AND 2"), expression("X BETWEEN 1 AND 2")));
        // Different min or max
        assertFalse(verifier.process(expression("orderkey BETWEEN 2 AND 4"), expression("X BETWEEN 1 AND 2")));
        assertFalse(verifier.process(expression("orderkey BETWEEN 1 AND 2"), expression("X BETWEEN '1' AND '2'")));
        assertFalse(verifier.process(expression("orderkey BETWEEN 1 AND 2"), expression("X BETWEEN 4 AND 7")));
    }

    @Test
    public void testSymmetry()
    {
        SymbolAliases symbolAliases = SymbolAliases.builder()
                .put("a", new SymbolReference("x"))
                .put("b", new SymbolReference("y"))
                .build();

        ExpressionVerifier verifier = new ExpressionVerifier(symbolAliases);

        assertTrue(verifier.process(expression("x > y"), expression("a > b")));
        assertTrue(verifier.process(expression("x > y"), expression("b < a")));
        assertTrue(verifier.process(expression("y < x"), expression("a > b")));
        assertTrue(verifier.process(expression("y < x"), expression("b < a")));

        assertFalse(verifier.process(expression("x < y"), expression("a > b")));
        assertFalse(verifier.process(expression("x < y"), expression("b < a")));
        assertFalse(verifier.process(expression("y > x"), expression("a > b")));
        assertFalse(verifier.process(expression("y > x"), expression("b < a")));

        assertTrue(verifier.process(expression("x >= y"), expression("a >= b")));
        assertTrue(verifier.process(expression("x >= y"), expression("b <= a")));
        assertTrue(verifier.process(expression("y <= x"), expression("a >= b")));
        assertTrue(verifier.process(expression("y <= x"), expression("b <= a")));

        assertFalse(verifier.process(expression("x <= y"), expression("a >= b")));
        assertFalse(verifier.process(expression("x <= y"), expression("b <= a")));
        assertFalse(verifier.process(expression("y >= x"), expression("a >= b")));
        assertFalse(verifier.process(expression("y >= x"), expression("b <= a")));

        assertTrue(verifier.process(expression("x = y"), expression("a = b")));
        assertTrue(verifier.process(expression("x = y"), expression("b = a")));
        assertTrue(verifier.process(expression("y = x"), expression("a = b")));
        assertTrue(verifier.process(expression("y = x"), expression("b = a")));
        assertTrue(verifier.process(expression("x <> y"), expression("a <> b")));
        assertTrue(verifier.process(expression("x <> y"), expression("b <> a")));
        assertTrue(verifier.process(expression("y <> x"), expression("a <> b")));
        assertTrue(verifier.process(expression("y <> x"), expression("b <> a")));

        assertTrue(verifier.process(expression("x IS DISTINCT FROM y"), expression("a IS DISTINCT FROM b")));
        assertTrue(verifier.process(expression("x IS DISTINCT FROM y"), expression("b IS DISTINCT FROM a")));
        assertTrue(verifier.process(expression("y IS DISTINCT FROM x"), expression("a IS DISTINCT FROM b")));
        assertTrue(verifier.process(expression("y IS DISTINCT FROM x"), expression("b IS DISTINCT FROM a")));
    }

    private Expression expression(String sql)
    {
        return rewriteIdentifiersToSymbolReferences(parser.createExpression(sql, new ParsingOptions()));
    }
}
