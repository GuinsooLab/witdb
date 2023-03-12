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
package io.trino.spi.metrics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.trino.spi.Mergeable;
import io.trino.spi.Unstable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import static java.util.Objects.requireNonNull;

public class Metrics
        implements Mergeable<Metrics>
{
    public static final Metrics EMPTY = new Metrics(Map.of());

    private final Map<String, Metric<?>> metrics;

    @JsonCreator
    @Unstable
    public Metrics(Map<String, Metric<?>> metrics)
    {
        this.metrics = Map.copyOf(requireNonNull(metrics, "metrics is null"));
    }

    @JsonValue
    public Map<String, Metric<?>> getMetrics()
    {
        return metrics;
    }

    @Override
    public Metrics mergeWith(Metrics other)
    {
        return accumulator().add(this).add(other).get();
    }

    public static Accumulator accumulator()
    {
        return new Accumulator();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Metrics)) {
            return false;
        }
        Metrics that = (Metrics) o;
        return metrics.equals(that.metrics);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(metrics);
    }

    @Override
    public String toString()
    {
        return new StringJoiner(", ", Metrics.class.getSimpleName() + "[", "]")
                .add(metrics.toString())
                .toString();
    }

    public static class Accumulator
    {
        private final Map<String, Metric<?>> merged = new HashMap<>();

        private Accumulator()
        {
        }

        public Accumulator add(Metrics metrics)
        {
            metrics.getMetrics().forEach((key, value) ->
                    merged.merge(key, value, Accumulator::merge));
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Metric<?> merge(Metric<?> a, Metric<?> b)
        {
            return (Metric<?>) ((Metric) a).mergeWith(b);
        }

        public Metrics get()
        {
            if (merged.isEmpty()) {
                return EMPTY;
            }
            return new Metrics(merged);
        }
    }
}
