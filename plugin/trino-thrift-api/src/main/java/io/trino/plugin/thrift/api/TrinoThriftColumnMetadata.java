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
package io.trino.plugin.thrift.api;

import io.airlift.drift.annotations.ThriftConstructor;
import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftStruct;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.type.TypeManager;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.airlift.drift.annotations.ThriftField.Requiredness.OPTIONAL;
import static io.trino.plugin.thrift.api.NameValidationUtils.checkValidName;
import static java.util.Objects.requireNonNull;

@ThriftStruct
public final class TrinoThriftColumnMetadata
{
    private final String name;
    private final String type;
    private final String comment;
    private final boolean hidden;

    @ThriftConstructor
    public TrinoThriftColumnMetadata(String name, String type, @Nullable String comment, boolean hidden)
    {
        this.name = checkValidName(name);
        this.type = requireNonNull(type, "type is null");
        this.comment = comment;
        this.hidden = hidden;
    }

    @ThriftField(1)
    public String getName()
    {
        return name;
    }

    @ThriftField(2)
    public String getType()
    {
        return type;
    }

    @Nullable
    @ThriftField(value = 3, requiredness = OPTIONAL)
    public String getComment()
    {
        return comment;
    }

    @ThriftField(4)
    public boolean isHidden()
    {
        return hidden;
    }

    public ColumnMetadata toColumnMetadata(TypeManager typeManager)
    {
        return ColumnMetadata.builder()
                .setName(name)
                .setType(typeManager.fromSqlType(type))
                .setComment(Optional.ofNullable(comment))
                .setHidden(hidden)
                .build();
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
        TrinoThriftColumnMetadata other = (TrinoThriftColumnMetadata) obj;
        return Objects.equals(this.name, other.name) &&
                Objects.equals(this.type, other.type) &&
                Objects.equals(this.comment, other.comment) &&
                this.hidden == other.hidden;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, type, comment, hidden);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", name)
                .add("type", type)
                .add("comment", comment)
                .add("hidden", hidden)
                .toString();
    }
}
