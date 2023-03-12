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

import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.procedure.Procedure;
import org.testng.annotations.Test;

import java.util.List;

import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.util.Reflection.methodHandle;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestProcedureCreation
{
    @Test
    public void shouldThrowExceptionWhenOptionalArgumentIsNotLast()
    {
        assertThatThrownBy(() -> createTestProcedure(ImmutableList.of(
                new Procedure.Argument("NAME", VARCHAR, false, null),
                new Procedure.Argument("NAME2", VARCHAR, true, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Optional arguments should follow required ones");

        assertThatThrownBy(() -> createTestProcedure(ImmutableList.of(
                new Procedure.Argument("NAME", VARCHAR, true, null),
                new Procedure.Argument("NAME2", VARCHAR, true, null),
                new Procedure.Argument("NAME3", VARCHAR, true, null),
                new Procedure.Argument("NAME4", VARCHAR, false, null),
                new Procedure.Argument("NAME5", VARCHAR, true, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Optional arguments should follow required ones");
    }

    @Test
    public void shouldThrowExceptionWhenArgumentNameRepeates()
    {
        assertThatThrownBy(() -> createTestProcedure(ImmutableList.of(
                new Procedure.Argument("NAME", VARCHAR, false, null),
                new Procedure.Argument("NAME", VARCHAR, true, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate argument name: 'NAME'");
    }

    @Test
    public void shouldThrowExceptionWhenProcedureIsNonVoid()
    {
        assertThatThrownBy(() -> new Procedure(
                "schema",
                "name",
                ImmutableList.of(),
                methodHandle(Procedures.class, "funWithoutArguments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method must return void");
    }

    @Test
    public void shouldThrowExceptionWhenMethodHandleIsNull()
    {
        assertThatThrownBy(() -> new Procedure(
                "schema",
                "name",
                ImmutableList.of(),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("methodHandle is null");
    }

    @Test
    public void shouldThrowExceptionWhenMethodHandleHasVarargs()
    {
        assertThatThrownBy(() -> new Procedure(
                "schema",
                "name",
                ImmutableList.of(),
                methodHandle(Procedures.class, "funWithVarargs", String[].class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method must have fixed arity");
    }

    @Test
    public void shouldThrowExceptionWhenArgumentCountDoesntMatch()
    {
        assertThatThrownBy(() -> new Procedure(
                "schema",
                "name",
                ImmutableList.of(
                        new Procedure.Argument("NAME", VARCHAR, true, null),
                        new Procedure.Argument("NAME2", VARCHAR, true, null),
                        new Procedure.Argument("NAME3", VARCHAR, true, null)),
                methodHandle(Procedures.class, "fun1", ConnectorSession.class, Object.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method parameter count must match arguments");
    }

    private static Procedure createTestProcedure(List<Procedure.Argument> arguments)
    {
        int argumentsCount = arguments.size();
        String functionName = "fun" + argumentsCount;

        Class<?>[] clazzes = new Class<?>[argumentsCount + 1];
        clazzes[0] = ConnectorSession.class;

        for (int i = 0; i < argumentsCount; i++) {
            clazzes[i + 1] = Object.class;
        }

        return new Procedure(
                "schema",
                "name",
                arguments,
                methodHandle(Procedures.class, functionName, clazzes));
    }

    public static class Procedures
    {
        public void fun0(ConnectorSession session) {}

        public void fun1(ConnectorSession session, Object arg1) {}

        public void fun2(ConnectorSession session, Object arg1, Object arg2) {}

        public void fun2(Object arg1, Object arg2) {}

        public void fun3(ConnectorSession session, Object arg1, Object arg2, Object arg3) {}

        public void fun4(ConnectorSession session, Object arg1, Object arg2, Object arg3, Object arg4) {}

        public void fun5(ConnectorSession session, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {}

        public String funWithoutArguments()
        {
            return "";
        }

        public void funWithVarargs(String... arguments) {}
    }
}
