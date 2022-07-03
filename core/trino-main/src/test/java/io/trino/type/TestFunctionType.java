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

import com.google.common.collect.ImmutableList;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import org.testng.annotations.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.RowType.field;
import static org.testng.Assert.assertEquals;

public class TestFunctionType
{
    @Test
    public void testDisplayName()
    {
        Type function = new FunctionType(
                ImmutableList.of(RowType.from(ImmutableList.of(field("field", DOUBLE)))),
                BIGINT);

        assertEquals(function.getDisplayName(), "function(row(field double),bigint)");
    }
}
