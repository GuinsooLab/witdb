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
package io.trino.spi.type;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TestLongTimestamp
{
    @Test
    public void testToString()
    {
        assertEquals(new LongTimestamp(1600960182536000L, 0).toString(), "2020-09-24 15:09:42.536000000000");
        assertEquals(new LongTimestamp(1600960182536123L, 0).toString(), "2020-09-24 15:09:42.536123000000");
        assertEquals(new LongTimestamp(1600960182536123L, 456000).toString(), "2020-09-24 15:09:42.536123456000");
        assertEquals(new LongTimestamp(1600960182536123L, 456789).toString(), "2020-09-24 15:09:42.536123456789");
    }
}
