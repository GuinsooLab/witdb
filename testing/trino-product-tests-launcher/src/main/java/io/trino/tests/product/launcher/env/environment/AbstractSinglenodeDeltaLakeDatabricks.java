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
package io.trino.tests.product.launcher.env.environment;

import io.trino.tests.product.launcher.docker.DockerFiles;
import io.trino.tests.product.launcher.env.DockerContainer;
import io.trino.tests.product.launcher.env.Environment;
import io.trino.tests.product.launcher.env.EnvironmentProvider;
import io.trino.tests.product.launcher.env.common.Standard;

import static io.trino.tests.product.launcher.env.EnvironmentContainers.COORDINATOR;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.TESTS;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.configureTempto;
import static io.trino.tests.product.launcher.env.common.Standard.CONTAINER_PRESTO_ETC;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

/**
 * Trino with Delta Lake connector and real S3 storage
 */
public abstract class AbstractSinglenodeDeltaLakeDatabricks
        extends EnvironmentProvider
{
    private final DockerFiles dockerFiles;

    abstract String databricksTestJdbcUrl();

    public AbstractSinglenodeDeltaLakeDatabricks(Standard standard, DockerFiles dockerFiles)
    {
        super(standard);
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
    }

    @Override
    public void extendEnvironment(Environment.Builder builder)
    {
        String databricksTestJdbcUrl = databricksTestJdbcUrl();
        String databricksTestJdbcDriverClass = requireNonNull(System.getenv("DATABRICKS_TEST_JDBC_DRIVER_CLASS"), "Environment DATABRICKS_TEST_JDBC_DRIVER_CLASS was not set");
        String databricksTestLogin = requireNonNull(System.getenv("DATABRICKS_TEST_LOGIN"), "Environment DATABRICKS_TEST_LOGIN was not set");
        String databricksTestToken = requireNonNull(System.getenv("DATABRICKS_TEST_TOKEN"), "Environment DATABRICKS_TEST_TOKEN was not set");
        String hiveMetastoreUri = requireNonNull(System.getenv("HIVE_METASTORE_URI"), "Environment HIVE_METASTORE_URI was not set");
        String s3Bucket = requireNonNull(System.getenv("S3_BUCKET"), "Environment S3_BUCKET was not set");
        DockerFiles.ResourceProvider configDir = dockerFiles.getDockerFilesHostDirectory("conf/environment/singlenode-delta-lake-databricks");

        builder.configureContainer(COORDINATOR, dockerContainer -> exportAWSCredentials(dockerContainer)
                .withEnv("HIVE_METASTORE_URI", hiveMetastoreUri)
                .withEnv("DATABRICKS_TEST_JDBC_URL", databricksTestJdbcUrl)
                .withEnv("DATABRICKS_TEST_LOGIN", databricksTestLogin)
                .withEnv("DATABRICKS_TEST_TOKEN", databricksTestToken));
        builder.addConnector("hive", forHostPath(configDir.getPath("hive.properties")));
        builder.addConnector(
                "delta-lake",
                forHostPath(configDir.getPath("delta.properties")),
                CONTAINER_PRESTO_ETC + "/catalog/delta.properties");

        builder.configureContainer(TESTS, container -> exportAWSCredentials(container)
                .withEnv("S3_BUCKET", s3Bucket)
                .withEnv("DATABRICKS_TEST_JDBC_DRIVER_CLASS", databricksTestJdbcDriverClass)
                .withEnv("DATABRICKS_TEST_JDBC_URL", databricksTestJdbcUrl)
                .withEnv("DATABRICKS_TEST_LOGIN", databricksTestLogin)
                .withEnv("DATABRICKS_TEST_TOKEN", databricksTestToken)
                .withEnv("HIVE_METASTORE_URI", hiveMetastoreUri));

        configureTempto(builder, configDir);
    }

    private DockerContainer exportAWSCredentials(DockerContainer container)
    {
        container = exportAWSCredential(container, "AWS_ACCESS_KEY_ID", true);
        container = exportAWSCredential(container, "AWS_SECRET_ACCESS_KEY", true);
        return exportAWSCredential(container, "AWS_SESSION_TOKEN", false);
    }

    private DockerContainer exportAWSCredential(DockerContainer container, String credentialEnvVariable, boolean required)
    {
        String credentialValue = System.getenv(credentialEnvVariable);
        if (credentialValue == null) {
            if (required) {
                throw new IllegalStateException(format("Environment variable %s not set", credentialEnvVariable));
            }
            return container;
        }
        return container.withEnv(credentialEnvVariable, credentialValue);
    }
}
