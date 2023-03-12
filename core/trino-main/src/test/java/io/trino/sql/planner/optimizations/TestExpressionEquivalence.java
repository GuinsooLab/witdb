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
package io.trino.sql.planner.optimizations;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;
import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.Expression;
import io.trino.transaction.TestingTransactionManager;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.util.Set;

import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.sql.ExpressionTestUtils.planExpression;
import static io.trino.sql.parser.ParsingOptions.DecimalLiteralTreatment.AS_DOUBLE;
import static io.trino.sql.planner.SymbolsExtractor.extractUnique;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestExpressionEquivalence
{
    private static final SqlParser SQL_PARSER = new SqlParser();
    private static final ExpressionEquivalence EQUIVALENCE = new ExpressionEquivalence(
            PLANNER_CONTEXT.getMetadata(),
            PLANNER_CONTEXT.getFunctionManager(),
            createTestingTypeAnalyzer(PLANNER_CONTEXT));
    private static final TypeProvider TYPE_PROVIDER = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(new Symbol("a_boolean"), BOOLEAN)
            .put(new Symbol("b_boolean"), BOOLEAN)
            .put(new Symbol("c_boolean"), BOOLEAN)
            .put(new Symbol("d_boolean"), BOOLEAN)
            .put(new Symbol("e_boolean"), BOOLEAN)
            .put(new Symbol("f_boolean"), BOOLEAN)
            .put(new Symbol("g_boolean"), BOOLEAN)
            .put(new Symbol("h_boolean"), BOOLEAN)
            .put(new Symbol("a_bigint"), BIGINT)
            .put(new Symbol("b_bigint"), BIGINT)
            .put(new Symbol("c_bigint"), BIGINT)
            .put(new Symbol("d_bigint"), BIGINT)
            .put(new Symbol("b_double"), DOUBLE)
            .buildOrThrow());

    @Test
    public void testEquivalent()
    {
        assertEquivalent("CAST(null AS BIGINT)", "CAST(null as BIGINT)");
        assertEquivalent("a_bigint < b_double", "b_double > a_bigint");
        assertEquivalent("true", "true");
        assertEquivalent("4", "4");
        assertEquivalent("4.4", "4.4");
        assertEquivalent("'foo'", "'foo'");

        assertEquivalent("4 = 5", "5 = 4");
        assertEquivalent("4.4 = 5.5", "5.5 = 4.4");
        assertEquivalent("'foo' = 'bar'", "'bar' = 'foo'");
        assertEquivalent("4 <> 5", "5 <> 4");
        assertEquivalent("4 is distinct from 5", "5 is distinct from 4");
        assertEquivalent("4 < 5", "5 > 4");
        assertEquivalent("4 <= 5", "5 >= 4");
        assertEquivalent(
                "TIMESTAMP '2020-05-10 12:34:56.123456789' = TIMESTAMP '2021-05-10 12:34:56.123456789'",
                "TIMESTAMP '2021-05-10 12:34:56.123456789' = TIMESTAMP '2020-05-10 12:34:56.123456789'");
        assertEquivalent(
                "TIMESTAMP '2020-05-10 12:34:56.123456789 +8' = TIMESTAMP '2021-05-10 12:34:56.123456789 +8'",
                "TIMESTAMP '2021-05-10 12:34:56.123456789 +8' = TIMESTAMP '2020-05-10 12:34:56.123456789 +8'");

        assertEquivalent("mod(4, 5)", "mod(4, 5)");

        assertEquivalent("a_bigint", "a_bigint");
        assertEquivalent("a_bigint = b_bigint", "b_bigint = a_bigint");
        assertEquivalent("a_bigint < b_bigint", "b_bigint > a_bigint");

        assertEquivalent("a_bigint < b_double", "b_double > a_bigint");

        assertEquivalent("true and false", "false and true");
        assertEquivalent("4 <= 5 and 6 < 7", "7 > 6 and 5 >= 4");
        assertEquivalent("4 <= 5 or 6 < 7", "7 > 6 or 5 >= 4");
        assertEquivalent("a_bigint <= b_bigint and c_bigint < d_bigint", "d_bigint > c_bigint and b_bigint >= a_bigint");
        assertEquivalent("a_bigint <= b_bigint or c_bigint < d_bigint", "d_bigint > c_bigint or b_bigint >= a_bigint");

        assertEquivalent("4 <= 5 and 4 <= 5", "4 <= 5");
        assertEquivalent("4 <= 5 and 6 < 7", "7 > 6 and 5 >= 4 and 5 >= 4");
        assertEquivalent("2 <= 3 and 4 <= 5 and 6 < 7", "7 > 6 and 5 >= 4 and 3 >= 2");

        assertEquivalent("4 <= 5 or 4 <= 5", "4 <= 5");
        assertEquivalent("4 <= 5 or 6 < 7", "7 > 6 or 5 >= 4 or 5 >= 4");
        assertEquivalent("2 <= 3 or 4 <= 5 or 6 < 7", "7 > 6 or 5 >= 4 or 3 >= 2");

        assertEquivalent("a_boolean and b_boolean and c_boolean", "c_boolean and b_boolean and a_boolean");
        assertEquivalent("(a_boolean and b_boolean) and c_boolean", "(c_boolean and b_boolean) and a_boolean");
        assertEquivalent("a_boolean and (b_boolean or c_boolean)", "a_boolean and (c_boolean or b_boolean) and a_boolean");

        assertEquivalent(
                "(a_boolean or b_boolean or c_boolean) and (d_boolean or e_boolean) and (f_boolean or g_boolean or h_boolean)",
                "(h_boolean or g_boolean or f_boolean) and (b_boolean or a_boolean or c_boolean) and (e_boolean or d_boolean)");

        assertEquivalent(
                "(a_boolean and b_boolean and c_boolean) or (d_boolean and e_boolean) or (f_boolean and g_boolean and h_boolean)",
                "(h_boolean and g_boolean and f_boolean) or (b_boolean and a_boolean and c_boolean) or (e_boolean and d_boolean)");
    }

    private static void assertEquivalent(@Language("SQL") String left, @Language("SQL") String right)
    {
        ParsingOptions parsingOptions = new ParsingOptions(AS_DOUBLE /* anything */);
        Expression leftExpression = planExpression(PLANNER_CONTEXT, TEST_SESSION, TYPE_PROVIDER, SQL_PARSER.createExpression(left, parsingOptions));
        Expression rightExpression = planExpression(PLANNER_CONTEXT, TEST_SESSION, TYPE_PROVIDER, SQL_PARSER.createExpression(right, parsingOptions));

        Set<Symbol> symbols = extractUnique(ImmutableList.of(leftExpression, rightExpression));
        TypeProvider types = TypeProvider.copyOf(symbols.stream()
                .collect(toMap(identity(), TestExpressionEquivalence::generateType)));

        assertTrue(
                areExpressionEquivalent(leftExpression, rightExpression, types),
                format("Expected (%s) and (%s) to be equivalent", left, right));
        assertTrue(
                areExpressionEquivalent(rightExpression, leftExpression, types),
                format("Expected (%s) and (%s) to be equivalent", right, left));
    }

    @Test
    public void testNotEquivalent()
    {
        assertNotEquivalent("CAST(null AS BOOLEAN)", "false");
        assertNotEquivalent("false", "CAST(null AS BOOLEAN)");
        assertNotEquivalent("true", "false");
        assertNotEquivalent("4", "5");
        assertNotEquivalent("4.4", "5.5");
        assertNotEquivalent("'foo'", "'bar'");

        assertNotEquivalent("4 = 5", "5 = 6");
        assertNotEquivalent("4 <> 5", "5 <> 6");
        assertNotEquivalent("4 is distinct from 5", "5 is distinct from 6");
        assertNotEquivalent("4 < 5", "5 > 6");
        assertNotEquivalent("4 <= 5", "5 >= 6");

        assertNotEquivalent("mod(4, 5)", "mod(5, 4)");

        assertNotEquivalent("a_bigint", "b_bigint");
        assertNotEquivalent("a_bigint = b_bigint", "b_bigint = c_bigint");
        assertNotEquivalent("a_bigint < b_bigint", "b_bigint > c_bigint");

        assertNotEquivalent("a_bigint < b_double", "b_double > c_bigint");

        assertNotEquivalent("4 <= 5 and 6 < 7", "7 > 6 and 5 >= 6");
        assertNotEquivalent("4 <= 5 or 6 < 7", "7 > 6 or 5 >= 6");
        assertNotEquivalent("a_bigint <= b_bigint and c_bigint < d_bigint", "d_bigint > c_bigint and b_bigint >= c_bigint");
        assertNotEquivalent("a_bigint <= b_bigint or c_bigint < d_bigint", "d_bigint > c_bigint or b_bigint >= c_bigint");

        assertNotEquivalent(
                "CAST(TIME '12:34:56.123 +00:00' AS varchar)",
                "CAST(TIME '14:34:56.123 +02:00' AS varchar)");
        assertNotEquivalent(
                "CAST(TIME '12:34:56.123456 +00:00' AS varchar)",
                "CAST(TIME '14:34:56.123456 +02:00' AS varchar)");
        assertNotEquivalent(
                "CAST(TIME '12:34:56.123456789 +00:00' AS varchar)",
                "CAST(TIME '14:34:56.123456789 +02:00' AS varchar)");
        assertNotEquivalent(
                "CAST(TIME '12:34:56.123456789012 +00:00' AS varchar)",
                "CAST(TIME '14:34:56.123456789012 +02:00' AS varchar)");

        assertNotEquivalent(
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123 Europe/Warsaw' AS varchar)",
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123 Europe/Paris' AS varchar)");
        assertNotEquivalent(
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456 Europe/Warsaw' AS varchar)",
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456 Europe/Paris' AS varchar)");
        assertNotEquivalent(
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456789 Europe/Warsaw' AS varchar)",
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456789 Europe/Paris' AS varchar)");
        assertNotEquivalent(
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456789012 Europe/Warsaw' AS varchar)",
                "CAST(TIMESTAMP '2020-05-10 12:34:56.123456789012 Europe/Paris' AS varchar)");
    }

    private static void assertNotEquivalent(@Language("SQL") String left, @Language("SQL") String right)
    {
        ParsingOptions parsingOptions = new ParsingOptions(AS_DOUBLE /* anything */);
        Expression leftExpression = planExpression(PLANNER_CONTEXT, TEST_SESSION, TYPE_PROVIDER, SQL_PARSER.createExpression(left, parsingOptions));
        Expression rightExpression = planExpression(PLANNER_CONTEXT, TEST_SESSION, TYPE_PROVIDER, SQL_PARSER.createExpression(right, parsingOptions));

        Set<Symbol> symbols = extractUnique(ImmutableList.of(leftExpression, rightExpression));
        TypeProvider types = TypeProvider.copyOf(symbols.stream()
                .collect(toMap(identity(), TestExpressionEquivalence::generateType)));

        assertFalse(
                areExpressionEquivalent(leftExpression, rightExpression, types),
                format("Expected (%s) and (%s) to not be equivalent", left, right));
        assertFalse(
                areExpressionEquivalent(rightExpression, leftExpression, types),
                format("Expected (%s) and (%s) to not be equivalent", right, left));
    }

    private static boolean areExpressionEquivalent(Expression leftExpression, Expression rightExpression, TypeProvider types)
    {
        return transaction(new TestingTransactionManager(), new AllowAllAccessControl())
                .singleStatement()
                .execute(TEST_SESSION, transactionSession -> {
                    return EQUIVALENCE.areExpressionsEquivalent(transactionSession, leftExpression, rightExpression, types);
                });
    }

    private static Type generateType(Symbol symbol)
    {
        String typeName = Splitter.on('_').limit(2).splitToList(symbol.getName()).get(1);
        return PLANNER_CONTEXT.getTypeManager().getType(new TypeSignature(typeName, ImmutableList.of()));
    }
}
