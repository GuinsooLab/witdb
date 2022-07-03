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

import io.trino.FeaturesConfig;
import io.trino.metadata.TypeRegistry;
import io.trino.spi.type.TypeNotFoundException;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.TypeSignature;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTypeRegistry
{
    private final TypeRegistry typeRegistry = new TypeRegistry(new TypeOperators(), new FeaturesConfig());

    @Test
    public void testNonexistentType()
    {
        assertThatThrownBy(() -> typeRegistry.getType(new TypeSignature("not a real type")))
                .isInstanceOf(TypeNotFoundException.class)
                .hasMessage("Unknown type: not a real type");
    }

    @Test
    public void testOperatorsImplemented()
    {
        typeRegistry.verifyTypes();
    }
}
