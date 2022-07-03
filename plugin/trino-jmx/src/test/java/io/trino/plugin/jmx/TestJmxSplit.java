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
package io.trino.plugin.jmx;

import com.google.common.collect.ImmutableList;
import io.trino.spi.HostAddress;
import org.testng.annotations.Test;

import static io.trino.plugin.jmx.MetadataUtil.SPLIT_CODEC;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

public class TestJmxSplit
{
    private static final ImmutableList<HostAddress> ADDRESSES = ImmutableList.of(HostAddress.fromString("test:1234"));
    private static final JmxSplit SPLIT = new JmxSplit(ADDRESSES);

    @Test
    public void testSplit()
    {
        assertEquals(SPLIT.getAddresses(), ADDRESSES);
        assertSame(SPLIT.getInfo(), SPLIT);
        assertEquals(SPLIT.isRemotelyAccessible(), false);
    }

    @Test
    public void testJsonRoundTrip()
    {
        String json = SPLIT_CODEC.toJson(SPLIT);
        JmxSplit copy = SPLIT_CODEC.fromJson(json);

        assertEquals(copy.getAddresses(), SPLIT.getAddresses());
        assertSame(copy.getInfo(), copy);
        assertEquals(copy.isRemotelyAccessible(), false);
    }
}
