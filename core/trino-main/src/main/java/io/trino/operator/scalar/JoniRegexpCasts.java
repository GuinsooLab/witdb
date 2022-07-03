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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import io.trino.spi.TrinoException;
import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.OperatorType;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;
import io.trino.type.JoniRegexp;
import io.trino.type.JoniRegexpType;

import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.spi.type.Chars.padSpaces;

public final class JoniRegexpCasts
{
    private JoniRegexpCasts() {}

    @LiteralParameters("x")
    @ScalarOperator(OperatorType.CAST)
    @SqlType(JoniRegexpType.NAME)
    public static JoniRegexp castVarcharToJoniRegexp(@SqlType("varchar(x)") Slice pattern)
    {
        return joniRegexp(pattern);
    }

    @ScalarOperator(OperatorType.CAST)
    @LiteralParameters("x")
    @SqlType(JoniRegexpType.NAME)
    public static JoniRegexp castCharToJoniRegexp(@LiteralParameter("x") Long charLength, @SqlType("char(x)") Slice pattern)
    {
        return joniRegexp(padSpaces(pattern, charLength.intValue()));
    }

    public static JoniRegexp joniRegexp(Slice pattern)
    {
        Regex regex;
        try {
            // When normal UTF8 encoding instead of non-strict UTF8) is used, joni can infinite loop when invalid UTF8 slice is supplied to it.
            regex = new Regex(pattern.getBytes(), 0, pattern.length(), Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
        }
        catch (Exception e) {
            throw new TrinoException(INVALID_FUNCTION_ARGUMENT, e);
        }
        return new JoniRegexp(pattern, regex);
    }
}
