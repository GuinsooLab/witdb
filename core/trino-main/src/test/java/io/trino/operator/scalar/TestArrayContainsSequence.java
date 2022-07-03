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

import org.testng.annotations.Test;

import static io.trino.spi.type.BooleanType.BOOLEAN;

public class TestArrayContainsSequence
        extends AbstractTestFunctions
{
    @Test
    public void testBasic()
    {
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[1,2])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[3,4])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[5,6])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[1,2,4])", BOOLEAN, false);
        assertFunction("contains_sequence(ARRAY [1,2,3,NULL,4,5,6], ARRAY[3,NULL,4])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[1,2,3,4,5,6])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY ['1','2','3'], ARRAY['1','2'])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [1.1,2.2,3.3], ARRAY[1.1,2.2])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [ARRAY[1,2],ARRAY[3],ARRAY[4,5]], ARRAY[ARRAY[1,2],ARRAY[3]])", BOOLEAN, true);
        assertFunction("contains_sequence(ARRAY [ARRAY[1,2],ARRAY[3],ARRAY[4,5]], ARRAY[ARRAY[1,2],ARRAY[4]])", BOOLEAN, false);

        for (int i = 1; i <= 6; i++) {
            assertFunction("contains_sequence(ARRAY [1,2,3,4,5,6], ARRAY[" + i + "])", BOOLEAN, true);
        }
    }
}
