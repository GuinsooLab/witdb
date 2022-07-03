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
package io.trino.plugin.geospatial;

import io.airlift.slice.Slice;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.jmh.Benchmarks.benchmark;
import static io.trino.plugin.geospatial.GeoFunctions.stGeometryFromText;
import static io.trino.plugin.geospatial.GeoFunctions.toSphericalGeography;
import static io.trino.plugin.geospatial.GeometryBenchmarkUtils.loadPolygon;
import static org.testng.Assert.assertEquals;

@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkSTArea
{
    @Benchmark
    public Object stSphericalArea(BenchmarkData data)
    {
        return GeoFunctions.stSphericalArea(data.geography);
    }

    @Benchmark
    public Object stSphericalArea500k(BenchmarkData data)
    {
        return GeoFunctions.stSphericalArea(data.geography500k);
    }

    @Benchmark
    public Object stArea(BenchmarkData data)
    {
        return GeoFunctions.stArea(data.geometry);
    }

    @Benchmark
    public Object stArea500k(BenchmarkData data)
    {
        return GeoFunctions.stArea(data.geometry500k);
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private Slice geometry;
        private Slice geometry500k;
        private Slice geography;
        private Slice geography500k;

        @Setup
        public void setup()
                throws IOException
        {
            geometry = stGeometryFromText(utf8Slice(loadPolygon("large_polygon.txt")));
            geometry500k = stGeometryFromText(utf8Slice(createPolygon(500000)));
            geography = toSphericalGeography(geometry);
            geography500k = toSphericalGeography(geometry500k);
        }
    }

    public static void main(String[] args)
            throws IOException, RunnerException
    {
        // assure the benchmarks are valid before running
        verify();

        benchmark(BenchmarkSTArea.class).run();
    }

    @Test
    public static void verify()
            throws IOException
    {
        BenchmarkData data = new BenchmarkData();
        data.setup();
        BenchmarkSTArea benchmark = new BenchmarkSTArea();

        assertEquals(Math.round(1000 * (Double) benchmark.stSphericalArea(data) / 3.659E8), 1000);
        assertEquals(Math.round(1000 * (Double) benchmark.stSphericalArea500k(data) / 38842273735.0), 1000);
        assertEquals(benchmark.stArea(data), 0.05033099592771004);
        assertEquals(Math.round(1000 * (Double) benchmark.stArea500k(data) / Math.PI), 1000);
    }

    private static String createPolygon(int vertexCount)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("POLYGON((");
        builder.append(IntStream.range(0, vertexCount)
                .mapToObj(i -> {
                    double angle = i * Math.PI * 2 / vertexCount;
                    return Math.cos(angle) + " " + Math.sin(angle);
                })
                .collect(Collectors.joining(",")));
        builder.append("))");
        return builder.toString();
    }
}
