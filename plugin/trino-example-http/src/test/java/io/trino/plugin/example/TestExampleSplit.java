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
package io.trino.plugin.example;

import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import io.trino.spi.HostAddress;
import org.testng.annotations.Test;

import static io.airlift.json.JsonCodec.jsonCodec;
import static org.testng.Assert.assertEquals;

public class TestExampleSplit
{
    private final ExampleSplit split = new ExampleSplit("http://127.0.0.1/test.file");

    @Test
    public void testAddresses()
    {
        // http split with default port
        ExampleSplit httpSplit = new ExampleSplit("http://example.com/example");
        assertEquals(httpSplit.getAddresses(), ImmutableList.of(HostAddress.fromString("example.com")));
        assertEquals(httpSplit.isRemotelyAccessible(), true);

        // http split with custom port
        httpSplit = new ExampleSplit("http://example.com:8080/example");
        assertEquals(httpSplit.getAddresses(), ImmutableList.of(HostAddress.fromParts("example.com", 8080)));
        assertEquals(httpSplit.isRemotelyAccessible(), true);

        // http split with default port
        ExampleSplit httpsSplit = new ExampleSplit("https://example.com/example");
        assertEquals(httpsSplit.getAddresses(), ImmutableList.of(HostAddress.fromString("example.com")));
        assertEquals(httpsSplit.isRemotelyAccessible(), true);

        // http split with custom port
        httpsSplit = new ExampleSplit("https://example.com:8443/example");
        assertEquals(httpsSplit.getAddresses(), ImmutableList.of(HostAddress.fromParts("example.com", 8443)));
        assertEquals(httpsSplit.isRemotelyAccessible(), true);
    }

    @Test
    public void testJsonRoundTrip()
    {
        JsonCodec<ExampleSplit> codec = jsonCodec(ExampleSplit.class);
        String json = codec.toJson(split);
        ExampleSplit copy = codec.fromJson(json);
        assertEquals(copy.getUri(), split.getUri());

        assertEquals(copy.getAddresses(), ImmutableList.of(HostAddress.fromString("127.0.0.1")));
        assertEquals(copy.isRemotelyAccessible(), true);
    }
}
