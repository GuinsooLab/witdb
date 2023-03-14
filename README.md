<div align="right">
    <img src="https://raw.githubusercontent.com/GuinsooLab/glab/main/src/images/guinsoolab-badge.png" height="60" alt="badge">
    <br />
</div>
<div align="center">
    <img src="https://raw.githubusercontent.com/GuinsooLab/glab/main/src/images/guinsoolab-witdb.svg" alt="logo" height="100" />
    <br />
    <br />
</div>

# WitDB

WitDB is a distributed SQL query engine designed to query large data sets distributed over one or more heterogeneous data sources.

WitDB is a tool designed to efficiently query vast amounts of data using distributed queries. If you work with terabytes or petabytes of data, you are likely using tools that interact with Hadoop and HDFS. WitDB was designed as an alternative to tools that query HDFS using pipelines of MapReduce jobs, such as Hive or Pig, but WitDB is not limited to accessing HDFS. 

WitDB can be and has been extended to operate over different kinds of data sources, including traditional relational databases and other data sources such as Cassandra.

## Development

See [DEVELOPMENT](.github/DEVELOPMENT.md) for information about code style,
development process, and guidelines.

See [CONTRIBUTING](.github/CONTRIBUTING.md) for contribution requirements.

## Security

See the project [security policy](.github/SECURITY.md) for
information about reporting vulnerabilities.

## Build requirements

* Mac OS X or Linux
* Java 17.0.6+, 64-bit
* Docker

## Building WitDB

WitDB is a standard Maven project. Simply run the following command from the
project root directory:

    ./mvnw clean install -DskipTests

On the first build, Maven downloads all the dependencies from the internet
and caches them in the local repository (`~/.m2/repository`), which can take a
while, depending on your connection speed. Subsequent builds are faster.

WitDB has a comprehensive set of tests that take a considerable amount of time
to run, and are thus disabled by the above command. These tests are run by the
CI system when you submit a pull request. We recommend only running tests
locally for the areas of code that you change.

## Running WitDB in your IDE

### Overview

After building WitDB for the first time, you can load the project into your IDE
and run the server.  We recommend using
[IntelliJ IDEA](http://www.jetbrains.com/idea/). Because WitDB is a standard
Maven project, you easily can import it into your IDE.  In IntelliJ, choose
*Open Project* from the *Quick Start* box or choose *Open*
from the *File* menu and select the root `pom.xml` file.

After opening the project in IntelliJ, double check that the Java SDK is
properly configured for the project:

* Open the File menu and select Project Structure
* In the SDKs section, ensure that JDK 11 is selected (create one if none exist)
* In the Project section, ensure the Project language level is set to 11

### Running a testing server

The simplest way to run WitDB for development is to run the `TpchQueryRunner`
class. It will start a development version of the server that is configured with
the TPCH connector. You can then use the CLI to execute queries against this
server. Many other connectors have their own `*QueryRunner` class that you can
use when working on a specific connector.

### Running the CLI

Start the CLI to connect to the server and run SQL queries:

    client/trino-cli/target/trino-cli-*-executable.jar

Run a query to see the nodes in the cluster:

    SELECT * FROM system.runtime.nodes;

Run a query against the TPCH connector:

    SELECT * FROM tpch.tiny.region;
    
## License

WitDB is released under [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

<img src="https://raw.githubusercontent.com/GuinsooLab/glab/main/src/images/guinsoolab-group.svg" width="120" alt="license" />

