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

import com.google.common.collect.ImmutableList;
import io.trino.tests.product.launcher.docker.DockerFiles;
import io.trino.tests.product.launcher.docker.DockerFiles.ResourceProvider;
import io.trino.tests.product.launcher.env.DockerContainer;
import io.trino.tests.product.launcher.env.Environment;
import io.trino.tests.product.launcher.env.EnvironmentConfig;
import io.trino.tests.product.launcher.env.EnvironmentProvider;
import io.trino.tests.product.launcher.env.common.HadoopKerberos;
import io.trino.tests.product.launcher.env.common.Standard;
import io.trino.tests.product.launcher.env.common.TestsEnvironment;
import io.trino.tests.product.launcher.testcontainers.PortBinder;

import javax.inject.Inject;

import static io.trino.tests.product.launcher.env.EnvironmentContainers.HADOOP;
import static io.trino.tests.product.launcher.env.common.Hadoop.createHadoopContainer;
import static io.trino.tests.product.launcher.env.common.Standard.CONTAINER_TRINO_ETC;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

/**
 * Two pseudo-distributed Hadoop installations running on side-by-side,
 * each within single container (one kerberized and one not), with single-node,
 * kerberized Trino.
 */
@TestsEnvironment
public final class EnvTwoMixedHives
        extends EnvironmentProvider
{
    private final DockerFiles dockerFiles;
    private final ResourceProvider configDir;

    private final String hadoopBaseImage;
    private final String hadoopImagesVersion;
    private final PortBinder portBinder;

    @Inject
    public EnvTwoMixedHives(
            DockerFiles dockerFiles,
            PortBinder portBinder,
            Standard standard,
            HadoopKerberos hadoopKerberos,
            EnvironmentConfig environmentConfig)
    {
        super(ImmutableList.of(standard, hadoopKerberos));
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
        configDir = dockerFiles.getDockerFilesHostDirectory("conf/environment/two-mixed-hives");
        this.portBinder = requireNonNull(portBinder, "portBinder is null");
        hadoopBaseImage = environmentConfig.getHadoopBaseImage();
        hadoopImagesVersion = environmentConfig.getHadoopImagesVersion();
    }

    @Override
    public void extendEnvironment(Environment.Builder builder)
    {
        builder.addConnector("hive", forHostPath(configDir.getPath("hive1.properties")), CONTAINER_TRINO_ETC + "/catalog/hive1.properties");
        builder.addConnector("hive", forHostPath(configDir.getPath("hive2.properties")), CONTAINER_TRINO_ETC + "/catalog/hive2.properties");
        builder.addConnector("iceberg", forHostPath(configDir.getPath("iceberg1.properties")), CONTAINER_TRINO_ETC + "/catalog/iceberg1.properties");
        builder.addConnector("iceberg", forHostPath(configDir.getPath("iceberg2.properties")), CONTAINER_TRINO_ETC + "/catalog/iceberg2.properties");

        builder.addContainer(createHadoopMaster2());
    }

    @SuppressWarnings("resource")
    private DockerContainer createHadoopMaster2()
    {
        return createHadoopContainer(dockerFiles, new PortBinder.ShiftingPortBinder(portBinder, 10000), hadoopBaseImage + ":" + hadoopImagesVersion, HADOOP + "-2")
                .withCopyFileToContainer(
                        forHostPath(configDir.getPath("hadoop-master-2/core-site.xml")),
                        "/etc/hadoop/conf/core-site.xml")
                .withCopyFileToContainer(
                        forHostPath(configDir.getPath("hadoop-master-2/mapred-site.xml")),
                        "/etc/hadoop/conf/mapred-site.xml")
                .withCopyFileToContainer(
                        forHostPath(configDir.getPath("hadoop-master-2/yarn-site.xml")),
                        "/etc/hadoop/conf/yarn-site.xml");
    }
}
