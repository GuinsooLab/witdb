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
package io.trino.spi.ptf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.Experimental;
import io.trino.spi.type.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.trino.spi.ptf.Preconditions.checkArgument;
import static io.trino.spi.ptf.Preconditions.checkNotNullOrEmpty;
import static java.util.Objects.requireNonNull;

@Experimental(eta = "2022-10-31")
public class Descriptor
{
    private final List<Field> fields;

    @JsonCreator
    public Descriptor(@JsonProperty("fields") List<Field> fields)
    {
        requireNonNull(fields, "fields is null");
        checkArgument(!fields.isEmpty(), "descriptor has no fields");
        this.fields = List.copyOf(fields);
    }

    public static Descriptor descriptor(String... names)
    {
        List<Field> fields = Arrays.stream(names)
                .map(name -> new Field(name, Optional.empty()))
                .collect(Collectors.toList());
        return new Descriptor(fields);
    }

    public static Descriptor descriptor(List<String> names, List<Type> types)
    {
        requireNonNull(names, "names is null");
        requireNonNull(types, "types is null");
        checkArgument(names.size() == types.size(), "names and types lists do not match");
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            fields.add(new Field(names.get(i), Optional.of(types.get(i))));
        }
        return new Descriptor(fields);
    }

    @JsonProperty
    public List<Field> getFields()
    {
        return fields;
    }

    public boolean isTyped()
    {
        return fields.stream().allMatch(field -> field.type.isPresent());
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
        Descriptor that = (Descriptor) o;
        return fields.equals(that.fields);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(fields);
    }

    public static class Field
    {
        private final String name;
        private final Optional<Type> type;

        @JsonCreator
        public Field(@JsonProperty("name") String name, @JsonProperty("type") Optional<Type> type)
        {
            this.name = checkNotNullOrEmpty(name, "name");
            this.type = requireNonNull(type, "type is null");
        }

        @JsonProperty
        public String getName()
        {
            return name;
        }

        @JsonProperty
        public Optional<Type> getType()
        {
            return type;
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
            Field field = (Field) o;
            return name.equals(field.name) && type.equals(field.type);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name, type);
        }
    }
}
