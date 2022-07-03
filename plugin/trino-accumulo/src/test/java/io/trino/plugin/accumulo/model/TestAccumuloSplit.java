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
package io.trino.plugin.accumulo.model;

import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import org.apache.accumulo.core.data.Range;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;

public class TestAccumuloSplit
{
    private final JsonCodec<AccumuloSplit> codec = JsonCodec.jsonCodec(AccumuloSplit.class);

    @Test
    public void testJsonRoundTrip()
    {
        AccumuloSplit expected = new AccumuloSplit(
                ImmutableList.of(new Range(), new Range("bar", "foo"), new Range("bar", false, "baz", false)).stream().map(SerializedRange::serialize).collect(Collectors.toList()),
                Optional.of("localhost:9000"));

        String json = codec.toJson(expected);
        AccumuloSplit actual = codec.fromJson(json);
        assertSplit(actual, expected);
    }

    @Test
    public void testJsonRoundTripEmptyThings()
    {
        AccumuloSplit expected = new AccumuloSplit(
                ImmutableList.of(),
                Optional.empty());

        String json = codec.toJson(expected);
        AccumuloSplit actual = codec.fromJson(json);
        assertSplit(actual, expected);
    }

    private static void assertSplit(AccumuloSplit actual, AccumuloSplit expected)
    {
        assertEquals(actual.getAddresses(), expected.getAddresses());
        assertEquals(actual.getHostPort(), expected.getHostPort());
        assertEquals(actual.getRanges(), expected.getRanges());
    }
}
