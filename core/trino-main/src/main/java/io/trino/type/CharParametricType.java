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

import io.trino.spi.type.ParametricType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeParameter;

import java.util.List;

import static io.trino.spi.type.CharType.createCharType;

public class CharParametricType
        implements ParametricType
{
    public static final CharParametricType CHAR = new CharParametricType();

    @Override
    public String getName()
    {
        return StandardTypes.CHAR;
    }

    @Override
    public Type createType(TypeManager typeManager, List<TypeParameter> parameters)
    {
        if (parameters.isEmpty()) {
            return createCharType(1);
        }
        if (parameters.size() != 1) {
            throw new IllegalArgumentException("Expected at most one parameter for CHAR");
        }

        TypeParameter parameter = parameters.get(0);

        if (!parameter.isLongLiteral()) {
            throw new IllegalArgumentException("CHAR length must be a number");
        }

        return createCharType(parameter.getLongLiteral());
    }
}
