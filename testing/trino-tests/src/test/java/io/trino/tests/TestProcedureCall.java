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
package io.trino.tests;

import io.trino.Session;
import io.trino.connector.MockConnectorFactory;
import io.trino.connector.MockConnectorPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.ProcedureTester;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingProcedures;
import io.trino.tests.tpch.TpchQueryRunnerBuilder;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

@Test(singleThreaded = true)
public class TestProcedureCall
        extends AbstractTestQueryFramework
{
    private static final String TESTING_CATALOG = "testing_catalog";
    private static final String PROCEDURE_SCHEMA = "procedure_schema";

    private ProcedureTester tester;
    private Session session;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return TpchQueryRunnerBuilder.builder().build();
    }

    @BeforeClass
    public void setUp()
    {
        DistributedQueryRunner queryRunner = getDistributedQueryRunner();
        tester = queryRunner.getCoordinator().getProcedureTester();
        session = testSessionBuilder()
                .setCatalog(TESTING_CATALOG)
                .setSchema(PROCEDURE_SCHEMA)
                .build();

        queryRunner.installPlugin(new MockConnectorPlugin(MockConnectorFactory.builder()
                .withProcedures(new TestingProcedures(tester).getProcedures(PROCEDURE_SCHEMA))
                .build()));
        queryRunner.createCatalog(TESTING_CATALOG, "mock");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        tester = null;
        session = null;
    }

    @Override
    protected Session getSession()
    {
        return session;
    }

    @Test
    public void testProcedureCall()
    {
        assertCall("CALL test_simple()", "simple");
        assertCall(format("CALL %s.test_simple()", PROCEDURE_SCHEMA), "simple");
        assertCall(format("CALL %s.%s.test_simple()", TESTING_CATALOG, PROCEDURE_SCHEMA), "simple");

        assertCall("CALL test_args(123, 4.5, 'hello', true)", "args", 123L, 4.5, "hello", true);
        assertCall("CALL test_args(-5, nan(), 'bye', false)", "args", -5L, Double.NaN, "bye", false);
        assertCall("CALL test_args(3, 88, 'coerce', true)", "args", 3L, 88.0, "coerce", true);
        assertCall("CALL test_args(x => 123, y => 4.5, z => 'hello', q => true)", "args", 123L, 4.5, "hello", true);
        assertCall("CALL test_args(q => true, z => 'hello', y => 4.5, x => 123)", "args", 123L, 4.5, "hello", true);

        assertCall("CALL test_nulls(123, null)", "nulls", 123L, null);
        assertCall("CALL test_nulls(null, 'apple')", "nulls", null, "apple");

        assertCall("CALL test_arrays(ARRAY [12, 34], ARRAY['abc', 'xyz'])", "arrays", list(12L, 34L), list("abc", "xyz"));
        assertCall("CALL test_arrays(ARRAY [], ARRAY[])", "arrays", list(), list());
        assertCall("CALL test_nested(ARRAY [ARRAY[12, 34], ARRAY[56]])", "nested", list(list(12L, 34L), list(56L)));

        assertCall("CALL test_nested(ARRAY [])", "nested", list());
        assertCall("CALL test_nested(ARRAY [ARRAY[]])", "nested", list(list()));

        assertCall("CALL test_session_first(123)", "session_first", 123L);
        assertCall("CALL test_session_last('grape')", "session_last", "grape");

        assertCallThrows("CALL test_exception()", "exception", "test exception from procedure");
        assertCallThrows("CALL test_error()", "error", "test error from procedure");

        assertCallFails("CALL test_args(null, 4.5, 'hello', true)", "Procedure argument cannot be null: X");
        assertCallFails("CALL test_args(123, null, 'hello', true)", "Procedure argument cannot be null: Y");
        assertCallFails("CALL test_args(123, 4.5, 'hello', null)", "Procedure argument cannot be null: Q");

        assertCallFails("CALL test_simple(123)", "line 1:1: Too many arguments for procedure");
        assertCallFails("CALL test_args(123, 4.5, 'hello')", "line 1:1: Required procedure argument 'Q' is missing");
        assertCallFails("CALL test_args(x => 123, y => 4.5, q => true)", "line 1:1: Required procedure argument 'Z' is missing");
        assertCallFails("CALL test_args(123, 4.5, 'hello', q => true)", "line 1:1: Named and positional arguments cannot be mixed");
        assertCallFails("CALL test_args(x => 3, x => 4)", "line 1:24: Duplicate procedure argument: X");
        assertCallFails("CALL test_args(t => 404)", "line 1:16: Unknown argument name: T");
        assertCallFails("CALL test_nulls('hello', null)", "line 1:17: Cannot cast type varchar(5) to bigint");
        assertCallFails("CALL test_nulls(null, 123)", "line 1:23: Cannot cast type integer to varchar");
    }

    @Test
    public void testProcedureCallWithOptionals()
    {
        // test_optionals(x => Optional['hello'])
        // test_optionals2(x, y => Optional['world])
        // test_optionals3(x => Optional['this'], y => Optional['is'], z => Optional['default'])
        // test_optionals4(x, y, z => Optional['z default'], v => Optional['v default'])
        assertCall("CALL test_optionals()", "optionals", "hello");
        assertCall("CALL test_optionals(x => 'x')", "optionals", "x");
        assertCall("CALL test_optionals2(x => 'ab')", "optionals2", "ab", "world");
        assertCall("CALL test_optionals2('ab')", "optionals2", "ab", "world");
        assertCall("CALL test_optionals2(x => 'ab', y => 'cd')", "optionals2", "ab", "cd");
        assertCall("CALL test_optionals2(y => 'cd', x => 'ab')", "optionals2", "ab", "cd");
        assertCall("CALL test_optionals2('ab', 'cd')", "optionals2", "ab", "cd");
        assertCall("CALL test_optionals3(x => 'ab', z => 'cd')", "optionals3", "ab", "is", "cd");
        assertCall("CALL test_optionals3('ab', 'cd', 'ef')", "optionals3", "ab", "cd", "ef");
        assertCall("CALL test_optionals3('ab', 'cd')", "optionals3", "ab", "cd", "default");
        assertCall("CALL test_optionals3('ab')", "optionals3", "ab", "is", "default");
        assertCall("CALL test_optionals3(y => 'ab', z => 'cd')", "optionals3", "this", "ab", "cd");
        assertCall("CALL test_optionals3(z => 'cd')", "optionals3", "this", "is", "cd");
        assertCall("CALL test_optionals4('a', 'b')", "optionals4", "a", "b", "z default", "v default");
        assertCall("CALL test_optionals4(x => 'x val', y => 'y val')", "optionals4", "x val", "y val", "z default", "v default");
        assertCall("CALL test_optionals4(z => 'z val', v => 'v val', x => 'x val', y => 'y val')", "optionals4", "x val", "y val", "z val", "v val");
        assertCall("CALL test_optionals4(v => 'v val', x => 'x val', y => 'y val', z => 'z val')", "optionals4", "x val", "y val", "z val", "v val");

        assertCallFails("CALL test_optionals2()", "line 1:1: Required procedure argument 'X' is missing");
        assertCallFails("CALL test_optionals4(z => 'cd')", "line 1:1: Required procedure argument 'X' is missing");
        assertCallFails("CALL test_optionals4(z => 'cd', v => 'value')", "line 1:1: Required procedure argument 'X' is missing");
        assertCallFails("CALL test_optionals4(y => 'cd', v => 'value')", "line 1:1: Required procedure argument 'X' is missing");
    }

    @Test
    public void testProcedureName()
    {
        assertCall("CALL test_lowercase_name()", "simple");
        assertCall("CALL TEST_LOWERCASE_NAME()", "simple");
        assertCall("CALL Test_Lowercase_NAME()", "simple");
        assertCall("CALL \"test_lowercase_name\"()", "simple");
        assertCall("CALL \"TEST_LOWERCASE_NAME\"()", "simple");
        assertCall("CALL \"Test_Lowercase_Name\"()", "simple");

        assertCall("CALL test_uppercase_name()", "simple");
        assertCall("CALL TEST_UPPERCASE_NAME()", "simple");
        assertCall("CALL Test_Uppercase_NAME()", "simple");
        assertCall("CALL \"test_uppercase_name\"()", "simple");
        assertCall("CALL \"TEST_UPPERCASE_NAME\"()", "simple");
        assertCall("CALL \"Test_Uppercase_NAME\"()", "simple");
    }

    @Test
    public void testNamedArguments()
    {
        assertCallFails("CALL test_argument_names(lower => 'a')", "line 1:26: Unknown argument name: LOWER");
        assertCallFails("CALL test_argument_names(LOWER => 'a')", "line 1:26: Unknown argument name: LOWER");
        assertCall("CALL test_argument_names(\"lower\" => 'a')", "names", "a", "b", "c", "d");
        assertCallFails("CALL test_argument_names(\"LOWER\" => 'a')", "line 1:26: Unknown argument name: LOWER");

        assertCall("CALL test_argument_names(upper => 'b')", "names", "a", "b", "c", "d");
        assertCall("CALL test_argument_names(UPPER => 'b')", "names", "a", "b", "c", "d");
        assertCallFails("CALL test_argument_names(\"upper\" => 'b')", "line 1:26: Unknown argument name: upper");
        assertCall("CALL test_argument_names(\"UPPER\" => 'b')", "names", "a", "b", "c", "d");

        assertCallFails("CALL test_argument_names(mixed => 'c')", "line 1:26: Unknown argument name: MIXED");
        assertCallFails("CALL test_argument_names(MixeD => 'c')", "line 1:26: Unknown argument name: MIXED");
        assertCallFails("CALL test_argument_names(MIXED => 'c')", "line 1:26: Unknown argument name: MIXED");
        assertCallFails("CALL test_argument_names(\"mixed\" => 'c')", "line 1:26: Unknown argument name: mixed");
        assertCall("CALL test_argument_names(\"MixeD\" => 'c')", "names", "a", "b", "c", "d");
        assertCallFails("CALL test_argument_names(\"MIXED\" => 'c')", "line 1:26: Unknown argument name: MIXED");

        assertCall("CALL test_argument_names(\"with space\" => 'd')", "names", "a", "b", "c", "d");
    }

    private void assertCall(@Language("SQL") String sql, String name, Object... arguments)
    {
        tester.reset();
        assertUpdate(sql);
        assertEquals(tester.getCalledName(), name);
        assertEquals(tester.getCalledArguments(), list(arguments));
    }

    private void assertCallThrows(@Language("SQL") String sql, String name, String message)
    {
        tester.reset();
        assertThatThrownBy(() -> assertUpdate(sql))
                .isInstanceOfSatisfying(RuntimeException.class, e -> {
                    assertEquals(tester.getCalledName(), name);
                    assertEquals(tester.getCalledArguments(), list());
                })
                .hasMessage(message);
    }

    private void assertCallFails(@Language("SQL") String sql, String message)
    {
        tester.reset();
        assertThatThrownBy(() -> assertUpdate(sql))
                .isInstanceOfSatisfying(RuntimeException.class, e -> assertFalse(tester.wasCalled()))
                .hasMessage(message);
    }

    @SafeVarargs
    private static <T> List<T> list(T... elements)
    {
        return asList(elements);
    }
}
