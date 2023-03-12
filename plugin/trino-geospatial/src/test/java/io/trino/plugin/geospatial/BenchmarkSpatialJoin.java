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

import com.google.common.collect.ImmutableMap;
import io.trino.metadata.Metadata;
import io.trino.metadata.QualifiedObjectName;
import io.trino.metadata.TableHandle;
import io.trino.plugin.memory.MemoryConnectorFactory;
import io.trino.testing.LocalQueryRunner;
import io.trino.testing.MaterializedResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.io.Resources.getResource;
import static io.trino.jmh.Benchmarks.benchmark;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;
import static org.testng.Assert.assertTrue;

@SuppressWarnings("MethodMayBeStatic")
@State(Thread)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(AverageTime)
@Fork(3)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
public class BenchmarkSpatialJoin
{
    @State(Thread)
    public static class Context
    {
        private LocalQueryRunner queryRunner;

        @Param({"10", "100", "1000", "10000"})
        private int pointCount;

        public LocalQueryRunner getQueryRunner()
        {
            return queryRunner;
        }

        @Setup
        public void setUp()
                throws Exception
        {
            queryRunner = LocalQueryRunner.create(testSessionBuilder()
                    .setCatalog("memory")
                    .setSchema("default")
                    .build());
            queryRunner.installPlugin(new GeoPlugin());
            queryRunner.createCatalog("memory", new MemoryConnectorFactory(), ImmutableMap.of());

            Path path = new File(getResource("us-states.tsv").toURI()).toPath();
            String polygonValues;
            try (Stream<String> lines = Files.lines(path)) {
                polygonValues = lines
                        .map(line -> line.split("\t"))
                        .map(parts -> format("('%s', '%s')", parts[0], parts[1]))
                        .collect(Collectors.joining(","));
            }
            queryRunner.execute(format("CREATE TABLE memory.default.polygons AS SELECT * FROM (VALUES %s) as t (name, wkt)", polygonValues));
        }

        @Setup(Level.Invocation)
        public void createPointsTable()
        {
            // Generate random points within the approximate bounding box of the US polygon:
            //  POLYGON ((-124 27, -65 27, -65 49, -124 49, -124 27))
            queryRunner.execute(format("CREATE TABLE memory.default.points AS " +
                    "SELECT 'p' || cast(elem AS VARCHAR) as name, xMin + (xMax - xMin) * random() as longitude, yMin + (yMax - yMin) * random() as latitude " +
                    "FROM (SELECT -124 AS xMin, -65 AS xMax, 27 AS yMin, 49 AS yMax) " +
                    "CROSS JOIN UNNEST(sequence(1, %s)) AS t(elem)", pointCount));
        }

        @TearDown(Level.Invocation)
        public void dropPointsTable()
        {
            queryRunner.inTransaction(queryRunner.getDefaultSession(), transactionSession -> {
                Metadata metadata = queryRunner.getMetadata();
                QualifiedObjectName tableName = QualifiedObjectName.valueOf("memory.default.points");
                Optional<TableHandle> tableHandle = metadata.getTableHandle(transactionSession, tableName);
                assertTrue(tableHandle.isPresent(), "Table memory.default.points does not exist");
                metadata.dropTable(transactionSession, tableHandle.get(), tableName.asCatalogSchemaTableName());
                return null;
            });
        }

        @TearDown
        public void tearDown()
        {
            queryRunner.close();
            queryRunner = null;
        }
    }

    @Benchmark
    public MaterializedResult benchmarkJoin(Context context)
    {
        return context.getQueryRunner()
                .execute("SELECT count(*) FROM points, polygons WHERE ST_Contains(ST_GeometryFromText(wkt), ST_Point(latitude, longitude))");
    }

    @Benchmark
    public MaterializedResult benchmarkUserOptimizedJoin(Context context)
    {
        return context.getQueryRunner()
                .execute("SELECT count(*) FROM (SELECT ST_Point(latitude, longitude) as point FROM points) t1, (SELECT ST_GeometryFromText(wkt) as geometry FROM polygons) t2 WHERE ST_Contains(geometry, point)");
    }

    @Test
    public void verify()
            throws Exception
    {
        Context context = new Context();
        try {
            context.setUp();
            context.createPointsTable();

            BenchmarkSpatialJoin benchmark = new BenchmarkSpatialJoin();
            benchmark.benchmarkJoin(context);
            benchmark.benchmarkUserOptimizedJoin(context);
        }
        finally {
            context.queryRunner.close();
        }
    }

    public static void main(String[] args)
            throws Exception
    {
        // assure the benchmarks are valid before running
        new BenchmarkSpatialJoin().verify();

        benchmark(BenchmarkSpatialJoin.class).run();
    }
}
