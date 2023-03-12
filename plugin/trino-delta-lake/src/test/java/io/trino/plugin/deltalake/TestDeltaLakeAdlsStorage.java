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
package io.trino.plugin.deltalake;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import io.trino.plugin.hive.containers.HiveHadoop;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.testcontainers.containers.Network;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static io.trino.plugin.deltalake.DeltaLakeQueryRunner.DELTA_CATALOG;
import static io.trino.plugin.deltalake.DeltaLakeQueryRunner.createAbfsDeltaLakeQueryRunner;
import static io.trino.testing.containers.TestContainers.getPathFromClassPathResource;
import static io.trino.tpch.TpchTable.CUSTOMER;
import static io.trino.tpch.TpchTable.NATION;
import static io.trino.tpch.TpchTable.REGION;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;

public class TestDeltaLakeAdlsStorage
        extends AbstractTestQueryFramework
{
    private static final String HADOOP_BASE_IMAGE = System.getenv().getOrDefault("HADOOP_BASE_IMAGE", "ghcr.io/trinodb/testing/hdp3.1-hive");
    private static final String SCHEMA_NAME = "default";
    private static final List<String> TABLES = ImmutableList.of(NATION.getTableName(), REGION.getTableName(), CUSTOMER.getTableName());

    private final String account;
    private final String accessKey;

    private final String adlsDirectory;

    private HiveHadoop hiveHadoop;

    @Parameters({
            "hive.hadoop2.azure-abfs-container",
            "hive.hadoop2.azure-abfs-account",
            "hive.hadoop2.azure-abfs-access-key"})
    public TestDeltaLakeAdlsStorage(String container, String account, String accessKey)
    {
        requireNonNull(container, "container is null");
        this.account = requireNonNull(account, "account is null");
        this.accessKey = requireNonNull(accessKey, "accessKey is null");

        String directoryBase = format("abfs://%s@%s.dfs.core.windows.net", container, account);
        adlsDirectory = format("%s/tpch-tiny-%s/", directoryBase, randomUUID());
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Path hadoopCoreSiteXmlTempFile = createHadoopCoreSiteXmlTempFileWithAbfsSettings();
        hiveHadoop = closeAfterClass(HiveHadoop.builder()
                .withNetwork(Network.newNetwork())
                .withImage(HADOOP_BASE_IMAGE)
                .withFilesToMount(ImmutableMap.of(
                        "/tmp/tpch-tiny", getPathFromClassPathResource("io/trino/plugin/deltalake/testing/resources/databricks"),
                        "/etc/hadoop/conf/core-site.xml", hadoopCoreSiteXmlTempFile.toString()))
                .build());
        hiveHadoop.start();
        return createAbfsDeltaLakeQueryRunner(
                DELTA_CATALOG,
                SCHEMA_NAME,
                ImmutableMap.of(),
                ImmutableMap.of("delta.register-table-procedure.enabled", "true"),
                hiveHadoop);
    }

    private Path createHadoopCoreSiteXmlTempFileWithAbfsSettings()
            throws Exception
    {
        String abfsSpecificCoreSiteXmlContent = Resources.toString(Resources.getResource("io/trino/plugin/deltalake/hdp3.1-core-site.xml.abfs-template"), UTF_8)
                .replace("%ABFS_ACCESS_KEY%", accessKey)
                .replace("%ABFS_ACCOUNT%", account);

        FileAttribute<Set<PosixFilePermission>> posixFilePermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"));
        Path coreSiteXml = Files.createTempFile("core-site", ".xml", posixFilePermissions);
        coreSiteXml.toFile().deleteOnExit();
        Files.writeString(coreSiteXml, abfsSpecificCoreSiteXmlContent);

        return coreSiteXml;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp()
    {
        hiveHadoop.executeInContainerFailOnError("hadoop", "fs", "-mkdir", "-p", adlsDirectory);
        TABLES.forEach(table -> {
            hiveHadoop.executeInContainerFailOnError("hadoop", "fs", "-copyFromLocal", "-f", "/tmp/tpch-tiny/" + table, adlsDirectory);
            getQueryRunner().execute(format("CALL system.register_table('%s', '%s', '%s/%s')", SCHEMA_NAME, table, adlsDirectory, table));
        });
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        if (adlsDirectory != null && hiveHadoop != null) {
            hiveHadoop.executeInContainerFailOnError("hadoop", "fs", "-rm", "-f", "-r", adlsDirectory);
        }
    }

    @Test
    public void testQuery()
    {
        assertQuery("SELECT n.name FROM nation n JOIN region r ON n.regionkey = r.regionkey WHERE r.name = 'EUROPE'");
        // the customer table's transaction log has a checkpoint for it
        assertQuery("SELECT count(*) FROM nation n JOIN customer c ON n.nationkey = c.nationkey WHERE n.name = 'ROMANIA'", "SELECT 64");
    }
}
