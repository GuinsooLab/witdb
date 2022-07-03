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
package io.trino.metadata;

import io.trino.spi.type.Type;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.CASE_INSENSITIVE_ORDER;

class BoundVariables
        implements TypeVariables
{
    private final Map<String, Type> typeVariables = new TreeMap<>(CASE_INSENSITIVE_ORDER);
    private final Map<String, Long> longVariables = new TreeMap<>(CASE_INSENSITIVE_ORDER);

    @Override
    public Type getTypeVariable(String variableName)
    {
        return getValue(typeVariables, variableName);
    }

    public BoundVariables setTypeVariable(String variableName, Type variableValue)
    {
        setValue(typeVariables, variableName, variableValue);
        return this;
    }

    @Override
    public boolean containsTypeVariable(String variableName)
    {
        return containsValue(typeVariables, variableName);
    }

    public Map<String, Type> getTypeVariables()
    {
        return typeVariables;
    }

    @Override
    public Long getLongVariable(String variableName)
    {
        return getValue(longVariables, variableName);
    }

    public BoundVariables setLongVariable(String variableName, Long variableValue)
    {
        setValue(longVariables, variableName, variableValue);
        return this;
    }

    @Override
    public boolean containsLongVariable(String variableName)
    {
        return containsValue(longVariables, variableName);
    }

    public Map<String, Long> getLongVariables()
    {
        return longVariables;
    }

    private static <T> T getValue(Map<String, T> map, String variableName)
    {
        checkState(variableName != null, "variableName is null");
        T value = map.get(variableName);
        checkState(value != null, "value for variable '%s' is null", variableName);
        return value;
    }

    private static boolean containsValue(Map<String, ?> map, String variableName)
    {
        checkState(variableName != null, "variableName is null");
        return map.containsKey(variableName);
    }

    private static <T> void setValue(Map<String, T> map, String variableName, T value)
    {
        checkState(variableName != null, "variableName is null");
        checkState(value != null, "value for variable '%s' is null", variableName);
        map.put(variableName, value);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BoundVariables that = (BoundVariables) o;
        return Objects.equals(typeVariables, that.typeVariables) &&
                Objects.equals(longVariables, that.longVariables);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(typeVariables, longVariables);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("typeVariables", typeVariables)
                .add("longVariables", longVariables)
                .toString();
    }
}
