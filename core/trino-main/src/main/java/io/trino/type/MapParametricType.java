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

import io.trino.spi.type.MapType;
import io.trino.spi.type.ParameterKind;
import io.trino.spi.type.ParametricType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeParameter;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

public final class MapParametricType
        implements ParametricType
{
    public static final MapParametricType MAP = new MapParametricType();

    @Override
    public String getName()
    {
        return StandardTypes.MAP;
    }

    @Override
    public Type createType(TypeManager typeManager, List<TypeParameter> parameters)
    {
        checkArgument(parameters.size() == 2, "Expected two parameters, got %s", parameters);
        TypeParameter firstParameter = parameters.get(0);
        TypeParameter secondParameter = parameters.get(1);
        checkArgument(
                firstParameter.getKind() == ParameterKind.TYPE && secondParameter.getKind() == ParameterKind.TYPE,
                "Expected key and type to be types, got %s",
                parameters);

        return new MapType(firstParameter.getType(), secondParameter.getType(), typeManager.getTypeOperators());
    }
}
