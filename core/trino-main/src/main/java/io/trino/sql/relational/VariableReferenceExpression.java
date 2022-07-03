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
package io.trino.sql.relational;

import io.trino.spi.type.Type;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class VariableReferenceExpression
        extends RowExpression
{
    private final String name;
    private final Type type;

    public VariableReferenceExpression(String name, Type type)
    {
        this.name = requireNonNull(name, "name is null");
        this.type = requireNonNull(type, "type is null");
    }

    public String getName()
    {
        return name;
    }

    @Override
    public Type getType()
    {
        return type;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, type);
    }

    @Override
    public String toString()
    {
        return name;
    }

    @Override
    public <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context)
    {
        return visitor.visitVariableReference(this, context);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        VariableReferenceExpression other = (VariableReferenceExpression) obj;
        return Objects.equals(this.name, other.name) && Objects.equals(this.type, other.type);
    }
}
