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
package io.trino.type;

import io.airlift.slice.Slice;
import io.trino.operator.scalar.AbstractTestFunctions;
import io.trino.spi.TrinoException;
import io.trino.spi.function.CastDependency;
import io.trino.spi.function.Convention;
import io.trino.spi.function.OperatorDependency;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.function.TypeParameter;
import io.trino.spi.type.StandardTypes;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandle;

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.NULLABLE_RETURN;
import static io.trino.spi.function.OperatorType.EQUAL;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;

public class TestCastDependencies
        extends AbstractTestFunctions
{
    @BeforeClass
    public void setUp()
    {
        registerParametricScalar(CastVarcharToInteger.class);
        registerParametricScalar(CastAnyToVarchar.class);
        registerParametricScalar(CastAnyFromVarchar.class);
    }

    @Test
    public void testConventionDependencies()
    {
        assertFunction("cast_varchar_to_integer('11')", INTEGER, 11);
        assertFunction("cast_any_to_varchar(BIGINT '11')", VARCHAR, "11");
        assertFunction("cast_any_to_varchar(DATE '2005-05-05')", VARCHAR, "2005-05-05");
        assertFunction("cast_any_from_varchar(DATE '2005-05-05', '2005-05-05')", BOOLEAN, true);
        assertFunction("cast_any_from_varchar(BIGINT '11', '12')", BOOLEAN, false);
    }

    @ScalarFunction("cast_varchar_to_integer")
    public static class CastVarcharToInteger
    {
        @SqlType(StandardTypes.INTEGER)
        public static long castVarcharToInteger(
                @CastDependency(
                        fromType = StandardTypes.VARCHAR,
                        toType = StandardTypes.INTEGER,
                        convention = @Convention(arguments = NEVER_NULL, result = FAIL_ON_NULL))
                        MethodHandle cast,
                @SqlType(StandardTypes.VARCHAR) Slice value)
        {
            try {
                return (long) cast.invokeExact(value);
            }
            catch (Throwable t) {
                throwIfInstanceOf(t, Error.class);
                throwIfInstanceOf(t, TrinoException.class);
                throw new TrinoException(GENERIC_INTERNAL_ERROR, t);
            }
        }
    }

    @ScalarFunction("cast_any_to_varchar")
    public static class CastAnyToVarchar
    {
        @TypeParameter("V")
        @SqlType(StandardTypes.VARCHAR)
        public static Slice castAnyToVarchar(
                @CastDependency(
                        fromType = "V",
                        toType = StandardTypes.VARCHAR,
                        convention = @Convention(arguments = NEVER_NULL, result = FAIL_ON_NULL))
                        MethodHandle cast,
                @SqlType("V") long value)
        {
            try {
                return (Slice) cast.invokeExact(value);
            }
            catch (Throwable t) {
                throwIfInstanceOf(t, Error.class);
                throwIfInstanceOf(t, TrinoException.class);
                throw new TrinoException(GENERIC_INTERNAL_ERROR, t);
            }
        }
    }

    @ScalarFunction("cast_any_from_varchar")
    public static class CastAnyFromVarchar
    {
        @TypeParameter("V")
        @SqlType(StandardTypes.BOOLEAN)
        public static boolean castAnyFromVarchar(
                @CastDependency(
                        fromType = StandardTypes.VARCHAR,
                        toType = "V",
                        convention = @Convention(arguments = NEVER_NULL, result = FAIL_ON_NULL))
                        MethodHandle cast,
                @OperatorDependency(
                        operator = EQUAL,
                        argumentTypes = {"V", "V"},
                        convention = @Convention(arguments = {NEVER_NULL, NEVER_NULL}, result = NULLABLE_RETURN))
                        MethodHandle equals,
                @SqlType("V") long left,
                @SqlType(StandardTypes.VARCHAR) Slice right)
        {
            try {
                long rightLong = (long) cast.invokeExact(right);
                return (Boolean) equals.invokeExact(left, rightLong);
            }
            catch (Throwable t) {
                throwIfInstanceOf(t, Error.class);
                throwIfInstanceOf(t, TrinoException.class);
                throw new TrinoException(GENERIC_INTERNAL_ERROR, t);
            }
        }
    }
}
