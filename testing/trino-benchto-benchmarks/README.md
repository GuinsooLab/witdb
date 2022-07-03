# Presto Benchto benchmarks

The Benchto benchmarks utilize [Benchto](https://github.com/trinodb/benchto) benchmarking
utility to do macro benchmarking of Presto. As opposed to micro benchmarking which exercises
a class or a small, coherent set of classes, macro benchmarks done with Benchto use Presto
end-to-end, by accessing it through its API (usually with `presto-jdbc`), executing queries,
measuring time and gathering various metrics.

## Benchmarking suites

Even though benchmarks exercise Presto end-to-end, a single benchmark cannot use all Presto
features. Therefore benchmarks are organized in suites, like:

* *tpch* - queries closely following the [TPC-H](http://www.tpc.org/tpch/) benchmark
* *tpcds* - queries closely following the [TPC-DS](http://www.tpc.org/tpcds/) benchmark

## Usage

### Requirements

* Presto already installed on the target environment
* Basic understanding of Benchto [components and architecture](https://github.com/trinodb/benchto)
* Benchto service [configured and running](https://github.com/trinodb/benchto/tree/master/benchto-service)
* An environment [defined in Benchto service](https://github.com/trinodb/benchto/tree/master/benchto-service#creating-environment)

### Configuring benchmarks

Benchto driver needs to know two things: what benchmark is to be run and what environment
it is to be run on. For the purpose of the following example, we will use `tpch` benchmark
and Presto server running at `localhost:8080`, with Benchto service running at `localhost:8081`.

Benchto driver uses Spring Boot to locate environment configuration file, so to pass the
configuration. To continue with our example, one needs to place an `application-presto-devenv.yaml`
file in the current directory (i.e. the directory from which the benchmark will be invoked),
with the following content:

```yaml
benchmark-service:
  url: http://localhost:8081

data-sources:
  trino:
    url: jdbc:trino://localhost:8080
    username: na
    password: na
    driver-class-name: io.trino.jdbc.TrinoDriver

environment:
  name: TRINO-DEV

presto:
  url: http://localhost:8080

benchmark:
  feature:
    trino:
      metrics.collection.enabled: true

macros:
  sleep-4s:
    command: echo "Sleeping for 4s" && sleep 4
```

### Bootstrapping benchmark data

* Make sure you have configured [Presto TPC-H connector](https://trino.io/docs/current/connector/tpch.html).
* Bootstrap benchmark data:
  ```bash
  python presto-benchto-benchmarks/generate_schemas/generate-tpch.py | presto-cli-[version]-executable.jar --server [presto_coordinator-url]:[port]
  ```

### Configuring overrides file

It is possible to override benchmark variables with benchto-driver overrides feature.
This is useful for instance when one wants to use different number of benchmark
runs or different underlying schemas. Create a simple `overrides.yaml` file:

```yaml
runs: 10
tpch_medium: tpcds_10gb_txt
```

### Running benchto-driver

With the scene set up as in the previous section, the benchmark can be run with:
```bash
./mvnw clean package -pl :trino-benchto-benchmarks
java -Xmx1g -jar trino-benchto-benchmarks/target/trino-benchto-benchmarks-*-executable.jar \
    --sql trino-benchto-benchmarks/src/main/resources/sql \
    --benchmarks trino-benchto-benchmarks/src/main/resources/benchmarks \
    --activeBenchmarks=presto/tpch --profile=presto-devenv \
    --overrides overrides.yaml
```
